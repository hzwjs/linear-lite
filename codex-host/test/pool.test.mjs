import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { performance } from 'node:perf_hooks'
import { runPool } from '../src/pool.mjs'

async function fixture() {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-pool-')))
  const project = path.join(base, 'project'), state = path.join(base, 'state')
  await mkdir(project); await mkdir(state)
  const identity = { deviceId: 'device-1', hostInstanceId: 'host-1' }
  const store = new Map(), calls = []
  let completed = false
  const task = { taskId: 3, inputRevision: 1, state: 'READY', title: '任务', description: '需求', writeScopes: ['result.txt'], prerequisites: [] }
  const client = { async call(route, payload) {
    calls.push({ route, payload })
    let result
    if (route === 'bindings/get') result = { id: 2, projectId: 1, deviceId: identity.deviceId, projectDirectory: project }
    else if (route === 'batches/issue') result = { batchId: 'batch-1', batchToken: 'batch-secret' }
    else if (route === 'environment/observe') result = { state: completed ? 'COMPLETE' : 'READY', tasks: [task] }
    else if (route === 'tasks/claim') result = { accepted: true, claimToken: 'claim-secret', attemptId: 4,
      leaseUntil: new Date(Date.now() + 120000).toISOString() }
    else if (route === 'attempts/complete') { completed = true; result = { accepted: true } }
    else throw new Error(`unexpected ${route}`)
    return { result, serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
  } }
  const vault = { async put(k, v) { store.set(k, v) }, async get(k) { return store.get(k) }, async remove(k) { store.delete(k) } }
  const options = { client, identity, projectId: 1, taskIds: [3], expiresAt: '2099-01-01T00:00:00Z',
    maxSlots: 1, maxAttempts: 1, maxDurationMs: 5000, stateDirectory: state, vault, authToken: 'auth-secret' }
  return { base, project, state, identity, calls, store, options }
}

test('one slot serializes claims, writes within scope and delivers a content snapshot', async () => {
  const f = await fixture()
  try {
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      let resolve
      const completion = new Promise(r => { resolve = r })
      setImmediate(async () => {
        try {
          const call = (action, payload) => onToolCall({ arguments: { action, payload } })
          const [first, second] = await Promise.allSettled([call('claim', { taskId: 3, inputRevision: 1 }), call('claim', { taskId: 3, inputRevision: 1 })])
          assert.equal(first.status, 'fulfilled'); assert.equal(first.value.accepted, true); assert.equal(second.status, 'rejected')
          await call('write_file', { path: 'result.txt', content: '成果' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
          resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
        } catch (error) { resolve(null); throw error }
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt() { throw new Error('completed turn must not be interrupted') }, close() {} })
    const result = await runPool({ ...f.options, createAgent })
    assert.deepEqual(result, { state: 'COMPLETE' })
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), '成果')
    assert.equal(f.calls.filter(x => x.route === 'tasks/claim').length, 1)
    const evidence = f.calls.find(x => x.route === 'attempts/complete').payload.evidence
    assert.match(evidence.projectCheckpoint, /^linear-lite-snapshot:/)
    assert.deepEqual(evidence.changedPaths, ['result.txt'])
    assert.equal(f.store.size, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('ambiguous claim keeps recovery metadata and freezes the project lock', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => { if (route === 'tasks/claim') throw new Error('connection dropped'); return original(route, payload) }
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        await assert.rejects(onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } }))
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    const result = await runPool({ ...f.options, createAgent })
    assert.deepEqual(result, { state: 'RECOVERY_REQUIRED' })
    assert.equal(f.store.get('batch:batch-1'), 'batch-secret')
    const { RecoveryJournal } = await import('../src/recovery.mjs')
    const records = await new RecoveryJournal(f.state).list('host-1')
    assert.equal(records.length, 1); assert.equal(records[0].attemptId, null)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a rejected claim does not prevent a later ready task from getting a slot', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    let claims = 0
    f.options.client.call = async (route, payload) => {
      if (route === 'tasks/claim' && claims++ === 0) return { result: { accepted: false, reason: 'CONFLICT' }, serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        const claim = await call('claim', { taskId: 3, inputRevision: 1 })
        if (claim.accepted) {
          await call('write_file', { path: 'result.txt', content: '成果' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
        }
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    assert.deepEqual(await runPool({ ...f.options, maxSlots: 2, createAgent }), { state: 'COMPLETE' })
    assert.equal(claims, 2)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a stop during agent creation does not start a turn', async () => {
  const f = await fixture(); const abort = new AbortController()
  try {
    let created; const creation = new Promise(resolve => { created = resolve })
    let entered; const waiting = new Promise(resolve => { entered = resolve })
    let runs = 0
    const createAgent = async () => { entered(); await creation; return { async run() { runs++ }, close() {} } }
    const pool = runPool({ ...f.options, signal: abort.signal, createAgent })
    await waiting; abort.abort(); created()
    assert.deepEqual(await pool, { state: 'USER_STOPPED' })
    assert.equal(runs, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a stop during turn start interrupts the returned turn', async () => {
  const f = await fixture(); const abort = new AbortController()
  try {
    let start; const starting = new Promise(resolve => { start = resolve })
    let entered; const waiting = new Promise(resolve => { entered = resolve })
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const interruptions = []
    const createAgent = async () => ({ threadId: 'thread-1', async run() { entered(); await starting; return { turnId: 'turn-1', completion } },
      async interrupt(id) { interruptions.push(id); finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    const pool = runPool({ ...f.options, signal: abort.signal, createAgent })
    await waiting; abort.abort(); start()
    assert.deepEqual(await pool, { state: 'USER_STOPPED' })
    assert.deepEqual(interruptions, ['turn-1'])
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('completion waits for an in-flight heartbeat before reporting delivery', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] })
  const f = await fixture()
  try {
    let release; const renewal = new Promise(resolve => { release = resolve })
    let renewStarted; const started = new Promise(resolve => { renewStarted = resolve })
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route !== 'attempts/renew') return original(route, payload)
      renewStarted(); await renewal
      return { result: { accepted: true, data: { leaseUntil: new Date(Date.now() + 120000).toISOString() } }, serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
    }
    let completeTurn
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => { completeTurn = resolve })
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('write_file', { path: 'result.txt', content: '成果' })
        await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
        t.mock.timers.tick(10000)
        await started
        completeTurn({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    const pool = runPool({ ...f.options, createAgent })
    await started
    await new Promise(resolve => setTimeout(resolve, 20))
    assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
    release()
    assert.deepEqual(await pool, { state: 'COMPLETE' })
  } finally { t.mock.timers.reset(); await rm(f.base, { recursive: true, force: true }) }
})

test('checkpoint connection loss seals the active write capability', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint') throw new Error('offline')
      if (route === 'attempts/request-stop') return { result: { accepted: true } }
      return original(route, payload)
    }
    let finish
    const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('write_file', { path: 'result.txt', content: 'before' })
        await assert.rejects(call('checkpoint', { artifactRefs: ['result.txt'] }))
        await assert.rejects(call('write_file', { path: 'result.txt', content: 'after' }))
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'RECOVERY_REQUIRED' })
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), 'before')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('stop during a pending claim sends stop after the grant arrives', async () => {
  const f = await fixture(); const abort = new AbortController()
  try {
    let release; const grant = new Promise(resolve => { release = resolve })
    let entered; const claiming = new Promise(resolve => { entered = resolve })
    let requestStops = 0
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'tasks/claim') { entered(); await grant; return original(route, payload) }
      if (route === 'attempts/request-stop') { requestStops++; return { result: { accepted: true } } }
      if (route === 'attempts/confirm-stop') return { result: { accepted: true } }
      return original(route, payload)
    }
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(() => { void onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } }) })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    const pool = runPool({ ...f.options, signal: abort.signal, createAgent })
    await claiming; abort.abort(); release()
    assert.deepEqual(await pool, { state: 'USER_STOPPED' })
    assert.equal(requestStops, 1)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a locally stale claim does not block refilling a ready task', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    let observations = 0
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe' && ++observations === 2) return {
        result: { state: 'ACTIVE', tasks: [{ taskId: 3, inputRevision: 1, state: 'ACTIVE', prerequisites: [] }] },
        serverTime: new Date().toISOString(), requestStartedAt: performance.now(),
      }
      return original(route, payload)
    }
    let turns = 0
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        if (++turns === 1) await assert.rejects(call('claim', { taskId: 3, inputRevision: 1 }))
        else {
          await call('claim', { taskId: 3, inputRevision: 1 })
          await call('write_file', { path: 'result.txt', content: '成果' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
        }
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    assert.deepEqual(await runPool({ ...f.options, maxSlots: 2, createAgent }), { state: 'COMPLETE' })
    assert.equal(turns, 2)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a rejected checkpoint seals the active write capability', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint') return { result: { accepted: false, reason: 'INPUT_CHANGED' } }
      if (route === 'attempts/request-stop' || route === 'attempts/confirm-stop') return { result: { accepted: true } }
      return original(route, payload)
    }
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('write_file', { path: 'result.txt', content: 'before' })
        await assert.rejects(call('checkpoint', { artifactRefs: ['result.txt'] }))
        await assert.rejects(call('write_file', { path: 'result.txt', content: 'after' }))
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'BUDGET_EXHAUSTED' })
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), 'before')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('an agent stop returns its decision reason without claiming the task again', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    const stopRequests = []
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/request-stop') { stopRequests.push(payload); return { result: { accepted: true } } }
      if (route === 'attempts/confirm-stop') return { result: { accepted: true } }
      return original(route, payload)
    }
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('stop', { reason: '外部接口定义缺失，需要业务确认' })
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    const result = await runPool({ ...f.options, maxAttempts: 3, createAgent })
    assert.deepEqual(result, { state: 'DECISION_REQUIRED', reason: '外部接口定义缺失，需要业务确认' })
    assert.equal(f.calls.filter(call => call.route === 'tasks/claim').length, 1)
    assert.equal(stopRequests.length, 1)
    assert.equal(stopRequests[0].reason, '外部接口定义缺失，需要业务确认')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})
