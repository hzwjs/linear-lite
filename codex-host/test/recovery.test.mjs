import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { RecoveryJournal, recoverAttempts, recoverLocalAttempt } from '../src/recovery.mjs'
import { createWriteGate } from '../src/executor.mjs'
import { createLocalAttemptProof } from '../src/recovery-proof.mjs'
import { createBatchRun, readBatchRun } from '../src/batch-journal.mjs'
import { ProcessGroupJournal } from '../src/process-groups.mjs'

function record(processId) {
  return { projectId: 1, bindingId: 2, batchId: 'batch', deviceId: 'device', projectDirectory: '/project',
    hostInstanceId: 'host-123', runEpoch: 1, agentInstanceId: 'agent-123', taskId: 3, inputRevision: 1,
    attemptId: 4, claimOperationId: 'claim-1', processId, stopConfirmed: false, claimOutcome: null }
}

test('journal records only execution association and rejects credentials', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-journal-'))
  try {
    const journal = new RecoveryJournal(dir)
    await assert.rejects(journal.save({ ...record(process.pid), claimToken: 'fixture-secret' }))
    await journal.save(record(process.pid))
    const files = await readdir(path.join(dir, 'attempts', 'batch', '1'))
    assert.equal(files.length, 1)
    const raw = await readFile(path.join(dir, 'attempts', 'batch', '1', files[0]), 'utf8')
    assert.equal(raw.includes('fixture-secret'), false)
    assert.deepEqual(await journal.list('host-123', 'batch', 1), [record(process.pid)])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test('recovery requires previous host exit and confirms stop before cleanup', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-recover-'))
  try {
    const journal = new RecoveryJournal(dir)
    const alive = record(process.pid)
    await journal.save(alive)
    const routes = []
    const client = { async recover(route, body) {
      assert.equal(body.batchToken, 'fixture-batch')
      routes.push(route); return { result: route === 'read' ? { id: 4, taskId: 3, bindingId: 2, batchId: 'batch', hostInstanceId: 'host-123', agentInstanceId: 'agent-123', inputRevision: 1, stopConfirmedAt: null } : { accepted: true } }
    } }
    const vault = { async get(key) { return key.startsWith('batch:') ? 'fixture-batch' : 'fixture-claim' }, async remove() { routes.push('vault-remove') } }
    await assert.rejects(recoverAttempts({ records: [alive], identity: { deviceId: 'device' }, client, vault, journal }))
    assert.deepEqual(routes, [])

    const old = spawn(process.execPath, ['-e', ''])
    await new Promise(resolve => old.once('exit', resolve))
    const stopped = record(old.pid)
    await journal.save(stopped)
    const [confirmed] = await recoverAttempts({ records: [stopped], identity: { deviceId: 'device' }, client, vault, journal })
    assert.deepEqual(routes, ['read', 'request-stop', 'confirm-stop'])
    assert.equal(confirmed.stopConfirmed, true)
    assert.deepEqual(await journal.list('host-123', 'batch', 1), [confirmed])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test('unknown claim outcome uses the batch credential and preserves the record on failure', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-unknown-'))
  try {
    const old = spawn(process.execPath, ['-e', ''])
    await new Promise(resolve => old.once('exit', resolve))
    const journal = new RecoveryJournal(dir)
    const pending = { ...record(old.pid), attemptId: null }
    await journal.save(pending)
    const vault = { async get(account) { assert.equal(account, 'batch:batch:1'); return 'batch-secret' } }
    await assert.rejects(recoverAttempts({ records: [pending], identity: { deviceId: 'device' },
      client: { async recover() { throw new Error('offline') } }, vault, journal }))
    assert.equal((await journal.list('host-123', 'batch', 1)).length, 1)
    let submitted
    const client = { async recover(route, body) { assert.equal(route, 'recover-claim'); submitted = body; return { result: { accepted: true, data: { claimOutcome: 'FENCED', attemptId: null } } } } }
    const [confirmed] = await recoverAttempts({ records: [pending], identity: { deviceId: 'device' }, client, vault, journal })
    assert.equal(submitted.batchToken, 'batch-secret')
    assert.equal(submitted.claimOperationId, 'claim-1')
    assert.equal(submitted.inputRevision, 1)
    assert.match(submitted.operationId, /^recover-claim-[a-f0-9]{64}$/)
    assert.equal(Object.hasOwn(submitted, 'claimToken'), false)
    assert.equal(confirmed.stopConfirmed, true)
    assert.equal(confirmed.claimOutcome, 'FENCED')
    assert.equal((await journal.list('host-123', 'batch', 1)).length, 1)
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test('a malformed claim fence response keeps its recovery record', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll145-malformed-'))
  try {
    const old = spawn(process.execPath, ['-e', '']); await new Promise(resolve => old.once('exit', resolve))
    const journal = new RecoveryJournal(dir)
    const pending = { ...record(old.pid), attemptId: null }
    await journal.save(pending)
    const vault = { async get() { return 'batch-secret' } }
    const client = { async recover() { return { result: { accepted: true, data: { claimOutcome: 'FENCED' } } } } }
    await assert.rejects(recoverAttempts({ records: [pending], identity: { deviceId: 'device' }, client, vault, journal }),
      /领取恢复停止确认遭拒/)
    assert.deepEqual(await journal.list('host-123', 'batch', 1), [pending])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

async function recoveryFixture(fn) {
  const dir = await realpath(await mkdtemp(path.join(tmpdir(), 'll144-recovery-')))
  const old = spawn(process.execPath, ['-e', ''], { detached: true })
  await new Promise(resolve => old.once('exit', resolve))
  const saved = record(old.pid), journal = new RecoveryJournal(dir), routes = []
  const client = { async recover(route) {
    routes.push(route)
    return { result: route === 'read' ? { id: 4, taskId: 3, bindingId: 2, batchId: 'batch',
      hostInstanceId: 'host-123', agentInstanceId: 'agent-123', inputRevision: 1, stopConfirmedAt: null } : { accepted: true } }
  } }
  const vault = { async get(key) { return key.startsWith('batch:') ? 'fixture-batch' : 'fixture-claim' } }
  await journal.save(saved)
  try { await fn({ dir, old, saved, journal, routes, client, vault }) }
  finally { await rm(dir, { recursive: true, force: true }) }
}

function recover(f, extra = {}) {
  return recoverAttempts({ records: [f.saved], identity: { deviceId: 'device' }, client: f.client,
    vault: f.vault, journal: f.journal, ...extra })
}

async function saveCommand(f, fact) {
  const dir = path.join(f.dir, 'command-operations', '4')
  await mkdir(dir, { recursive: true })
  await writeFile(path.join(dir, `${fact.operationId}.json`), JSON.stringify(fact))
}

function commandFact(overrides = {}) {
  return { attemptId: 4, agentInstanceId: 'agent-123', operationId: 'command-1', status: 'pending',
    processGroupId: null, startedAt: new Date().toISOString(), finishedAt: null, exitCode: null,
    timedOut: false, ...overrides }
}

test('unknown or unassigned command facts block stop confirmation, even with local attempt proof', async () => {
  await recoveryFixture(async f => {
    const identity = { deviceId: 'device', hostInstanceId: 'host-123' }
    const owner = { batchId: 'batch', runEpoch: 1, hostInstanceId: 'host-123', processId: process.pid }
    f.saved = { ...f.saved, processId: process.pid }
    const proof = createLocalAttemptProof(owner, f.saved, identity)
    await saveCommand(f, commandFact())
    await assert.rejects(recover(f, { identity, owner, attemptProof: proof }), /命令进程归属待确认/)
    await saveCommand(f, commandFact({ status: 'unknown', processGroupId: f.old.pid,
      finishedAt: new Date().toISOString() }))
    await assert.rejects(recover(f, { identity, owner, attemptProof: proof }), /命令进程归属待确认/)
    assert.deepEqual(f.routes, [])
  })
})

test('live command group blocks recovery without sending signals; stopped group permits confirmation', async () => {
  await recoveryFixture(async f => {
    const live = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
    await new Promise(resolve => live.once('spawn', resolve))
    try {
      await saveCommand(f, commandFact({ processGroupId: live.pid }))
      await assert.rejects(recover(f), /命令进程组未确认退出/)
      assert.equal(process.kill(live.pid, 0), true)
      assert.deepEqual(f.routes, [])
      process.kill(-live.pid, 'SIGTERM')
      await new Promise(resolve => live.once('exit', resolve))
      const [confirmed] = await recover(f)
      assert.equal(confirmed.stopConfirmed, true)
      assert.deepEqual(f.routes, ['read', 'request-stop', 'confirm-stop'])
    } finally {
      if (live.exitCode === null && live.signalCode === null) process.kill(-live.pid, 'SIGKILL')
    }
  })
})

test('foreign or malformed command facts block recovery', async () => {
  await recoveryFixture(async f => {
    for (const fact of [commandFact({ agentInstanceId: 'other' }), commandFact({ processGroupId: 1 }),
      commandFact({ status: 'completed', processGroupId: null, finishedAt: new Date().toISOString(), exitCode: 0 }),
      { ...commandFact(), claimToken: 'fixture-secret' }]) {
      await saveCommand(f, fact)
      await assert.rejects(recover(f), /命令进程记录归属无效/)
    }
    assert.deepEqual(f.routes, [])
  })
})

test('previous stop confirmation still checks command facts before cleanup', async () => {
  await recoveryFixture(async f => {
    f.saved = { ...f.saved, stopConfirmed: true, claimOutcome: 'CLAIMED' }
    await saveCommand(f, commandFact({ status: 'unknown', processGroupId: f.old.pid,
      finishedAt: new Date().toISOString() }))
    await assert.rejects(recover(f), /命令进程归属待确认/)
    await saveCommand(f, commandFact({ status: 'completed', processGroupId: f.old.pid,
      finishedAt: new Date().toISOString(), exitCode: 0 }))
    const editDirectory = path.join(f.dir, 'file-operations', '4')
    await mkdir(editDirectory, { recursive: true })
    const brokenEdit = path.join(editDirectory, 'broken.json')
    await writeFile(brokenEdit, '{')
    await assert.rejects(recover(f), /编辑进程记录无法读取/)
    await unlink(brokenEdit)
    const [confirmed] = await recover(f)
    assert.equal(confirmed.stopConfirmed, true)
    assert.deepEqual(f.routes, [])
  })
})

test('local cleanup checks existing app-server group after previous stop confirmation', async () => {
  const state = await realpath(await mkdtemp(path.join(tmpdir(), 'll144-confirmed-state-')))
  const project = await realpath(await mkdtemp(path.join(tmpdir(), 'll144-confirmed-project-')))
  const identity = { deviceId: 'device', hostInstanceId: 'host-123' }
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
  await new Promise(resolve => child.once('spawn', resolve))
  try {
    const config = { stateDirectory: state, identity, projectId: 1, projectDirectory: project, batchId: 'batch' }
    const run = await createBatchRun(config)
    await run.reserve(1); await run.prepared(); await run.accepted()
    const owner = await readBatchRun(config)
    const saved = { ...record(process.pid), projectDirectory: project, stopConfirmed: true, claimOutcome: 'CLAIMED' }
    const journal = new RecoveryJournal(state), groups = new ProcessGroupJournal(state)
    await journal.save(saved)
    await groups.save({ ...owner, agentInstanceId: saved.agentInstanceId, phase: 'running', processGroupId: child.pid })
    const proof = createLocalAttemptProof(owner, saved, identity), routes = []
    const client = { async recover(route) { routes.push(route); throw new Error('unexpected service call') } }
    const vault = { async removeIfPresent() {} }
    await assert.rejects(recoverLocalAttempt({ stateDirectory: state, identity, owner, record: saved, proof, client, vault }),
      /执行进程组未确认退出/)
    assert.equal((await journal.list(null, 'batch', 1)).length, 1)
    assert.deepEqual(routes, [])
    process.kill(-child.pid, 'SIGTERM')
    await new Promise(resolve => child.once('exit', resolve))
    const result = await recoverLocalAttempt({ stateDirectory: state, identity, owner, record: saved, proof, client, vault })
    assert.equal(result.cleaned, true)
    assert.deepEqual(routes, [])
  } finally {
    if (child.exitCode === null && child.signalCode === null) process.kill(-child.pid, 'SIGKILL')
    await rm(state, { recursive: true, force: true }); await rm(project, { recursive: true, force: true })
  }
})

test('current editing facts can be recovered after create, rename and rejected edit', { skip: process.platform !== 'darwin' }, async () => {
  await recoveryFixture(async f => {
    const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll144-edit-root-')))
    try {
      const gate = await createWriteGate({ projectDirectory: root, stateDirectory: f.dir, attemptId: 4,
        agentInstanceId: 'agent-123', isAuthorized: () => true, knownCredentials: [] })
      await gate.createFile({ path: 'first.txt', content: 'fixture', operationId: 'create' })
      await assert.rejects(gate.createFile({ path: 'first.txt', content: 'fixture', operationId: 'rejected' }))
      const observed = await gate.readFile({ path: 'first.txt' })
      await gate.renameFile({ from: 'first.txt', to: 'renamed.txt', version: observed.version, operationId: 'rename' })
      await gate.sealAndDrain()
      const [confirmed] = await recover(f)
      assert.equal(confirmed.stopConfirmed, true)
      assert.deepEqual(f.routes, ['read', 'request-stop', 'confirm-stop'])
    } finally { await rm(root, { recursive: true, force: true }) }
  })
})
