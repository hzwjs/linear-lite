import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { spawn } from 'node:child_process'
import { performance } from 'node:perf_hooks'
import path from 'node:path'
import { BatchDriver } from '../src/batch-driver.mjs'
import { createBatchRun, readBatchRun } from '../src/batch-journal.mjs'
import { ProcessGroupJournal, stopProcessGroup } from '../src/process-groups.mjs'
import { RecoveryJournal, recoverBatch } from '../src/recovery.mjs'
import { createLocalRecoveryProof } from '../src/recovery-proof.mjs'
import { runPool } from '../src/pool.mjs'

function taskView(fields) {
  return { prerequisites: [], attempts: [], contextFacts: [], prerequisiteReviewFacts: [],
    dependencyFacts: [], prerequisiteReviewStatuses: [], ...fields }
}
async function fixture() {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll134-driver-')))
  const project = path.join(base, 'project'); const other = path.join(base, 'other'); const state = path.join(base, 'state')
  await mkdir(project); await mkdir(other); await mkdir(state)
  const identity = { deviceId: 'device', hostInstanceId: 'new-host' }
  const entry = (batchId, projectId = 1, projectDirectory = project) => ({ batchId, projectId, projectDirectory,
    deviceId: 'device', lifecycle: 'queued', desiredIntent: 'RUN' })
  const store = new Map()
  const vault = { async get(key) { if (!store.has(key)) throw new Error('missing'); return store.get(key) },
    async put(key, value) { store.set(key, value) }, async remove(key) { store.delete(key) },
    async removeIfPresent(key) { return store.delete(key) } }
  return { base, project, other, state, identity, entry, store, vault }
}

async function createOldRun(f, batchId = 'old-batch') {
  const old = spawn(process.execPath, ['-e', ''])
  await new Promise(resolve => old.once('exit', resolve))
  const identity = { ...f.identity, hostInstanceId: 'old-host' }
  const config = { stateDirectory: f.state, identity, projectId: 1, projectDirectory: f.project, batchId }
  const run = await createBatchRun(config)
  await run.reserve(1); await run.prepared(); await run.accepted()
  const file = path.join(f.state, 'runs', batchId, '1.json')
  const owner = JSON.parse(await readFile(file, 'utf8')); owner.processId = old.pid
  await writeFile(file, JSON.stringify(owner))
  f.store.set(`batch:${batchId}:1`, 'test-batch-capability')
  f.store.set(`batch-recovery:${batchId}:1`, 'test-recovery-capability')
  return owner
}

test('same-root batches start independent pools and duplicate polls do not start a batch twice', async () => {
  const f = await fixture(); const started = []; const releases = new Map()
  let items = [f.entry('one'), f.entry('same-root'), f.entry('other-root', 2, f.other)]
  const client = { async call(route) { assert.equal(route, 'batches/pending'); return { result: { items } } } }
  const pool = ({ batchId }) => new Promise(resolve => { started.push(batchId); releases.set(batchId, resolve) })
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault, pool })
  try {
    await driver.poll(); await driver.poll()
    assert.deepEqual(started, ['one', 'same-root', 'other-root'])
    items = [f.entry('same-root')]
    releases.get('one')({ state: 'COMPLETE' }); await new Promise(resolve => setImmediate(resolve))
    await driver.poll()
    assert.deepEqual(started, ['one', 'same-root', 'other-root'])
  } finally {
    for (const release of releases.values()) release({ state: 'COMPLETE' })
    await driver.close(); await rm(f.base, { recursive: true, force: true })
  }
})

test('driver shutdown aborts active pool and waits for its stop confirmation', async () => {
  const f = await fixture(); let sawAbort; const abortSeen = new Promise(resolve => { sawAbort = resolve }); let finish
  const client = { async call() { return { result: { items: [f.entry('one')] } } } }
  const pool = ({ signal }) => new Promise(resolve => { finish = resolve; signal.addEventListener('abort', sawAbort, { once: true }) })
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault, pool })
  try {
    await driver.poll(); let closed = false
    const closing = driver.close().then(() => { closed = true })
    await abortSeen; assert.equal(closed, false)
    finish({ state: 'HOST_STOPPED' }); await closing; assert.equal(closed, true)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('restart recovers an accepted batch without pending or OAuth', async () => {
  const f = await fixture(); const owner = await createOldRun(f); const routes = []
  const client = { async call() { throw new Error('authorization unavailable') }, async recover(route, body) {
    routes.push(route); assert.equal(body.batchToken, 'test-batch-capability'); return { result: { batchId: 'old-batch', lifecycle: 'ended' } }
  } }
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault })
  try {
    await assert.rejects(driver.poll(), /authorization unavailable/)
    await Promise.all([...driver.recovering.values()])
    assert.deepEqual(routes, ['finish'])
    assert.equal(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: owner.projectId,
      projectDirectory: owner.projectDirectory, batchId: owner.batchId }), null)
    assert.equal(f.store.size, 0)
  } finally { await driver.close(); await rm(f.base, { recursive: true, force: true }) }
})

test('live old process group retains its batch while another project may start', async () => {
  const f = await fixture(); const owner = await createOldRun(f)
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
  const groups = new ProcessGroupJournal(f.state)
  const group = { ...owner, agentInstanceId: 'agent-old', phase: 'running', processGroupId: child.pid }
  await groups.save(group)
  const routes = []; const started = []
  const client = { async call() { return { result: { items: [f.entry('other-batch', 2, f.other)] } } },
    async recover(route) { routes.push(route); return { result: { batchId: 'old-batch', lifecycle: 'ended' } } } }
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
    pool: async ({ batchId }) => { started.push(batchId); return { state: 'COMPLETE' } } })
  try {
    await driver.poll(); await Promise.all([...driver.recovering.values()]); await new Promise(resolve => setImmediate(resolve))
    assert.deepEqual(routes, []); assert.deepEqual(started, ['other-batch'])
    assert.ok(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'old-batch' }))
    assert.ok(f.store.has('batch:old-batch:1'))
    await stopProcessGroup(child.pid)
    await driver.poll(); await Promise.all([...driver.recovering.values()])
    assert.deepEqual(routes, ['finish'])
    assert.equal(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'old-batch' }), null)
  } finally { await stopProcessGroup(child.pid).catch(() => {}); await driver.close(); await rm(f.base, { recursive: true, force: true }) }
})

test('missing or starting process group record prevents recovery confirmation', async () => {
  const f = await fixture(); const owner = await createOldRun(f); const routes = []
  const journal = new RecoveryJournal(f.state); const groups = new ProcessGroupJournal(f.state)
  const { phase, ...association } = owner
  const attempt = { ...association, bindingId: 2, agentInstanceId: 'agent-old', taskId: 3, inputRevision: 1,
    attemptId: 4, claimOperationId: 'claim-old', stopConfirmed: false, claimOutcome: null }
  await journal.save(attempt)
  const client = { async call() { return { result: { items: [] } } },
    async recover(route) { routes.push(route); return { result: { batchId: 'old-batch', lifecycle: 'ended' } } } }
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault })
  try {
    await driver.poll(); await Promise.all([...driver.recovering.values()]); assert.deepEqual(routes, [])
    await groups.save({ ...owner, agentInstanceId: 'agent-old', phase: 'starting', processGroupId: null })
    await driver.poll(); await Promise.all([...driver.recovering.values()]); assert.deepEqual(routes, [])
    assert.ok(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1,
      projectDirectory: f.project, batchId: 'old-batch' }))
    assert.equal((await journal.list('old-host', 'old-batch', 1)).length, 1)
  } finally { await driver.close(); await rm(f.base, { recursive: true, force: true }) }
})

test('a joined pool reconnects using the same batch capability and fixed budget', async () => {
  const f = await fixture(); const item = f.entry('same-batch'); const calls = []
  let offline = false; let queued = true
  const context = { ...item, bindingId: 7, maxSlots: 1, maxAttempts: 3, attemptsUsed: 1 }
  const deadline = new Date(Date.now() + 60000).toISOString()
  const response = result => { const now = performance.now(); return { result, serverTime: new Date().toISOString(),
    requestStartedAt: now, responseReceivedAt: now } }
  const client = {
    async call(route) {
      calls.push(route)
      if (route === 'batches/pending') return response({ items: queued ? [item] : [] })
      if (route === 'batches/context') return response(context)
      if (route === 'batches/prepare-accept') return response({ batchId: item.batchId, runEpoch: 1, recoveryToken: 'fixture-recovery' })
      if (route === 'batches/accept') { queued = false; return response({ batchId: item.batchId,
        batchToken: 'fixture-batch', runEpoch: 1, deadlineAt: deadline }) }
      if (route === 'environment/observe') { offline = true; throw new Error('offline') }
      throw new Error(`unexpected ${route}`)
    },
    async recover(route, payload) {
      calls.push(`recovery/${route}`)
      if (offline) throw new Error('offline')
      assert.equal(payload.batchToken, 'fixture-batch')
      return response({ batchId: item.batchId, lifecycle: 'ended' })
    }, knownCredentials() { return [] },
  }
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
    pool: options => runPool({ ...options, createAgent() { throw new Error('no task should start') } }) })
  try {
    await driver.poll()
    await driver.active.get(item.batchId).finished
    assert.equal(driver.recoveries.size, 1)
    assert.ok(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'same-batch' }))
    assert.equal(f.store.get('batch:same-batch:1'), 'fixture-batch')
    offline = false
    await driver.poll(); await Promise.all([...driver.recovering.values()])
    assert.equal(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'same-batch' }), null)
    assert.equal(f.store.has('batch:same-batch:1'), false)
    assert.equal(calls.filter(route => route === 'batches/accept').length, 1)
    assert.equal(calls.filter(route => route === 'recovery/finish').length, 1)
    assert.equal(context.attemptsUsed, 1)
    assert.equal(calls.filter(route => route === 'batches/context').length, 1)
  } finally { await driver.close(); await rm(f.base, { recursive: true, force: true }) }
})

test('a local proof cannot recover a batch with a live process group or changed owner', async () => {
  const f = await fixture()
  const config = { stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'batch' }
  const run = await createBatchRun(config); await run.reserve(1); await run.prepared(); await run.accepted()
  const owner = await readBatchRun(config)
  const proof = createLocalRecoveryProof(owner, f.identity)
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
  const groups = new ProcessGroupJournal(f.state)
  await groups.save({ ...owner, agentInstanceId: 'agent', phase: 'running', processGroupId: child.pid })
  let finished = 0
  const client = { async recover() { finished++; return { result: { batchId: 'old-batch', lifecycle: 'ended' } } } }
  try {
    await assert.rejects(recoverBatch({ ...config, client, vault: f.vault, localProof: proof }), /进程组未确认退出/)
    assert.equal(finished, 0)
    assert.ok(await readBatchRun(config))
    await stopProcessGroup(child.pid)
    const file = path.join(f.state, 'runs', 'batch', '1.json')
    await writeFile(file, JSON.stringify({ ...owner, runEpoch: 2 }))
    await assert.rejects(recoverBatch({ ...config, client, vault: f.vault, localProof: proof }), /批次运行记录归属无效/)
    assert.equal(finished, 0)
  } finally { await stopProcessGroup(child.pid).catch(() => {}); await rm(f.base, { recursive: true, force: true }) }
})

test('a joined active attempt confirms stop after the network returns without restarting', async () => {
  const f = await fixture(); const item = f.entry('active-batch'); const calls = []
  let offline = false; let queued = true; let agentError
  const context = { ...item, bindingId: 7, maxSlots: 1, maxAttempts: 3, attemptsUsed: 0 }
  const response = result => { const now = performance.now(); return { result, serverTime: new Date().toISOString(),
    requestStartedAt: now, responseReceivedAt: now } }
  const client = {
    async call(route) {
      calls.push(route)
      if (route === 'batches/pending') return response({ items: queued ? [item] : [] })
      if (route === 'batches/context') return response(context)
      if (route === 'batches/prepare-accept') return response({ batchId: item.batchId, runEpoch: 1, recoveryToken: 'fixture-recovery' })
      if (route === 'batches/accept') { queued = false; return response({ batchId: item.batchId,
        batchToken: 'fixture-batch', runEpoch: 1, deadlineAt: new Date(Date.now() + 60000).toISOString() }) }
      if (route === 'environment/observe' && offline) throw new Error('offline')
      if (route === 'environment/observe') return response({ state: 'READY', tasks: [taskView({ taskId: 3, inputRevision: 1,
        state: 'READY' })] })
      if (route === 'tasks/claim') return response({ accepted: true, attemptId: 4, claimToken: 'fixture-claim',
        inputRevision: 1, prerequisites: [], leaseUntil: new Date(Date.now() + 30000).toISOString() })
      if (route === 'attempts/checkpoint') { offline = true; throw new Error('offline') }
      throw new Error(`unexpected ${route}`)
    },
    async recover(route, payload) {
      calls.push(`recovery/${route}`)
      if (offline) throw new Error('offline')
      assert.equal(payload.batchToken, 'fixture-batch')
      return response(route === 'read' ? { id: 4, taskId: 3, bindingId: 7, batchId: item.batchId, hostInstanceId: f.identity.hostInstanceId, agentInstanceId: payload.command.agentInstanceId, inputRevision: 1, stopConfirmedAt: null } : route === 'finish' ? { batchId: item.batchId, lifecycle: 'ended' } : { accepted: true })
    }, knownCredentials() { return [] },
  }
  const createAgent = async ({ onToolCall }) => ({ threadId: 'thread', async run() {
    const completion = new Promise(resolve => setImmediate(async () => {
      try {
        await onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } })
        await assert.rejects(onToolCall({ arguments: { action: 'checkpoint', payload: { artifactRefs: [], finding: 'fixture' } } }))
      } catch (error) { agentError = error }
      resolve({ threadId: 'thread', turn: { id: 'turn', status: 'completed' } })
    }))
    return { turnId: 'turn', completion }
  }, async interrupt() {}, async close() {} })
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
    pool: options => runPool({ ...options, createAgent }) })
  try {
    await driver.poll(); await driver.active.get(item.batchId).finished
    assert.ifError(agentError)
    assert.equal(driver.recoveries.size, 1)
    assert.equal((await new RecoveryJournal(f.state).list(f.identity.hostInstanceId, item.batchId, 1)).length, 1)
    offline = false
    await driver.poll(); await Promise.all([...driver.recovering.values()])
    assert.deepEqual(calls.filter(route => route.startsWith('recovery/')).slice(-4),
      ['recovery/read', 'recovery/request-stop', 'recovery/confirm-stop', 'recovery/finish'])
    assert.equal(f.store.size, 0)
    assert.equal((await new RecoveryJournal(f.state).list(f.identity.hostInstanceId, item.batchId, 1)).length, 0)
    assert.equal(await readBatchRun({ stateDirectory: f.state, identity: f.identity, projectId: 1, projectDirectory: f.project, batchId: 'active-batch' }), null)
    assert.equal(calls.filter(route => route === 'batches/accept').length, 1)
    assert.equal(calls.filter(route => route === 'tasks/claim').length, 1)
  } finally { await driver.close(); await rm(f.base, { recursive: true, force: true }) }
})

test('a stalled recovery does not delay another queued batch on the same project', async () => {
  const f = await fixture(); await createOldRun(f, 'old-batch')
  let releaseRecovery; let releasePool
  const waiting = new Promise(resolve => { releaseRecovery = resolve })
  const started = []
  const client = {
    async call(route) {
      assert.equal(route, 'batches/pending')
      return { result: { items: [f.entry('new-batch')] } }
    },
    async recover(route) {
      assert.equal(route, 'finish')
      await waiting
      return { result: { batchId: 'old-batch', lifecycle: 'ended' } }
    },
  }
  const pool = ({ batchId }) => new Promise(resolve => { started.push(batchId); releasePool = resolve })
  const driver = new BatchDriver({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault, pool })
  try {
    await driver.poll()
    assert.deepEqual(started, ['new-batch'])
    assert.equal(driver.recovering.has('old-batch'), true)
    assert.ok(await readBatchRun({ stateDirectory: f.state, batchId: 'old-batch' }))
  } finally {
    releaseRecovery()
    releasePool({ state: 'COMPLETE' })
    await driver.close()
    await rm(f.base, { recursive: true, force: true })
  }
})

test('two same-root pools persist separate accepted runs and keep their own budgets', async () => {
  const f = await fixture()
  const items = [f.entry('first'), f.entry('second')]
  const expectedEpoch = { first: 3, second: 7 }
  const arrival = new Set()
  let releaseObservation; let bothAccepted
  const waiting = new Promise(resolve => { releaseObservation = resolve })
  const ready = new Promise(resolve => { bothAccepted = resolve })
  const calls = []
  const response = result => { const now = performance.now(); return { result, serverTime: new Date().toISOString(),
    requestStartedAt: now, responseReceivedAt: now } }
  const client = {
    async call(route, body) {
      calls.push({ route, body })
      if (route === 'batches/context') {
        const item = items.find(value => value.batchId === body.batchId)
        return response({ ...item, bindingId: 7, maxSlots: 1, maxAttempts: 5, attemptsUsed: 2 })
      }
      if (route === 'batches/prepare-accept') return response({ batchId: body.batchId,
        runEpoch: expectedEpoch[body.batchId], recoveryToken: `recovery-${body.batchId}` })
      if (route === 'batches/accept') {
        assert.equal(body.recoveryToken, `recovery-${body.batchId}`)
        return response({ batchId: body.batchId, batchToken: `batch-${body.batchId}`,
          runEpoch: expectedEpoch[body.batchId], deadlineAt: new Date(Date.now() + 60000).toISOString() })
      }
      if (route === 'environment/observe') {
        assert.deepEqual(body.sourceObservations, [])
        arrival.add(body.batch.batchToken)
        if (arrival.size === 2) bothAccepted()
        await waiting
        return response({ state: 'COMPLETE', tasks: [] })
      }
      throw new Error(`unexpected ${route}`)
    },
    async recover(route, body) {
      assert.equal(route, 'finish')
      calls.push({ route: `recovery/${route}`, body })
      return response({ batchId: body.batchToken.slice('batch-'.length), lifecycle: 'ended' })
    }, knownCredentials() { return [] },
  }
  const run = item => runPool({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
    projectId: item.projectId, batchId: item.batchId, createAgent() { throw new Error('unexpected agent') } })
  let pools
  try {
    pools = items.map(run)
    await ready
    const first = await readBatchRun({ stateDirectory: f.state, batchId: 'first' })
    const second = await readBatchRun({ stateDirectory: f.state, batchId: 'second' })
    assert.equal(first.projectDirectory, second.projectDirectory)
    assert.deepEqual([first.runEpoch, second.runEpoch], [3, 7])
    assert.equal(first.phase, 'accepted'); assert.equal(second.phase, 'accepted')
    releaseObservation()
    assert.deepEqual(await Promise.all(pools), [{ state: 'COMPLETE' }, { state: 'COMPLETE' }])
    assert.equal(calls.filter(call => call.route === 'batches/accept').length, 2)
    assert.equal(calls.filter(call => call.route === 'recovery/finish').length, 2)
    assert.equal(f.store.size, 0)
  } finally {
    releaseObservation()
    if (pools) await Promise.allSettled(pools)
    await rm(f.base, { recursive: true, force: true })
  }
})

test('an unknown claim is fenced locally before another task uses a one-slot batch', async () => {
  const f = await fixture(); const calls = []; let stage = 0; let agents = 0
  const response = result => { const now = performance.now(); return { result, serverTime: new Date().toISOString(),
    requestStartedAt: now, responseReceivedAt: now } }
  const client = {
    async call(route, body) {
      calls.push({ route, body })
      if (route === 'batches/context') return response({ ...f.entry('batch'), bindingId: 7,
        maxSlots: 1, maxAttempts: 1, attemptsUsed: 0 })
      if (route === 'batches/prepare-accept') return response({ batchId: 'batch', runEpoch: 1, recoveryToken: 'recovery' })
      if (route === 'batches/accept') return response({ batchId: 'batch', batchToken: 'batch-token', runEpoch: 1,
        deadlineAt: new Date(Date.now() + 15000).toISOString() })
      if (route === 'environment/observe') return response(stage === 2 ? { state: 'COMPLETE', tasks: [] }
        : { state: 'READY', tasks: (stage === 0 ? [3, 4] : [4]).map(taskId => taskView({ taskId,
          inputRevision: 1, state: 'READY', title: 'task', description: 'goal' })) })
      if (route === 'tasks/claim' && body.taskId === 3) throw new Error('claim response lost')
      if (route === 'tasks/claim' && body.taskId === 4) return response({ accepted: true, attemptId: 9,
        claimToken: 'claim-token', inputRevision: 1, prerequisites: [], leaseUntil: new Date(Date.now() + 10000).toISOString() })
      if (route === 'attempts/complete') { stage = 2; return response({ accepted: true }) }
      throw new Error(`unexpected ${route}`)
    },
    async recover(route, body) {
      calls.push({ route: `recovery/${route}`, body })
      if (route === 'recover-claim') { stage = 1; return response({ accepted: true, data: { claimOutcome: 'FENCED', attemptId: null } }) }
      if (route === 'finish') return response({ batchId: 'batch', lifecycle: 'ended' })
      throw new Error(`unexpected recovery ${route}`)
    }, knownCredentials() { return [] },
  }
  const createAgent = async ({ onToolCall }) => {
    const agent = ++agents, threadId = `thread-${agent}`, turnId = `turn-${agent}`
    return { threadId, async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        if (agent === 1) await assert.rejects(call('claim', { taskId: 3, inputRevision: 1 }))
        else {
          assert.equal((await call('claim', { taskId: 4, inputRevision: 1 })).accepted, true)
          await call('deliver', { artifactRefs: [], conditions: [{ condition: 'analysis', result: 'complete', evidenceRefs: [] }] })
        }
        resolve({ threadId, turn: { id: turnId, status: 'completed' } })
      }))
      return { turnId, completion }
    }, async interrupt() {}, async close() {} }
  }
  try {
    const result = await runPool({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
      projectId: 1, batchId: 'batch', createAgent })
    assert.deepEqual(result, { state: 'COMPLETE' })
    const fence = calls.findIndex(call => call.route === 'recovery/recover-claim')
    const second = calls.findIndex(call => call.route === 'tasks/claim' && call.body.taskId === 4)
    assert(fence >= 0 && second > fence)
    assert.equal(calls.filter(call => call.route === 'recovery/finish').length, 1)
    assert.equal(f.store.size, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('malformed claim reply remains unknown until the scoped fence returns', async () => {
  const f = await fixture(); let stage = 0; let releaseFence; let fenceEntered
  const fenceWaiting = new Promise(resolve => { releaseFence = resolve })
  const fenceStarted = new Promise(resolve => { fenceEntered = resolve })
  const response = result => { const now = performance.now(); return { result, serverTime: new Date().toISOString(),
    requestStartedAt: now, responseReceivedAt: now } }
  const client = {
    async call(route) {
      if (route === 'batches/context') return response({ ...f.entry('batch'), bindingId: 7,
        maxSlots: 1, maxAttempts: 1, attemptsUsed: 0 })
      if (route === 'batches/prepare-accept') return response({ batchId: 'batch', runEpoch: 1, recoveryToken: 'recovery' })
      if (route === 'batches/accept') return response({ batchId: 'batch', batchToken: 'batch-token', runEpoch: 1,
        deadlineAt: new Date(Date.now() + 10000).toISOString() })
      if (route === 'environment/observe') return response(stage ? { state: 'COMPLETE', tasks: [] } :
        { state: 'READY', tasks: [taskView({ taskId: 3, inputRevision: 1, state: 'READY', title: 'task' })] })
      if (route === 'tasks/claim') return response({ claimToken: 'claim-token', attemptId: 9 })
      throw new Error(`unexpected ${route}`)
    },
    async recover(route) {
      if (route === 'recover-claim') { fenceEntered(); await fenceWaiting; stage = 1
        return response({ accepted: true, data: { claimOutcome: 'FENCED', attemptId: null } }) }
      if (route === 'finish') return response({ batchId: 'batch', lifecycle: 'ended' })
      throw new Error(`unexpected ${route}`)
    }, knownCredentials() { return [] },
  }
  const createAgent = async ({ onToolCall }) => ({ threadId: 'thread', async run() {
    const completion = new Promise(resolve => setImmediate(async () => {
      await assert.rejects(onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } }))
      resolve({ threadId: 'thread', turn: { id: 'turn', status: 'completed' } })
    }))
    return { turnId: 'turn', completion }
  }, async interrupt() {}, async close() {} })
  let run
  try {
    run = runPool({ client, identity: f.identity, stateDirectory: f.state, vault: f.vault,
      projectId: 1, batchId: 'batch', createAgent })
    await fenceStarted
    const records = await new RecoveryJournal(f.state).list(null, 'batch', 1)
    assert.equal(records.length, 1)
    assert.equal(records[0].attemptId, null)
    assert.equal(records[0].stopConfirmed, false)
    releaseFence()
    assert.deepEqual(await run, { state: 'COMPLETE' })
  } finally {
    releaseFence()
    if (run) await run.catch(() => {})
    await rm(f.base, { recursive: true, force: true })
  }
})
