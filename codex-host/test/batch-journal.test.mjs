import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createBatchRun, readBatchRun, listBatchRuns, recoverBatchRun } from '../src/batch-journal.mjs'
import { createLocalRecoveryProof } from '../src/recovery-proof.mjs'
import { RecoveryJournal, recoverBatch } from '../src/recovery.mjs'
import { ProcessGroupJournal } from '../src/process-groups.mjs'

async function fixture() {
  const base = await realpath(await mkdtemp(path.join(os.tmpdir(), 'll145-batch-')))
  const project = path.join(base, 'project'), stateDirectory = path.join(base, 'state')
  await mkdir(project); await mkdir(stateDirectory)
  return { base, project, stateDirectory, identity: { deviceId: 'device', hostInstanceId: 'host' } }
}

const config = (f, batchId) => ({ stateDirectory: f.stateDirectory, identity: f.identity,
  projectId: 1, projectDirectory: f.project, batchId })

test('same physical directory has distinct batch and epoch records', async () => {
  const f = await fixture()
  try {
    const a = await createBatchRun(config(f, 'batch-a'))
    const b = await createBatchRun(config(f, 'batch-b'))
    await Promise.all([a.reserve(3), b.reserve(7)])
    await Promise.all([a.prepared(), b.prepared()])
    assert.deepEqual((await listBatchRuns(f)).map(x => [x.batchId, x.runEpoch]).sort(), [['batch-a', 3], ['batch-b', 7]])
    await assert.rejects(createBatchRun(config(f, 'batch-a')), { code: 'BATCH_RECOVERY_REQUIRED' })
    await a.release()
    assert.equal(await readBatchRun(config(f, 'batch-a')), null)
    assert.equal((await readBatchRun(config(f, 'batch-b'))).runEpoch, 7)
    await b.release()
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('unknown accept retains a durable credential reference and retries finish fence', async () => {
  const f = await fixture(), values = new Map()
  const vault = { async get(k) { if (!values.has(k)) throw new Error('missing'); return values.get(k) },
    async removeIfPresent(k) { return values.delete(k) } }
  try {
    const run = await createBatchRun(config(f, 'batch'))
    await run.reserve(2)
    values.set('batch-recovery:batch:2', 'fixture-recovery-token')
    await run.prepared()
    const owner = await readBatchRun(config(f, 'batch'))
    const proof = createLocalRecoveryProof(owner, f.identity)
    const raw = await readFile(path.join(f.stateDirectory, 'runs', 'batch', '2.json'), 'utf8')
    assert.equal(raw.includes('fixture-recovery-token'), false)
    let finishCalls = 0
    const client = { async recover(route, request) {
      assert.equal(route, 'finish-accept'); assert.equal(request.recoveryToken, 'fixture-recovery-token')
      finishCalls++
      if (finishCalls === 1) throw new Error('offline')
      return { result: { batchId: 'batch', lifecycle: 'ended' } }
    } }
    await assert.rejects(recoverBatch({ ...config(f, 'batch'), client, vault, localProof: proof }), /offline/)
    assert.equal((await readBatchRun(config(f, 'batch'))).phase, 'prepared')
    assert.equal(values.size, 1)
    assert.equal((await recoverBatch({ ...config(f, 'batch'), client, vault, localProof: proof })).recovered, true)
    assert.equal(await readBatchRun(config(f, 'batch')), null)
    assert.equal(values.size, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('pending preparation has no accept and can be discarded after local quiescence', async () => {
  const f = await fixture()
  try {
    await createBatchRun(config(f, 'pending'))
    const owner = await readBatchRun(config(f, 'pending'))
    const proof = createLocalRecoveryProof(owner, f.identity)
    const client = { async recover() { throw new Error('unexpected remote action') } }
    const vault = { async removeIfPresent() { throw new Error('unexpected credential') } }
    assert.equal((await recoverBatch({ ...config(f, 'pending'), client, vault, localProof: proof })).recovered, true)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('recovery for one batch ignores corrupt records in another same-root batch', async () => {
  const f = await fixture(), values = new Map()
  const vault = { async get(k) { return values.get(k) }, async removeIfPresent(k) { return values.delete(k) } }
  try {
    const a = await createBatchRun(config(f, 'a')), b = await createBatchRun(config(f, 'b'))
    await a.reserve(1); await a.prepared(); await a.accepted()
    await b.reserve(1); await b.prepared(); await b.accepted()
    values.set('batch:a:1', 'token-a'); values.set('batch-recovery:a:1', 'recovery-a')
    const ownerA = await readBatchRun(config(f, 'a'))
    const proof = createLocalRecoveryProof(ownerA, f.identity)
    const attempts = new RecoveryJournal(f.stateDirectory), groups = new ProcessGroupJournal(f.stateDirectory)
    const record = { projectId: 1, bindingId: 2, batchId: 'b', deviceId: 'device', projectDirectory: f.project,
      hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent', taskId: 11, inputRevision: 1,
      attemptId: 12, claimOperationId: 'claim', processId: process.pid, stopConfirmed: false, claimOutcome: null }
    await attempts.save(record)
    await groups.save({ projectId: 1, batchId: 'b', deviceId: 'device', projectDirectory: f.project,
      hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent', processId: process.pid,
      phase: 'starting', processGroupId: null })
    const filename = path.join(f.stateDirectory, 'attempts', 'b', '1', 'host-agent.json')
    await writeFile(filename, 'broken')
    const routes = []
    const client = { async recover(route) { routes.push(route); return { result: { batchId: 'a', lifecycle: 'ended' } } } }
    assert.equal((await recoverBatch({ ...config(f, 'a'), client, vault, localProof: proof })).recovered, true)
    assert.deepEqual(routes, ['finish'])
    assert.ok(await readBatchRun(config(f, 'b')))
    assert.equal((await readdir(path.dirname(filename))).includes(path.basename(filename)), true)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('restart cannot treat an existing old process or unconfirmed result as stopped', async () => {
  const f = await fixture()
  try {
    await createBatchRun(config(f, 'batch'))
    await assert.rejects(recoverBatchRun(config(f, 'batch'), async () => {}), /旧宿主进程保持活动/)
    const child = spawn(process.execPath, ['-e', '']); await once(child, 'exit')
    const file = path.join(f.stateDirectory, 'runs', 'batch', 'pending.json')
    const owner = JSON.parse(await readFile(file, 'utf8')); owner.processId = child.pid
    await writeFile(file, JSON.stringify(owner))
    await assert.rejects(recoverBatchRun(config(f, 'batch'), async () => { throw new Error('server offline') }), /offline/)
    assert.equal((await readBatchRun(config(f, 'batch'))).processId, child.pid)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a live edit helper blocks only its attempt and its batch recovery', async () => {
  const f = await fixture(), values = new Map()
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
  const vault = { async get(key) { return values.get(key) }, async remove(key) { values.delete(key) },
    async removeIfPresent(key) { return values.delete(key) } }
  try {
    const a = await createBatchRun(config(f, 'a')), b = await createBatchRun(config(f, 'b'))
    await a.reserve(1); await a.prepared(); await a.accepted()
    await b.reserve(1); await b.prepared(); await b.accepted()
    const ownerA = await readBatchRun(config(f, 'a')), ownerB = await readBatchRun(config(f, 'b'))
    const proofA = createLocalRecoveryProof(ownerA, f.identity), proofB = createLocalRecoveryProof(ownerB, f.identity)
    for (const id of ['a', 'b']) {
      values.set(`batch:${id}:1`, `token-${id}`)
      values.set(`batch-recovery:${id}:1`, `recovery-${id}`)
    }
    values.set('attempt:host:agent-b', 'claim-token')
    const attempt = { projectId: 1, bindingId: 2, batchId: 'b', deviceId: 'device', projectDirectory: f.project,
      hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent-b', taskId: 11, inputRevision: 1,
      attemptId: 12, claimOperationId: 'claim', processId: process.pid, stopConfirmed: false, claimOutcome: null }
    await new RecoveryJournal(f.stateDirectory).save(attempt)
    await new ProcessGroupJournal(f.stateDirectory).save({ projectId: 1, batchId: 'b', deviceId: 'device',
      projectDirectory: f.project, hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent-b',
      processId: process.pid, phase: 'idle', processGroupId: null })
    const operationDirectory = path.join(f.stateDirectory, 'file-operations', '12')
    await mkdir(operationDirectory, { recursive: true })
    const operationFile = path.join(operationDirectory, 'edit.json')
    await writeFile(operationFile, JSON.stringify({ attemptId: 12, agentInstanceId: 'agent-b', operationId: 'edit',
      action: 'replace', status: 'pending', processGroupId: child.pid, changedPaths: [], artifactObservations: [],
      previousContentVersion: null, traceOperationId: 'trace-edit', occurredAt: new Date().toISOString() }))
    const calls = []
    const client = { async recover(route, body) {
      calls.push([route, body.batchToken])
      return { result: route === 'finish' ? { batchId: body.batchToken === 'token-a' ? 'a' : 'b', lifecycle: 'ended' } : route === 'read' ? { id: 12, taskId: 11, bindingId: 2, batchId: 'b', hostInstanceId: 'host', agentInstanceId: 'agent-b', inputRevision: 1, stopConfirmedAt: null } : { accepted: true } }
    } }
    await assert.rejects(recoverBatch({ ...config(f, 'b'), client, vault, localProof: proofB }), /部分执行尝试等待恢复/)
    assert.deepEqual(calls, [])
    assert.equal((await recoverBatch({ ...config(f, 'a'), client, vault, localProof: proofA })).recovered, true)
    assert.deepEqual(calls, [['finish', 'token-a']])
    process.kill(-child.pid, 'SIGKILL')
    await once(child, 'exit')
    assert.equal((await recoverBatch({ ...config(f, 'b'), client, vault, localProof: proofB })).recovered, true)
    assert.equal((await readFile(operationFile, 'utf8')).includes('pending'), true)
    assert.deepEqual(calls.map(call => call[0]), ['finish', 'read', 'request-stop', 'confirm-stop', 'finish'])
  } finally {
    try { process.kill(-child.pid, 'SIGKILL') } catch { }
    await rm(f.base, { recursive: true, force: true })
  }
})

test('contradictory host ownership in one epoch prevents recovery confirmation', async () => {
  const f = await fixture()
  try {
    const run = await createBatchRun(config(f, 'batch'))
    await run.reserve(1); await run.prepared(); await run.accepted()
    const owner = await readBatchRun(config(f, 'batch'))
    const proof = createLocalRecoveryProof(owner, f.identity)
    await new ProcessGroupJournal(f.stateDirectory).save({ projectId: 1, batchId: 'batch', deviceId: 'device',
      projectDirectory: f.project, hostInstanceId: 'other-host', runEpoch: 1, agentInstanceId: 'agent',
      processId: process.pid, phase: 'idle', processGroupId: null })
    let confirmed = false
    await assert.rejects(recoverBatch({ ...config(f, 'batch'), localProof: proof,
      vault: {}, client: { async recover() { confirmed = true } } }), /关联不完整/)
    assert.equal(confirmed, false)
    assert.ok(await readBatchRun(config(f, 'batch')))
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('confirmed attempt cleanup can resume after the group record was removed', async () => {
  const f = await fixture(), values = new Map(); let failCleanup = true; const calls = []
  const vault = { async get(k) { return values.get(k) }, async removeIfPresent(k) {
    if (k.startsWith('attempt:') && failCleanup) { failCleanup = false; throw new Error('keychain unavailable') }
    return values.delete(k)
  } }
  try {
    const run = await createBatchRun(config(f, 'batch'))
    await run.reserve(1); await run.prepared(); await run.accepted()
    const owner = await readBatchRun(config(f, 'batch'))
    const record = { projectId: 1, bindingId: 2, batchId: 'batch', deviceId: 'device', projectDirectory: f.project,
      hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent', taskId: 11, inputRevision: 1,
      attemptId: 12, claimOperationId: 'claim', processId: process.pid, stopConfirmed: false, claimOutcome: null }
    await new RecoveryJournal(f.stateDirectory).save(record)
    await new ProcessGroupJournal(f.stateDirectory).save({ projectId: 1, batchId: 'batch', deviceId: 'device',
      projectDirectory: f.project, hostInstanceId: 'host', runEpoch: 1, agentInstanceId: 'agent',
      processId: process.pid, phase: 'idle', processGroupId: null })
    values.set('batch:batch:1', 'batch-token'); values.set('attempt:host:agent', 'claim-token')
    const client = { async recover(route) { calls.push(route); return { result: route === 'read' ? { id: 12, taskId: 11, bindingId: 2, batchId: 'batch', hostInstanceId: 'host', agentInstanceId: 'agent', inputRevision: 1, stopConfirmedAt: null } : { accepted: true } } } }
    const { recoverLocalAttempt } = await import('../src/recovery.mjs')
    const { createLocalAttemptProof } = await import('../src/recovery-proof.mjs')
    const first = await recoverLocalAttempt({ ...config(f, 'batch'), owner, record, client, vault,
      proof: createLocalAttemptProof(owner, record, f.identity) })
    assert.equal(first.cleaned, false)
    assert.equal(first.record.stopConfirmed, true)
    assert.deepEqual(await new ProcessGroupJournal(f.stateDirectory).list(null, 'batch', 1), [])
    const second = await recoverLocalAttempt({ ...config(f, 'batch'), owner, record: first.record, client, vault,
      proof: createLocalAttemptProof(owner, first.record, f.identity) })
    assert.equal(second.cleaned, true)
    assert.deepEqual(calls, ['read', 'request-stop', 'confirm-stop'])
    assert.deepEqual(await new RecoveryJournal(f.stateDirectory).list(null, 'batch', 1), [])
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('restart recovers a stopped sibling despite another attempt with an unknown process group', async () => {
  const f = await fixture(), values = new Map(), calls = []
  const vault = { async get(k) { return values.get(k) }, async removeIfPresent(k) { return values.delete(k) } }
  try {
    const run = await createBatchRun(config(f, 'batch'))
    await run.reserve(1); await run.prepared(); await run.accepted()
    const child = spawn(process.execPath, ['-e', '']); await once(child, 'exit')
    const filename = path.join(f.stateDirectory, 'runs', 'batch', '1.json')
    const owner = JSON.parse(await readFile(filename, 'utf8')); owner.processId = child.pid
    await writeFile(filename, JSON.stringify(owner))
    values.set('batch:batch:1', 'batch-token'); values.set('attempt:host:agent-b', 'claim-b')
    const journal = new RecoveryJournal(f.stateDirectory), groups = new ProcessGroupJournal(f.stateDirectory)
    for (const [agentInstanceId, taskId, attemptId, phase] of [['agent-a', 10, 11, 'starting'], ['agent-b', 20, 21, 'idle']]) {
      await journal.save({ projectId: 1, bindingId: 2, batchId: 'batch', deviceId: 'device', projectDirectory: f.project,
        hostInstanceId: 'host', runEpoch: 1, agentInstanceId, taskId, inputRevision: 1, attemptId,
        claimOperationId: `claim-${taskId}`, processId: child.pid, stopConfirmed: false, claimOutcome: null })
      await groups.save({ projectId: 1, batchId: 'batch', deviceId: 'device', projectDirectory: f.project,
        hostInstanceId: 'host', runEpoch: 1, agentInstanceId, processId: child.pid, phase, processGroupId: null })
    }
    const client = { async recover(route, body) {
      calls.push([route, body.taskId])
      return { result: route === 'read' ? { id: 21, taskId: 20, bindingId: 2, batchId: 'batch', hostInstanceId: 'host', agentInstanceId: 'agent-b', inputRevision: 1, stopConfirmedAt: null } : { accepted: true } }
    } }
    await assert.rejects(recoverBatch({ ...config(f, 'batch'), identity: { ...f.identity, hostInstanceId: 'new-host' },
      client, vault }), /部分执行尝试等待恢复/)
    assert.deepEqual(calls, [['read', 20], ['request-stop', 20], ['confirm-stop', 20]])
    const remaining = await journal.list(null, 'batch', 1)
    assert.deepEqual(remaining.map(value => value.taskId), [10])
    assert.ok(await readBatchRun(config(f, 'batch')))
  } finally { await rm(f.base, { recursive: true, force: true }) }
})
