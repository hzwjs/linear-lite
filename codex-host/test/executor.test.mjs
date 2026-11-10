import test from 'node:test'
import assert from 'node:assert/strict'
import { link, mkdtemp, mkdir, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createWriteGate } from '../src/executor.mjs'

test('host writes only held paths and rejects symlink escape', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-write-')))
  const outside = await mkdtemp(path.join(tmpdir(), 'll122-outside-'))
  try {
    await mkdir(path.join(root, 'src'))
    await mkdir(path.join(root, 'docs'))
    await symlink(outside, path.join(root, 'docs', 'link'))
    const gate = await createWriteGate({ projectDirectory: root, stateDirectory: outside, writeScopes: ['src/allowed.txt', 'docs/', '.git/'], isAuthorized: () => true, knownCredentials: ['fixture-claim'] })
    await gate.writeFile('src/allowed.txt', 'new content')
    assert.equal(await readFile(path.join(root, 'src', 'allowed.txt'), 'utf8'), 'new content')
    await assert.rejects(gate.writeFile('src/other.txt', 'no'))
    await assert.rejects(gate.writeFile('../outside.txt', 'no'))
    await assert.rejects(gate.writeFile('docs/link/outside.txt', 'no'))
    await assert.rejects(gate.writeFile('.git/config', 'no'))
    await assert.rejects(gate.writeFile('src/allowed.txt', 'fixture-claim'))
    assert.deepEqual(gate.changedPaths(), ['src/allowed.txt'])
    await gate.removeFile('src/allowed.txt')
    await assert.rejects(readFile(path.join(root, 'src', 'allowed.txt'), 'utf8'))
    await assert.rejects(gate.removeFile('src/other.txt'))
  } finally { await rm(root, { recursive: true, force: true }); await rm(outside, { recursive: true, force: true }) }
})

test('hard-linked files cannot mutate paths outside the held scope', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-hardlink-')))
  const state = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-state-')))
  try {
    await writeFile(path.join(root, 'outside.txt'), 'original')
    await link(path.join(root, 'outside.txt'), path.join(root, 'allowed.txt'))
    const gate = await createWriteGate({ projectDirectory: root, stateDirectory: state,
      writeScopes: ['allowed.txt'], isAuthorized: () => true, knownCredentials: [] })
    await assert.rejects(gate.writeFile('allowed.txt', 'changed'))
    assert.equal(await readFile(path.join(root, 'outside.txt'), 'utf8'), 'original')
    assert.deepEqual(gate.changedPaths(), [])
  } finally { await rm(root, { recursive: true, force: true }); await rm(state, { recursive: true, force: true }) }
})

test('case aliases cannot mutate another path, including concurrent creation', async t => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-case-')))
  const state = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-state-')))
  try {
    await mkdir(path.join(root, 'parent'))
    await writeFile(path.join(root, 'parent', 'name.txt'), 'original')
    try { await readFile(path.join(root, 'PARENT', 'NAME.txt')) } catch (error) {
      if (error.code === 'ENOENT') { t.skip('volume is case sensitive'); return }
      throw error
    }
    const gate = scopes => createWriteGate({ projectDirectory: root, stateDirectory: state,
      writeScopes: scopes, isAuthorized: () => true, knownCredentials: [] })
    const upper = await gate(['parent/NAME.txt', 'PARENT/name.txt'])
    await assert.rejects(upper.writeFile('parent/NAME.txt', 'bad'))
    await assert.rejects(upper.writeFile('PARENT/name.txt', 'bad'))
    assert.equal(await readFile(path.join(root, 'parent', 'name.txt'), 'utf8'), 'original')
    const a = await gate(['parent/new.txt']), b = await gate(['parent/NEW.txt'])
    const results = await Promise.allSettled([a.writeFile('parent/new.txt', 'lower'), b.writeFile('parent/NEW.txt', 'upper')])
    assert.equal(results.filter(x => x.status === 'fulfilled').length, 1)
    const content = await readFile(path.join(root, 'parent', 'new.txt'), 'utf8')
    assert.equal(content, results[0].status === 'fulfilled' ? 'lower' : 'upper')
  } finally { await rm(root, { recursive: true, force: true }); await rm(state, { recursive: true, force: true }) }
})

test('sealing or lease loss rejects writes and waits for queued work', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-seal-')))
  try {
    let allowed = true
    const gate = await createWriteGate({ projectDirectory: root, stateDirectory: root, writeScopes: ['a.txt'], isAuthorized: () => allowed, knownCredentials: [] })
    allowed = false
    await assert.rejects(gate.writeFile('a.txt', 'no'))
    allowed = true
    const pending = gate.writeFile('a.txt', 'queued')
    await gate.sealAndDrain()
    await assert.rejects(pending)
    await assert.rejects(gate.writeFile('a.txt', 'after stop'))
    assert.equal(gate.accepting, false)
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('named pipes are rejected without leaving an in-flight writer that blocks stop', { skip: process.platform === 'win32', timeout: 2000 }, async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-fifo-')))
  const state = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-fifo-state-')))
  try {
    await promisify(execFile)('mkfifo', [path.join(root, 'pipe')])
    const gate = await createWriteGate({ projectDirectory: root, stateDirectory: state,
      writeScopes: ['pipe'], isAuthorized: () => true, knownCredentials: [] })
    await assert.rejects(gate.writeFile('pipe', 'data'), /普通文件/)
    await gate.sealAndDrain()
    assert.equal(gate.accepting, false)
  } finally { await rm(root, { recursive: true, force: true }); await rm(state, { recursive: true, force: true }) }
})
