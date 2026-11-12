import test from 'node:test'
import assert from 'node:assert/strict'
import { link, mkdtemp, mkdir, readFile, realpath, readdir, rename, rm, symlink, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFile } from 'node:child_process'
import childProcess from 'node:child_process'
import { EventEmitter } from 'node:events'
import { syncBuiltinESMExports } from 'node:module'
import { PassThrough, Writable } from 'node:stream'
import { promisify } from 'node:util'
import { createHash } from 'node:crypto'
import { setImmediate as yieldTurn } from 'node:timers/promises'
import { createWriteGate } from '../src/executor.mjs'

const macOnly = { skip: process.platform !== 'darwin' }

async function fixture() {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-root-')))
  const state = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-state-')))
  return {
    root, state,
    gate: (attemptId, isAuthorized = () => true) => createWriteGate({
      projectDirectory: root, stateDirectory: state, attemptId, agentInstanceId: `agent-${attemptId}`,
      isAuthorized, knownCredentials: ['fixture-claim-secret'],
    }),
    async clean() { await rm(root, { recursive: true, force: true }); await rm(state, { recursive: true, force: true }) },
  }
}

test('unsupported platforms reject editor startup without an unrestricted fallback', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform')
  try {
    Object.defineProperty(process, 'platform', { ...descriptor, value: 'linux' })
    await assert.rejects(createWriteGate({}), /不支持此平台/)
  } finally { Object.defineProperty(process, 'platform', descriptor) }
})

test('managed directory browsing pages project entries and rejects external links', macOnly, async () => {
  const f = await fixture()
  try {
    await mkdir(path.join(f.root, 'src'))
    await mkdir(path.join(f.root, '.git'))
    await symlink(f.state, path.join(f.root, 'outside'))
    await Promise.all(Array.from({ length: 101 }, (_, i) => writeFile(path.join(f.root, 'src', `file-${String(i).padStart(3, '0')}`), 'fixture')))
    const gate = await f.gate(10)
    assert.deepEqual(await gate.listFiles({ path: '.' }), { entries: [{ path: 'src', type: 'directory' }], nextAfter: null })
    const first = await gate.listFiles({ path: 'src' })
    assert.equal(first.entries.length, 100)
    assert.deepEqual(await gate.listFiles({ path: 'src', after: first.nextAfter }), {
      entries: [{ path: 'src/file-100', type: 'file' }], nextAfter: null,
    })
    await assert.rejects(gate.listFiles({ path: 'outside' }), /符号链接/)
    await assert.rejects(gate.listFiles({ path: '../outside' }), /规范化相对路径/)
    await gate.sealAndDrain()
    await assert.rejects(gate.listFiles({ path: '.' }), /已停止/)
  } finally { await f.clean() }
})

test('different attempts edit the same file with shared per-operation consistency', macOnly, async () => {
  const f = await fixture()
  try {
    await mkdir(path.join(f.root, 'src'))
    await writeFile(path.join(f.root, 'src', 'shared.txt'), 'first\nsecond\n')
    const a = await f.gate(11), b = await f.gate(12)
    const original = await a.readFile({ path: 'src/shared.txt' })
    const observed = await b.readFile({ path: 'src/shared.txt' })
    assert.equal(original.version, observed.version)
    const results = await Promise.allSettled([
      a.applyPatch({ operationId: 'edit-a', path: original.path, version: original.version, oldText: 'first', newText: 'FIRST' }),
      b.applyPatch({ operationId: 'edit-b', path: observed.path, version: observed.version, oldText: 'second', newText: 'SECOND' }),
    ])
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1)
    assert.equal(results.filter(result => result.status === 'rejected').length, 1)
    assert.match(results.find(result => result.status === 'rejected').reason.message, /冲突|变化/)
    const staleGate = results[0].status === 'rejected' ? a : b
    const staleWord = results[0].status === 'rejected' ? 'first' : 'second'
    const staleAttempt = results[0].status === 'rejected' ? '11' : '12'
    const staleOperation = results[0].status === 'rejected' ? 'edit-a' : 'edit-b'
    const staleFact = JSON.parse(await readFile(path.join(f.state, 'file-operations', staleAttempt, `${staleOperation}.json`), 'utf8'))
    assert.equal(staleFact.status, 'rejected')
    assert.deepEqual(staleFact.cleanupPaths, [])
    assert.deepEqual(staleGate.operationFacts(), [])
    assert.equal(staleGate.accepting, true)
    const latest = await staleGate.readFile({ path: 'src/shared.txt' })
    await staleGate.applyPatch({ operationId: 'retry', path: latest.path, version: latest.version,
      oldText: staleWord, newText: staleWord.toUpperCase() })
    assert.equal(await readFile(path.join(f.root, 'src', 'shared.txt'), 'utf8'), 'FIRST\nSECOND\n')
    assert.deepEqual(a.changedPaths(), ['src/shared.txt'])
    assert.deepEqual(b.changedPaths(), ['src/shared.txt'])
    for (const gate of [a, b]) {
      const fact = gate.operationFacts()[0]
      assert.equal(typeof fact.attemptId, 'number')
      assert.equal(typeof fact.agentInstanceId, 'string')
      assert.equal(typeof fact.operationId, 'string')
      assert.deepEqual(fact.changedPaths, ['src/shared.txt'])
    }
  } finally { await f.clean() }
})

test('create, replace, patch, rename and delete respect the observed version', macOnly, async () => {
  const f = await fixture()
  try {
    const gate = await f.gate(13)
    await gate.makeDirectory({ operationId: 'mkdir', path: 'work' })
    await gate.createFile({ operationId: 'create', path: 'work/a.txt', content: 'same same' })
    await assert.rejects(gate.createFile({ operationId: 'duplicate', path: 'work/a.txt', content: 'bad' }), /存在|冲突/)
    const first = await gate.readFile({ path: 'work/a.txt' })
    await assert.rejects(gate.applyPatch({ operationId: 'ambiguous', path: first.path, version: first.version,
      oldText: 'same', newText: 'bad' }), /歧义|冲突/)
    await gate.replaceFile({ operationId: 'replace', path: first.path, version: first.version, content: 'one two' })
    await assert.rejects(gate.replaceFile({ operationId: 'stale-replace', path: first.path, version: first.version, content: 'bad' }), /冲突|变化/)
    await assert.rejects(gate.removeFile({ operationId: 'stale-remove', path: first.path, version: first.version }), /冲突|变化/)
    await assert.rejects(gate.renameFile({ operationId: 'stale-rename', from: first.path, to: 'work/b.txt', version: first.version }), /冲突|变化/)
    const current = await gate.readFile({ path: first.path })
    await gate.createFile({ operationId: 'create-other', path: 'work/b.txt', content: 'other' })
    await assert.rejects(gate.renameFile({ operationId: 'rename-existing', from: first.path, to: 'work/b.txt', version: current.version }), /存在|冲突/)
    await gate.renameFile({ operationId: 'rename', from: first.path, to: 'work/c.txt', version: current.version })
    const moved = await gate.readFile({ path: 'work/c.txt' })
    assert.equal(moved.content, 'one two')
    await gate.removeFile({ operationId: 'remove', path: moved.path, version: moved.version })
    await assert.rejects(readFile(path.join(f.root, 'work', 'c.txt'), 'utf8'))
    assert.equal(await readFile(path.join(f.root, 'work', 'b.txt'), 'utf8'), 'other')
  } finally { await f.clean() }
})

test('same-content file replacement changes physical identity and invalidates an edit', macOnly, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'input.txt')
    await writeFile(filename, 'same content')
    const gate = await f.gate(130)
    const observed = await gate.readFile({ path: 'input.txt' })
    const replacement = path.join(f.root, 'replacement.txt')
    await writeFile(replacement, 'same content')
    await rename(replacement, filename)
    const current = await gate.readFile({ path: 'input.txt' })
    assert.equal(current.contentVersion, observed.contentVersion)
    assert.notEqual(current.version, observed.version)
    await assert.rejects(gate.replaceFile({ operationId: 'wrong-object', path: observed.path,
      version: observed.version, content: 'unsafe' }), /冲突|变化/)
    await assert.rejects(gate.removeFile({ operationId: 'wrong-object-remove', path: observed.path,
      version: observed.version }), /冲突|变化/)
    assert.equal(await readFile(filename, 'utf8'), 'same content')
  } finally { await f.clean() }
})

test('project boundary rejects symbolic links, hard links, aliases, private state and credentials', macOnly, async () => {
  const f = await fixture()
  const outside = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-outside-')))
  try {
    await mkdir(path.join(f.root, 'src'))
    await writeFile(path.join(outside, 'external.txt'), 'outside')
    await symlink(outside, path.join(f.root, 'src', 'link'))
    await link(path.join(outside, 'external.txt'), path.join(f.root, 'src', 'hard.txt'))
    await mkdir(path.join(f.root, '.git'))
    const gate = await f.gate(14)
    await assert.rejects(gate.createFile({ operationId: 'traversal', path: '../escape.txt', content: 'bad' }))
    await assert.rejects(gate.createFile({ operationId: 'symlink', path: 'src/link/new.txt', content: 'bad' }))
    await assert.rejects(gate.readFile({ path: 'src/hard.txt' }))
    await assert.rejects(gate.createFile({ operationId: 'git', path: '.git/config', content: 'bad' }))
    await assert.rejects(gate.createFile({ operationId: 'credential', path: 'src/a.txt', content: 'fixture-claim-secret' }))
    await assert.rejects(gate.createFile({ operationId: 'fixture-claim-secret', path: 'src/a.txt', content: 'bad' }))
    await writeFile(path.join(f.root, 'src', 'secret.txt'), 'fixture-claim-secret')
    await assert.rejects(gate.readFile({ path: 'src/secret.txt' }), /凭据/)
    assert.equal(await readFile(path.join(outside, 'external.txt'), 'utf8'), 'outside')
    const privateInside = path.join(f.root, 'host-private')
    await mkdir(privateInside)
    const privateGate = await createWriteGate({ projectDirectory: f.root, stateDirectory: privateInside,
      attemptId: 15, agentInstanceId: 'agent-15', isAuthorized: () => true, knownCredentials: [] })
    await assert.rejects(privateGate.createFile({ operationId: 'private', path: 'host-private/a', content: 'bad' }))
  } finally { await f.clean(); await rm(outside, { recursive: true, force: true }) }
})

test('real parent-directory swaps cannot read or write the outside directory', { timeout: 15000, skip: process.platform !== 'darwin' }, async () => {
  const f = await fixture()
  const outside = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-swap-outside-')))
  const target = path.join(f.root, 'directory'), held = path.join(f.root, 'held-directory')
  let running = true, swaps = 0
  try {
    await mkdir(target)
    await writeFile(path.join(target, 'value.txt'), 'project content')
    await writeFile(path.join(outside, 'value.txt'), 'outside content')
    const gates = await Promise.all(Array.from({ length: 20 }, (_, index) => f.gate(100 + index)))
    const attacker = (async () => {
      while (running) {
        await rename(target, held)
        await symlink(outside, target)
        await yieldTurn()
        await unlink(target)
        await rename(held, target)
        swaps++
        await yieldTurn()
      }
    })()
    const outcomes = await Promise.allSettled(gates.map(async (gate, index) => {
      const value = await gate.readFile({ path: 'directory/value.txt' })
      assert.equal(value.content, 'project content')
      await gate.createFile({ operationId: `swap-${index}`, path: `directory/new-${index}.txt`, content: 'project write' })
    }))
    running = false
    await attacker
    assert.ok(swaps > 0)
    for (const outcome of outcomes) if (outcome.status === 'rejected') {
      assert.ok(!outcome.reason.message.includes('outside content'))
      assert.ok(!outcome.reason.message.includes(outside))
    }
    assert.equal(await readFile(path.join(outside, 'value.txt'), 'utf8'), 'outside content')
    assert.deepEqual(await readdir(outside), ['value.txt'])
  } finally { running = false; await f.clean(); await rm(outside, { recursive: true, force: true }) }
})

test('real project-root swaps cannot transfer sandbox authority outside the original root', { timeout: 15000, skip: process.platform !== 'darwin' }, async () => {
  const f = await fixture()
  const outside = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-root-swap-outside-')))
  const held = `${f.root}-held`
  let running = true, swaps = 0
  try {
    await mkdir(path.join(f.root, 'directory'))
    await mkdir(path.join(outside, 'directory'))
    await writeFile(path.join(outside, 'directory', 'sentinel.txt'), 'outside content')
    const gates = await Promise.all(Array.from({ length: 20 }, (_, index) => f.gate(200 + index)))
    const attacker = (async () => {
      while (running) {
        await rename(f.root, held)
        await symlink(outside, f.root)
        await yieldTurn()
        await unlink(f.root)
        await rename(held, f.root)
        swaps++
        await yieldTurn()
      }
    })()
    await Promise.allSettled(gates.map((gate, index) => gate.createFile({
      operationId: `root-swap-${index}`, path: `directory/new-${index}.txt`, content: 'project write',
    })))
    running = false
    await attacker
    assert.ok(swaps > 0)
    assert.deepEqual(await readdir(path.join(outside, 'directory')), ['sentinel.txt'])
    assert.equal(await readFile(path.join(outside, 'directory', 'sentinel.txt'), 'utf8'), 'outside content')
  } finally { running = false; await f.clean(); await rm(held, { recursive: true, force: true }); await rm(outside, { recursive: true, force: true }) }
})

test('case aliases cannot mutate another file on case-insensitive volumes', macOnly, async t => {
  const f = await fixture()
  try {
    await mkdir(path.join(f.root, 'parent'))
    await writeFile(path.join(f.root, 'parent', 'name.txt'), 'original')
    try { await readFile(path.join(f.root, 'PARENT', 'NAME.txt')) } catch (error) {
      if (error.code === 'ENOENT') { t.skip('volume is case sensitive'); return }
      throw error
    }
    const gate = await f.gate(16)
    await assert.rejects(gate.readFile({ path: 'parent/NAME.txt' }))
    await assert.rejects(gate.readFile({ path: 'PARENT/name.txt' }))
    await assert.rejects(gate.createFile({ operationId: 'alias', path: 'parent/NAME.txt', content: 'bad' }))
    assert.equal(await readFile(path.join(f.root, 'parent', 'name.txt'), 'utf8'), 'original')
  } finally { await f.clean() }
})

test('worker ignores Node and dynamic-library injection variables', macOnly, async () => {
  const f = await fixture()
  const oldOptions = process.env.NODE_OPTIONS
  const oldNodePath = process.env.NODE_PATH
  const oldLibrary = process.env.DYLD_INSERT_LIBRARIES
  try {
    const marker = path.join(f.state, 'injected')
    const injected = path.join(f.state, 'injected.cjs')
    await writeFile(injected, `require('node:fs').writeFileSync(${JSON.stringify(marker)}, 'bad')`)
    await writeFile(path.join(f.root, 'readable.txt'), 'ordinary project content')
    process.env.NODE_OPTIONS = `--require=${injected}`
    process.env.NODE_PATH = f.state
    process.env.DYLD_INSERT_LIBRARIES = path.join(f.state, 'missing.dylib')
    const gate = await f.gate(160)
    assert.equal((await gate.readFile({ path: 'readable.txt' })).content, 'ordinary project content')
    await assert.rejects(readFile(marker, 'utf8'))
  } finally {
    if (oldOptions === undefined) delete process.env.NODE_OPTIONS; else process.env.NODE_OPTIONS = oldOptions
    if (oldNodePath === undefined) delete process.env.NODE_PATH; else process.env.NODE_PATH = oldNodePath
    if (oldLibrary === undefined) delete process.env.DYLD_INSERT_LIBRARIES; else process.env.DYLD_INSERT_LIBRARIES = oldLibrary
    await f.clean()
  }
})

test('authorization loss and sealing reject new edits and drain the pending operation', macOnly, async () => {
  const f = await fixture()
  try {
    let authorized = true
    const gate = await f.gate(17, () => authorized)
    authorized = false
    await assert.rejects(gate.createFile({ operationId: 'unowned', path: 'a.txt', content: 'bad' }))
    authorized = true
    let settled = false
    const pending = gate.createFile({ operationId: 'queued', path: 'a.txt', content: 'a'.repeat(1024 * 1024) })
    const recorded = pending.then(() => { settled = true }, () => { settled = true })
    await gate.sealAndDrain()
    assert.equal(settled, true)
    await recorded
    await assert.rejects(gate.createFile({ operationId: 'after', path: 'b.txt', content: 'bad' }))
    assert.equal(gate.accepting, false)
  } finally { await f.clean() }
})

test('a timed-out helper is killed and the project queue stays held until it exits', macOnly, async t => {
  const f = await fixture()
  const originalSpawn = childProcess.spawn
  const originalKill = process.kill
  const fake = new EventEmitter()
  fake.pid = 987654321
  fake.stdout = new PassThrough()
  fake.stdin = new Writable({ write(_chunk, _encoding, callback) { started(); callback() } })
  const signals = []
  let groupAlive = true
  let sealed = false, secondDone = false, closed = false
  process.kill = (pid, signal) => {
    if (pid !== -fake.pid) return originalKill(pid, signal)
    if (!groupAlive) throw Object.assign(new Error('gone'), { code: 'ESRCH' })
    if (signal) signals.push(signal)
    if (signal === 'SIGTERM') {
      assert.equal(closed, true)
      assert.equal(sealed, false)
      assert.equal(secondDone, false)
      groupAlive = false
    }
    return true
  }
  let started
  const spawned = new Promise(resolve => { started = resolve })
  try {
    await writeFile(path.join(f.root, 'current.txt'), 'safe')
    const gate = await f.gate(170), other = await f.gate(171)
    t.mock.timers.enable({ apis: ['setTimeout'] })
    childProcess.spawn = () => { queueMicrotask(() => fake.emit('spawn')); return fake }
    syncBuiltinESMExports()
    const first = gate.createFile({ operationId: 'timeout', path: 'pending.txt', content: 'pending' })
      .then(value => ({ value }), error => ({ error }))
    await spawned
    const draining = gate.sealAndDrain().then(() => { sealed = true })
    const second = other.readFile({ path: 'current.txt' }).then(value => { secondDone = true; return value })
    t.mock.timers.tick(30_000)
    await yieldTurn()
    assert.deepEqual(signals, ['SIGKILL'])
    assert.equal(sealed, false)
    assert.equal(secondDone, false)
    childProcess.spawn = originalSpawn
    syncBuiltinESMExports()
    t.mock.timers.reset()
    fake.stdout.end()
    closed = true
    fake.emit('close', null, 'SIGKILL')
    assert.match((await first).error.message, /待确认/)
    assert.deepEqual(signals, ['SIGKILL', 'SIGTERM'])
    await draining
    assert.equal(sealed, true)
    assert.equal(gate.accepting, false)
    assert.equal((await second).content, 'safe')
    const record = JSON.parse(await readFile(path.join(f.state, 'file-operations', '170', 'timeout.json'), 'utf8'))
    assert.equal(record.status, 'pending')
    assert.equal(record.processGroupId, fake.pid)
  } finally {
    childProcess.spawn = originalSpawn
    process.kill = originalKill
    syncBuiltinESMExports()
    t.mock.timers.reset()
    await f.clean()
  }
})

test('a timed-out read seals its attempt after the helper exits', macOnly, async t => {
  const f = await fixture()
  const originalSpawn = childProcess.spawn
  const originalKill = process.kill
  const fake = new EventEmitter()
  fake.pid = 987654322
  fake.stdout = new PassThrough()
  fake.stdin = new Writable({ write(_chunk, _encoding, callback) { started(); callback() } })
  let started
  const spawned = new Promise(resolve => { started = resolve })
  let groupAlive = true
  process.kill = (pid, signal) => {
    if (pid !== -fake.pid) return originalKill(pid, signal)
    if (!groupAlive) throw Object.assign(new Error('gone'), { code: 'ESRCH' })
    if (signal === 'SIGKILL') queueMicrotask(() => { groupAlive = false; fake.stdout.end(); fake.emit('close', null, signal) })
    return true
  }
  try {
    const gate = await f.gate(172)
    t.mock.timers.enable({ apis: ['setTimeout'] })
    childProcess.spawn = () => { queueMicrotask(() => fake.emit('spawn')); return fake }
    syncBuiltinESMExports()
    const read = gate.readFile({ path: 'a.txt' }).then(value => ({ value }), error => ({ error }))
    await spawned
    t.mock.timers.tick(30_000)
    assert.match((await read).error.message, /待确认/)
    assert.equal(gate.accepting, false)
    await assert.rejects(gate.readFile({ path: 'a.txt' }), /停止|失效/)
    await gate.sealAndDrain()
  } finally {
    childProcess.spawn = originalSpawn
    process.kill = originalKill
    syncBuiltinESMExports()
    t.mock.timers.reset()
    await f.clean()
  }
})

test('non-regular files and changed project roots are rejected', { skip: process.platform !== 'darwin', timeout: 5000 }, async () => {
  const f = await fixture()
  const moved = `${f.root}-moved`
  try {
    await promisify(execFile)('mkfifo', [path.join(f.root, 'pipe')])
    const gate = await f.gate(18)
    await assert.rejects(gate.readFile({ path: 'pipe' }), /普通文件/)
    await rename(f.root, moved)
    await mkdir(f.root)
    await assert.rejects(gate.createFile({ operationId: 'new-root', path: 'a.txt', content: 'bad' }), /目录已变化|目录无效/)
    await gate.sealAndDrain()
  } finally { await f.clean(); await rm(moved, { recursive: true, force: true }) }
})

test('failed temporary cleanup records its path and seals only the affected attempt', macOnly, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'restricted.txt')
    await writeFile(filename, 'before')
    await promisify(execFile)('/bin/chmod', ['+a', 'everyone deny delete', filename])
    const gate = await f.gate(173), other = await f.gate(174)
    const read = await gate.readFile({ path: 'restricted.txt' })
    await assert.rejects(gate.replaceFile({ operationId: 'cleanup-denied', path: read.path,
      version: read.version, content: 'after' }), /待确认/)
    assert.equal(await readFile(filename, 'utf8'), 'before')
    assert.equal(gate.accepting, false)
    const record = JSON.parse(await readFile(path.join(f.state, 'file-operations', '173', 'cleanup-denied.json'), 'utf8'))
    assert.equal(record.status, 'unknown')
    assert.equal(record.cleanupPaths.length, 1)
    assert.match(record.cleanupPaths[0], /^\.linear-lite-edit-[a-f0-9-]+\.tmp$/)
    assert.equal(await readFile(path.join(f.root, record.cleanupPaths[0]), 'utf8'), 'after')
    assert.deepEqual(gate.operationFacts().at(-1).cleanupPaths, record.cleanupPaths)
    assert.equal((await other.readFile({ path: 'restricted.txt' })).content, 'before')
  } finally {
    await promisify(execFile)('/bin/chmod', ['-RN', f.root])
    await f.clean()
  }
})

test('durable operation facts carry attempt and operation attribution without file contents', macOnly, async () => {
  const f = await fixture()
  try {
    const gate = await f.gate(19)
    await gate.createFile({ operationId: 'persisted-op', path: 'evidence.txt', content: 'private project content' })
    const facts = gate.operationFacts()
    assert.equal(facts.length, 1)
    assert.equal(facts[0].attemptId, 19)
    assert.equal(facts[0].agentInstanceId, 'agent-19')
    assert.equal(facts[0].operationId, 'persisted-op')
    assert.deepEqual(facts[0].changedPaths, ['evidence.txt'])
    const files = await readdir(path.join(f.state, 'file-operations', '19'))
    assert.equal(files.length, 1)
    const stored = await readFile(path.join(f.state, 'file-operations', '19', files[0]), 'utf8')
    assert.equal(JSON.parse(stored).status, 'completed')
    assert.equal(stored.includes('private project content'), false)
    assert.equal(stored.includes('fixture-claim-secret'), false)
  } finally { await f.clean() }
})

test('rejects a forged rename trace with a changed content version', macOnly, async () => {
  const f = await fixture()
  const originalSpawn = childProcess.spawn
  const originalKill = process.kill
  const fake = new EventEmitter()
  fake.pid = 987654323
  fake.stdout = new PassThrough()
  fake.stdin = new Writable({ write(_chunk, _encoding, callback) { callback() } })
  fake.stdin.on('finish', () => {
    fake.stdout.end(JSON.stringify({ ok: true, result: { changedPaths: ['a.txt', 'b.txt'],
      artifactObservations: [{ path: 'b.txt', state: 'PRESENT', contentVersion: `sha256:${'a'.repeat(64)}` }],
      previousPath: 'a.txt', previousContentVersion: `sha256:${'b'.repeat(64)}` } }))
    fake.emit('close', 0)
  })
  try {
    const gate = await f.gate(189)
    childProcess.spawn = () => { queueMicrotask(() => fake.emit('spawn')); return fake }
    process.kill = (pid, signal) => {
      if (pid !== -fake.pid) return originalKill(pid, signal)
      throw Object.assign(new Error('gone'), { code: 'ESRCH' })
    }
    syncBuiltinESMExports()
    await assert.rejects(gate.renameFile({ operationId: 'forged', from: 'a.txt', to: 'b.txt', version: 'v' }), /结果待确认/)
    assert.equal(gate.accepting, false)
    assert.deepEqual(gate.operationFacts(), [])
  } finally {
    childProcess.spawn = originalSpawn
    process.kill = originalKill
    syncBuiltinESMExports()
    await f.clean()
  }
})

test('file traces retain the committed bytes and exclude no-op or non-file operations', macOnly, async () => {
  const f = await fixture()
  const versionOf = value => `sha256:${createHash('sha256').update(value).digest('hex')}`
  try {
    const gate = await f.gate(190)
    const dir = await gate.makeDirectory({ operationId: 'mkdir', path: 'work' })
    assert.deepEqual(dir.artifactObservations, [])
    const created = await gate.createFile({ operationId: 'create', path: 'work/a.txt', content: 'original' })
    const first = { path: 'work/a.txt', state: 'PRESENT', contentVersion: versionOf('original') }
    assert.deepEqual(created.artifactObservations, [first])
    assert.equal(created.previousContentVersion, null)
    assert.match(created.traceOperationId, /^trace-[a-f0-9-]+$/)
    const persisted = JSON.parse(await readFile(path.join(f.state, 'file-operations', '190', 'create.json'), 'utf8'))
    assert.deepEqual(persisted.artifactObservations, [first])
    assert.equal(persisted.traceOperationId, created.traceOperationId)
    await writeFile(path.join(f.root, 'work/a.txt'), 'external')
    assert.deepEqual(created.artifactObservations, [first])
    const read = await gate.readFile({ path: 'work/a.txt' })
    const patched = await gate.applyPatch({ operationId: 'patch', path: read.path, version: read.version,
      oldText: 'external', newText: 'patched' })
    assert.deepEqual(patched.artifactObservations, [{ path: 'work/a.txt', state: 'PRESENT', contentVersion: versionOf('patched') }])
    assert.equal(patched.previousContentVersion, versionOf('external'))
    const current = await gate.readFile({ path: 'work/a.txt' })
    const same = await gate.replaceFile({ operationId: 'same', path: current.path, version: current.version, content: 'patched' })
    assert.deepEqual(same.changedPaths, [])
    assert.deepEqual(same.artifactObservations, [])
    assert.equal(same.previousContentVersion, null)
    const replaced = await gate.replaceFile({ operationId: 'replace', path: current.path, version: current.version, content: 'new' })
    assert.deepEqual(replaced.artifactObservations, [{ path: 'work/a.txt', state: 'PRESENT', contentVersion: versionOf('new') }])
    assert.equal(replaced.previousContentVersion, versionOf('patched'))
    const last = await gate.readFile({ path: 'work/a.txt' })
    const moved = await gate.renameFile({ operationId: 'rename', from: last.path, to: 'work/b.txt', version: last.version })
    assert.deepEqual(moved.artifactObservations, [{ path: 'work/b.txt', state: 'PRESENT', contentVersion: versionOf('new') }])
    assert.equal(moved.previousContentVersion, versionOf('new'))
    assert.equal(moved.previousPath, 'work/a.txt')
    const movedFile = await gate.readFile({ path: 'work/b.txt' })
    const removed = await gate.removeFile({ operationId: 'remove', path: movedFile.path, version: movedFile.version })
    assert.deepEqual(removed.artifactObservations, [])
  } finally { await f.clean() }
})
