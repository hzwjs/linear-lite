import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, realpath, rename, rm, readdir, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { performance } from 'node:perf_hooks'
import { createHash } from 'node:crypto'
import { runPool } from '../src/pool.mjs'
import { HostError } from '../src/device.mjs'
import { sourcePlans, sourceFacts } from '../src/collaboration-observations.mjs'
import { createBatchRun, readBatchRun } from '../src/batch-journal.mjs'

function taskView(fields) {
  return { prerequisites: [], attempts: [], contextFacts: [], prerequisiteReviewFacts: [],
    dependencyFacts: [], prerequisiteReviewStatuses: [], ...fields }
}

test('observes and deduplicates trace candidates across source attempts', () => {
  const evidence = path => ({ artifactRefs: [path], artifactObservations: [], traceOperationIds: {} })
  const first = { attemptId: 90, inputRevision: 2, evidence: evidence('a.txt') }
  const second = { attemptId: 91, inputRevision: 2, evidence: evidence('b.txt') }
  const source = { taskId: 9, ...second, traceCandidates: [first, second] }
  const review = { sourceTaskId: 9, sourceAttemptId: 90, sourceInputRevision: 2,
    observations: [{ path: 'extra.txt', state: 'MISSING' }] }
  const plans = sourcePlans([taskView({ prerequisites: [source], prerequisiteReviewFacts: [{ reviews: [{ review }] }] }),
    taskView({ prerequisites: [source] })])
  const facts = new Map(['a.txt', 'b.txt', 'extra.txt'].map(path => [path, { path, state: 'MISSING' }]))
  const observed = sourceFacts(plans, facts)
  assert.equal(observed.length, 2)
  assert.deepEqual(observed.find(item => item.sourceAttemptId === 90).observations.map(item => item.path), ['a.txt', 'extra.txt'])
  assert.deepEqual(observed.find(item => item.sourceAttemptId === 91).observations.map(item => item.path), ['b.txt'])
})

async function fixture() {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-pool-')))
  const project = path.join(base, 'project'), state = path.join(base, 'state')
  await mkdir(project); await mkdir(state)
  const identity = { deviceId: 'device-1', hostInstanceId: 'host-1' }
  const store = new Map(), calls = []
  let completed = false
  let claimPrerequisites = []
  const context = { batchId: 'batch-1', bindingId: 2, projectId: 1, deviceId: identity.deviceId,
    projectDirectory: project, maxSlots: 1, maxAttempts: 1, attemptsUsed: 0, maxDurationMs: 5000 }
  const task = taskView({ taskId: 3, inputRevision: 1, state: 'READY', title: '任务', description: '需求' })
  const client = { async call(route, payload) {
    calls.push({ route, payload })
    let result
    if (route === 'batches/context') result = context
    else if (route === 'batches/prepare-accept') result = { batchId: context.batchId, runEpoch: 1, recoveryToken: 'recovery-secret' }
    else if (route === 'batches/accept') result = { batchId: context.batchId, batchToken: 'batch-secret', runEpoch: 1,
      runLeaseUntil: new Date(Date.now() + 30000).toISOString(), deadlineAt: new Date(Date.now() + context.maxDurationMs).toISOString() }
    else if (route === 'batches/renew') result = { batchId: context.batchId, desiredIntent: 'RUN',
      runLeaseUntil: new Date(Date.now() + 30000).toISOString(), deadlineAt: new Date(Date.now() + context.maxDurationMs).toISOString() }
    else if (route === 'environment/observe') result = { state: completed ? 'COMPLETE' : 'READY', tasks: [task] }
    else if (route === 'tasks/claim') result = { accepted: true, claimToken: 'claim-secret', attemptId: 4,
      inputRevision: payload.inputRevision, prerequisites: claimPrerequisites,
      leaseUntil: new Date(Date.now() + 120000).toISOString() }
    else if (route === 'attempts/checkpoint') result = { accepted: true }
    else if (route === 'attempts/complete') { completed = true; result = { accepted: true } }
    else throw new Error(`unexpected ${route}`)
    const receivedAt = performance.now()
    return { result, serverTime: new Date().toISOString(), requestStartedAt: receivedAt, responseReceivedAt: receivedAt }
  }, async recover(route, payload) {
    calls.push({ route: `recovery/${route}`, payload })
    assert.equal(payload.batchToken, 'batch-secret')
    return { result: route === 'finish' ? { batchId: context.batchId, lifecycle: 'ended' } : { accepted: true },
      serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
  }, knownCredentials() { return ['auth-secret'] } }
  const vault = { async put(k, v) { store.set(k, v) }, async get(k) { return store.get(k) }, async remove(k) { store.delete(k) }, async removeIfPresent(k) { return store.delete(k) } }
  const options = { client, identity, projectId: 1, batchId: context.batchId, stateDirectory: state, vault }
  return { base, project, state, identity, calls, store, options, context,
    setClaimPrerequisites(value) { claimPrerequisites = value } }
}

test('one slot serializes claims, edits the shared project and delivers a content snapshot', async () => {
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
          await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '成果' })
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

test('invalid optional checkpoint text stays local and can be corrected before service submission', async () => {
  const f = await fixture()
  let agentError
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint') {
        f.calls.push({ route, payload })
        return { result: { accepted: true }, serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
      }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        try {
          const call = (action, payload) => onToolCall({ arguments: { action, payload } })
          await call('claim', { taskId: 3, inputRevision: 1 })
          await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '成果' })
          await assert.rejects(call('checkpoint', { artifactRefs: ['result.txt'], finding: '发现', blockedReason: null }),
            error => error.code === 'TOOL_INPUT_INVALID')
          await call('checkpoint', { artifactRefs: ['result.txt'], finding: '发现' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
          resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
        } catch (error) { agentError = error; resolve(null) }
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    const result = await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.deepEqual(result, { state: 'COMPLETE' })
    const checkpoints = f.calls.filter(call => call.route === 'attempts/checkpoint' && !call.payload.checkpoint.artifactObservations.length)
    assert.equal(checkpoints.length, 1)
    assert.equal(Object.hasOwn(checkpoints[0].payload.checkpoint, 'blockedReason'), false)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

const versionOf = value => `sha256:${createHash('sha256').update(value).digest('hex')}`
const reply = result => {
  const at = performance.now()
  return { result, serverTime: new Date().toISOString(), requestStartedAt: at, responseReceivedAt: at }
}
const source = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
  evidence: { artifactRefs: ['source.txt', 'missing.txt'], artifactObservations: [], traceOperationIds: {},
    projectCheckpoint: 'linear-lite-snapshot:old-version' } }
const reviewSource = { sourceTaskId: source.taskId, sourceAttemptId: source.attemptId, sourceInputRevision: source.inputRevision }

function fakeAgent(onToolCall, act, onError, onInterrupt = () => {}) {
  return {
    threadId: 'fake-thread',
    async run(prompt) {
      const completion = new Promise(resolve => setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        try {
          await act(call, prompt)
          resolve({ threadId: this.threadId, turn: { id: 'fake-turn', status: 'completed' } })
        } catch (error) { onError(error); resolve(null) }
      }))
      return { turnId: 'fake-turn', completion }
    },
    async interrupt() { onInterrupt() },
    close() {},
  }
}

test('live source facts release a wait and drive claim, context adoption, editing, checkpoint and delivery', async () => {
  const f = await fixture()
  f.setClaimPrerequisites([source])
  let agentError
  let completed = false
  try {
    await writeFile(path.join(f.project, 'source.txt'), 'current source')
    await writeFile(path.join(f.project, 'companion.txt'), 'wait released')
    const original = f.options.client.call
    const currentSource = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('current source') }
    const missing = { path: 'missing.txt', state: 'MISSING' }
    const companion = { path: 'companion.txt', state: 'PRESENT', contentVersion: versionOf('wait released') }
    const priorWait = { attemptId: 66, reviews: [{ outcome: 'WAIT_FOR_CHANGE', review: {
      ...reviewSource, observations: [{ path: 'companion.txt', state: 'MISSING' }],
    } }] }
    const contextRefs = [
      { kind: 'DOCUMENT', documentId: 16, documentVersion: 4 },
      { kind: 'PROJECT_FILE', projectPath: 'source.txt', contentVersion: currentSource.contentVersion },
    ]
    let adopted = false
    let reviewOperationId
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const current = payload.sourceObservations[0]?.observations
          .some(fact => fact.path === companion.path && fact.contentVersion === companion.contentVersion)
        const state = completed ? 'COMPLETE' : current ? 'READY' : 'PREREQUISITE_REVIEW_WAIT'
        return reply({ state, tasks: [{ taskId: 3, title: '任务', inputRevision: 1, state, prerequisites: [source],
          attempts: [{ id: 4, bindingId: 2 }, { id: 5, bindingId: 99 }],
          contextFacts: adopted ? [4, 5].map(attemptId => ({ attemptId,
            refs: [{ ref: contextRefs[1], status: 'PENDING_CONFIRMATION' }] })) : [],
          prerequisiteReviewFacts: [priorWait], dependencyFacts: [], prerequisiteReviewStatuses: [] }] })
      }
      if (route === 'tasks/claim') {
        assert.deepEqual(payload.sourceObservations, [{ sourceAttemptId: 90, sourceInputRevision: 2,
          observations: [currentSource, missing, companion] }])
      }
      if (route === 'documents/list') return reply({ documents: [{ id: 16, title: '项目约定' }] })
      if (route === 'documents/read') {
        if (payload.documentId === 17) return reply({ id: 17, projectId: 1, version: 4, content: 'auth-secret' })
        return reply({ id: 16, projectId: 1, version: 4, content: '项目约定内容' })
      }
      if (route === 'attempts/adopt-context') {
        assert.deepEqual(payload.contextRefs, contextRefs)
        adopted = true
        return reply({ accepted: true, contextRefs: payload.contextRefs })
      }
      if (route === 'attempts/checkpoint') {
        assert.deepEqual(payload.checkpoint.contextRefs, contextRefs)
        assert.deepEqual(payload.checkpoint.artifactRefs, ['result.txt'])
        return reply({ accepted: true })
      }
      if (route === 'attempts/prerequisites/review') {
        assert.deepEqual(payload.review.observations, [currentSource])
        assert.equal(Object.hasOwn(payload.review, 'paths'), false)
        reviewOperationId = payload.command.operationId
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/complete') {
        assert.deepEqual(payload.evidence.contextRefs, contextRefs)
        assert.deepEqual(payload.evidence.changedPaths, ['result.txt'])
        assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId, observations: [currentSource] }])
        assert.match(payload.evidence.projectCheckpoint, /^linear-lite-snapshot:/)
        completed = true
      }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async (call, prompt) => {
      assert.match(prompt, /review_prerequisite/)
      const observation = await call('observe', {})
      assert.equal(observation.state, 'READY')
      assert.deepEqual(observation.sourceObservations[0].observations, [currentSource, missing, companion])
      const grant = await call('claim', { taskId: 3, inputRevision: 1 })
      assert.deepEqual(grant.task.prerequisites, [source])
      assert.equal((await call('list_documents', {})).documents[0].id, 16)
      await assert.rejects(call('adopt_context', { documentIds: [16], projectPaths: [] }), /读取所选文档/)
      await assert.rejects(call('read_document', { documentId: 17 }), /包含凭据/)
      await assert.rejects(call('adopt_context', { documentIds: [17], projectPaths: [] }), /读取所选文档/)
      await call('read_document', { documentId: 16 })
      await call('read_file', { path: 'source.txt' })
      await call('adopt_context', { documentIds: [16], projectPaths: ['source.txt'] })
      const adoptedView = await call('observe', {})
      assert.deepEqual(adoptedView.tasks[0].contextFacts.map(attempt => attempt.refs[0].status), ['CURRENT', 'PENDING_CONFIRMATION'])
      await call('create_file', { path: 'result.txt', content: 'result', operationId: 'edit-result' })
      await call('checkpoint', { artifactRefs: ['result.txt'], finding: '已处理当前目标' })
      const delivery = { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] }
      await assert.rejects(call('deliver', delivery), /复核并采用/)
      const review = { ...reviewSource, decision: 'ADOPT', finding: '可用', evidence: '已读当前来源文件', paths: ['source.txt'] }
      await assert.rejects(call('review_prerequisite', { ...review, observations: [currentSource] }), { code: 'TOOL_INPUT_INVALID' })
      await call('review_prerequisite', review)
      await call('deliver', delivery)
    }, error => { agentError = error })
    const result = await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.deepEqual(result, { state: 'COMPLETE' })
    assert.equal(f.calls.some(call => call.route === 'recovery/request-stop'), false)
    assert.equal(await readFile(path.join(f.project, 'source.txt'), 'utf8'), 'current source')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('matching source reads produce adoption without an agent review request', async () => {
  const f = await fixture()
  let agentError
  let completed = false
  let reviewOperationId
  const current = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('shared source') }
  const traceSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [current.path], artifactObservations: [current], traceOperationIds: { 'source.txt': 'trace-source' } } }
  f.setClaimPrerequisites([traceSource])
  try {
    await writeFile(path.join(f.project, current.path), 'shared source')
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = completed ? 'COMPLETE' : 'READY'
        return reply({ state, tasks: [taskView({ taskId: 3, title: '任务', inputRevision: 1, state,
          prerequisites: [traceSource] })] })
      }
      if (route === 'attempts/prerequisites/review') {
        f.calls.push({ route, payload })
        assert.equal(payload.review.decision, 'ADOPT')
        assert.deepEqual(payload.review.observations, [current])
        reviewOperationId = payload.command.operationId
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/complete') {
        assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId, observations: [current] }])
        completed = true
      }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      assert.equal((await call('read_file', { path: current.path })).content, 'shared source')
      await call('read_file', { path: current.path })
      await call('create_file', { path: 'result.txt', content: 'result', operationId: 'edit-result' })
      await call('deliver', { artifactRefs: ['result.txt'],
        conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
    assert.equal(f.calls.filter(call => call.route === 'attempts/prerequisites/review').length, 1)
    const write = f.calls.find(call => call.route === 'attempts/checkpoint')
    assert.deepEqual(write.payload.checkpoint.artifactObservations,
      [{ path: 'result.txt', state: 'PRESENT', contentVersion: versionOf('result') }])
    assert.deepEqual(write.payload.checkpoint.artifactRefs, ['result.txt'])
    const stored = JSON.parse(await readFile(path.join(f.state, 'file-operations', '4', 'edit-result.json'), 'utf8'))
    assert.equal(write.payload.command.operationId, stored.traceOperationId)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('claim uses the server fixed prerequisites when the observation changes during the claim', async () => {
  const f = await fixture()
  let agentError
  let completed = false
  const observed = { path: 'old.txt', state: 'PRESENT', contentVersion: versionOf('old') }
  const fixed = { path: 'new.txt', state: 'PRESENT', contentVersion: versionOf('new') }
  const observedSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [observed.path], artifactObservations: [observed], traceOperationIds: { 'old.txt': 'trace-old' } } }
  const fixedSource = { taskId: 9, attemptId: 91, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [fixed.path], artifactObservations: [fixed], traceOperationIds: { 'new.txt': 'trace-new' } } }
  f.setClaimPrerequisites([fixedSource])
  try {
    await writeFile(path.join(f.project, observed.path), 'old')
    await writeFile(path.join(f.project, fixed.path), 'new')
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = completed ? 'COMPLETE' : 'READY'
        return reply({ state, tasks: [taskView({ taskId: 3, title: '任务', inputRevision: 1, state,
          prerequisites: [observedSource] })] })
      }
      if (route === 'attempts/prerequisites/review') {
        assert.equal(payload.review.sourceAttemptId, fixedSource.attemptId)
        assert.deepEqual(payload.review.observations, [fixed])
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/complete') completed = true
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      const claim = await call('claim', { taskId: 3, inputRevision: 1 })
      assert.deepEqual(claim.task.prerequisites, [fixedSource])
      assert.equal((await call('read_file', { path: fixed.path })).content, 'new')
      await call('deliver', { artifactRefs: [], conditions: [{ condition: '来源已读取', result: '通过', evidenceRefs: [] }] })
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('an accepted claim without its fixed prerequisites is fenced and recovered', async () => {
  const f = await fixture()
  let agentError
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'tasks/claim') return reply({ accepted: true, claimToken: 'claim-secret', attemptId: 4,
        inputRevision: payload.inputRevision, leaseUntil: new Date(Date.now() + 120000).toISOString() })
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await assert.rejects(call('claim', { taskId: 3, inputRevision: 1 }), /领取响应无效/)
      await assert.rejects(call('create_file', { operationId: 'blocked', path: 'unsafe.txt', content: 'unsafe' }))
    }, error => { agentError = error })
    await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
    assert(f.calls.some(call => call.route === 'recovery/confirm-stop'))
    await assert.rejects(readFile(path.join(f.project, 'unsafe.txt')))
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('reads from the same source accumulate as one adoption', async () => {
  const f = await fixture()
  let agentError
  let completed = false
  let reviewOperationId
  const first = { path: 'a.txt', state: 'PRESENT', contentVersion: versionOf('one') }
  const edited = { path: 'a.txt', state: 'PRESENT', contentVersion: versionOf('ONE') }
  const second = { path: 'b.txt', state: 'PRESENT', contentVersion: versionOf('two') }
  const traceSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [first.path, second.path], artifactObservations: [first, second],
      traceOperationIds: { 'a.txt': 'trace-a', 'b.txt': 'trace-b' } } }
  f.setClaimPrerequisites([traceSource])
  const reviews = []
  try {
    await writeFile(path.join(f.project, first.path), 'one')
    await writeFile(path.join(f.project, second.path), 'two')
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = completed ? 'COMPLETE' : 'READY'
        return reply({ state, tasks: [taskView({ taskId: 3, title: '任务', inputRevision: 1, state,
          prerequisites: [traceSource] })] })
      }
      if (route === 'attempts/prerequisites/review') {
        reviews.push(payload.review.observations)
        reviewOperationId = payload.command.operationId
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/complete') {
        assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId, observations: [edited, second] }])
        completed = true
      }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      const read = await call('read_file', { path: first.path })
      await call('apply_patch', { operationId: 'patch-source-a', path: read.path, version: read.version,
        oldText: 'one', newText: 'ONE' })
      await writeFile(path.join(f.project, first.path), 'external')
      await assert.rejects(call('read_file', { path: second.path }), /前置成果已变化/)
      await writeFile(path.join(f.project, first.path), 'ONE')
      await call('read_file', { path: second.path })
      await call('deliver', { artifactRefs: [], conditions: [{ condition: '资料已读取', result: '通过', evidenceRefs: [] }] })
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
    assert.deepEqual(reviews, [[first], [edited, second]])
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('adopted source can be edited through its own published version chain', async () => {
  const f = await fixture()
  let agentError
  let completed = false
  let reviewOperationId
  const originalFact = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('before') }
  const changedFact = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('after') }
  const traceSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [originalFact.path], artifactObservations: [originalFact], traceOperationIds: { 'source.txt': 'trace-source' } } }
  f.setClaimPrerequisites([traceSource])
  try {
    await writeFile(path.join(f.project, originalFact.path), 'before')
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = completed ? 'COMPLETE' : 'READY'
        return reply({ state, tasks: [taskView({ taskId: 3, title: '任务', inputRevision: 1, state,
          prerequisites: [traceSource] })] })
      }
      if (route === 'attempts/prerequisites/review') {
        assert.deepEqual(payload.review.observations, [originalFact])
        reviewOperationId = payload.command.operationId
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/checkpoint') {
        assert.deepEqual(payload.checkpoint.artifactObservations, [changedFact])
        assert.equal(payload.checkpoint.previousContentVersion, originalFact.contentVersion)
      }
      if (route === 'attempts/complete') {
        assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId, observations: [changedFact] }])
        completed = true
      }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      const file = await call('read_file', { path: originalFact.path })
      await call('apply_patch', { operationId: 'own-patch', path: file.path, version: file.version,
        oldText: 'before', newText: 'after' })
      const delivery = { artifactRefs: ['source.txt'],
        conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['source.txt'] }] }
      await writeFile(path.join(f.project, originalFact.path), 'external change')
      await assert.rejects(call('deliver', delivery), /前置成果已变化/)
      await writeFile(path.join(f.project, originalFact.path), 'after')
      await call('deliver', delivery)
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('adopted source remains valid after the same attempt renames it', async () => {
  const f = await fixture()
  let agentError
  let completed = false
  let reviewOperationId
  const original = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('before') }
  const renamed = { ...original, path: 'renamed.txt' }
  const traceSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [original.path], artifactObservations: [original], traceOperationIds: { 'source.txt': 'trace-source' } } }
  f.setClaimPrerequisites([traceSource])
  try {
    await writeFile(path.join(f.project, original.path), 'before')
    const callService = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = completed ? 'COMPLETE' : 'READY'
        return reply({ state, tasks: [taskView({ taskId: 3, title: '任务', inputRevision: 1, state,
          prerequisites: [traceSource] })] })
      }
      if (route === 'attempts/prerequisites/review') {
        assert.deepEqual(payload.review.observations, [original])
        reviewOperationId = payload.command.operationId
        return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      }
      if (route === 'attempts/checkpoint') {
        assert.equal(payload.checkpoint.previousPath, original.path)
        assert.equal(payload.checkpoint.previousContentVersion, original.contentVersion)
        assert.deepEqual(payload.checkpoint.artifactObservations, [renamed])
      }
      if (route === 'attempts/complete') {
        assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId, observations: [renamed] }])
        completed = true
      }
      return callService(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      const file = await call('read_file', { path: original.path })
      await call('rename_file', { operationId: 'rename-source', from: file.path, to: renamed.path, version: file.version })
      await writeFile(path.join(f.project, renamed.path), 'external')
      const delivery = { artifactRefs: [renamed.path], conditions: [{ condition: '成果', result: '通过', evidenceRefs: [renamed.path] }] }
      await assert.rejects(call('deliver', delivery), /前置成果已变化/)
      await writeFile(path.join(f.project, renamed.path), 'before')
      await call('deliver', delivery)
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('an external edit before an own patch does not form a valid source chain', async () => {
  const f = await fixture()
  let agentError
  const originalFact = { path: 'source.txt', state: 'PRESENT', contentVersion: versionOf('before') }
  const traceSource = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: [originalFact.path], artifactObservations: [originalFact], traceOperationIds: { 'source.txt': 'trace-source' } } }
  f.setClaimPrerequisites([traceSource])
  try {
    await writeFile(path.join(f.project, originalFact.path), 'before')
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') return reply({ state: 'READY', tasks: [taskView({ taskId: 3,
        title: '任务', inputRevision: 1, state: 'READY', prerequisites: [traceSource] })] })
      if (route === 'attempts/prerequisites/review') return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      await call('read_file', { path: originalFact.path })
      await writeFile(path.join(f.project, originalFact.path), 'external')
      const edited = await call('read_file', { path: originalFact.path })
      await call('apply_patch', { operationId: 'own-patch', path: edited.path, version: edited.version,
        oldText: 'external', newText: 'after' })
      await assert.rejects(call('deliver', { artifactRefs: ['source.txt'],
        conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['source.txt'] }] }), /前置成果已变化/)
    }, error => { agentError = error })
    await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

for (const trigger of ['change_dependency', 'review_prerequisite']) {
  test(`${trigger} stops the old attempt and leaves work available in the same batch`, async () => {
    const f = await fixture()
    f.context.maxAttempts = 2
    let phase = 'dependent', agents = 0, agentError
    const interrupted = []
    try {
      const original = f.options.client.call
      const recover = f.options.client.recover
      f.options.client.call = async (route, payload) => {
        if (route === 'environment/observe') {
          const task = phase === 'dependent' ? { taskId: 3, inputRevision: 1, prerequisites: [source] }
            : { taskId: 9, inputRevision: 2, prerequisites: [] }
          const state = phase === 'complete' ? 'COMPLETE' : phase === 'stopping' ? 'STOPPING_WAIT' : 'READY'
          return reply({ state, tasks: [taskView({ ...task, state })] })
        }
        if (route === 'tasks/claim') return reply({ accepted: true, claimToken: `claim-secret-${payload.taskId}`,
          attemptId: payload.taskId + 100, inputRevision: payload.inputRevision,
          prerequisites: phase === 'dependent' ? [source] : [], leaseUntil: new Date(Date.now() + 120000).toISOString() })
        if (route === 'attempts/dependencies/change') {
          if (payload.change.prerequisiteTaskId === 3) throw new HostError(400, 'SERVER_REJECTED', 'service validation')
          assert.equal(payload.change.prerequisiteTaskId, 9)
          phase = 'stopping'
          return reply({ accepted: true, changed: true, outcome: 'RECORDED', stopRequested: true })
        }
        if (route === 'attempts/prerequisites/review') {
          assert.deepEqual(payload.review.observations, [{ path: 'missing.txt', state: 'MISSING' }])
          phase = 'stopping'
          return reply({ accepted: true, outcome: 'REWORK_REQUESTED', stopRequested: true })
        }
        if (route === 'attempts/complete') {
          assert.equal(payload.taskId, 9)
          phase = 'complete'
        }
        return original(route, payload)
      }
      f.options.client.recover = async (route, payload) => {
        if (route === 'confirm-stop') {
          assert.equal(payload.taskId, 3)
          phase = 'upstream'
        }
        return recover(route, payload)
      }
      const createAgent = async ({ onToolCall }) => {
        const number = ++agents
        return fakeAgent(onToolCall, async call => {
          if (number === 1) {
            await call('claim', { taskId: 3, inputRevision: 1 })
            if (trigger === 'change_dependency') {
              const change = { action: 'ADD', prerequisiteTaskId: 3, missing: '成果缺失', reason: '需交付', evidence: '缺失文件' }
              await assert.rejects(call(trigger, change), /成环/)
              await call(trigger, { ...change, prerequisiteTaskId: 9 })
            } else {
              await call(trigger, { ...reviewSource, decision: 'REQUEST_REWORK', finding: '产物缺失',
                evidence: '共享目录缺少已交付的成果', paths: ['missing.txt'] })
            }
            await assert.rejects(call('create_file', { path: 'after-stop.txt', content: 'bad', operationId: 'after-stop' }))
          } else {
            await call('claim', { taskId: 9, inputRevision: 2 })
            await call('create_file', { path: 'missing.txt', content: 'repaired', operationId: 'upstream-fix' })
            await call('deliver', { artifactRefs: ['missing.txt'], conditions: [{ condition: '补全成果', result: '通过', evidenceRefs: ['missing.txt'] }] })
          }
        }, error => { agentError = error }, () => interrupted.push(number))
      }
      const result = await runPool({ ...f.options, createAgent })
      assert.ifError(agentError)
      assert.deepEqual(result, { state: 'COMPLETE' })
      assert.deepEqual(interrupted, [1])
      assert.equal(await readFile(path.join(f.project, 'missing.txt'), 'utf8'), 'repaired')
    } finally { await rm(f.base, { recursive: true, force: true }) }
  })
}

test('split_task stops the parent attempt, adds the granted budget and lets later attempts claim the subtasks', async () => {
  const f = await fixture()
  f.context.maxAttempts = 1
  let phase = 'parent', agents = 0, agentError, splitPayload
  const interrupted = []
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const task = phase === 'parent' ? { taskId: 3, inputRevision: 1, prerequisites: [] } : { taskId: 20, inputRevision: 1, prerequisites: [] }
        const state = phase === 'complete' ? 'COMPLETE' : phase === 'stopping' ? 'STOPPING_WAIT' : 'READY'
        return reply({ state, tasks: [taskView({ ...task, state })] })
      }
      if (route === 'tasks/claim') return reply({ accepted: true, claimToken: `claim-secret-${payload.taskId}`,
        attemptId: payload.taskId + 100, inputRevision: payload.inputRevision, prerequisites: [],
        leaseUntil: new Date(Date.now() + 120000).toISOString() })
      if (route === 'attempts/split') {
        splitPayload = payload.split
        phase = 'stopping'
        return reply({ accepted: true, outcome: 'SPLIT', stopRequested: true, addedAttempts: 2,
          subtasks: [{ taskId: 20, taskKey: 'T-20', title: '接口' }, { taskId: 21, taskKey: 'T-21', title: '页面' }] })
      }
      if (route === 'attempts/complete') { phase = 'complete'; return reply({ accepted: true }) }
      return original(route, payload)
    }
    const recover = f.options.client.recover
    f.options.client.recover = async (route, payload) => {
      if (route === 'confirm-stop' && phase === 'stopping') phase = 'children'
      return recover(route, payload)
    }
    const createAgent = options => {
      const number = ++agents
      return fakeAgent(options.onToolCall, async call => {
        if (number === 1) {
          await call('claim', { taskId: 3, inputRevision: 1 })
          await call('split_task', { reason: '两个独立成果', subtasks: [{ title: '接口', description: '实现接口' }, { title: '页面' }] })
          await assert.rejects(call('create_file', { path: 'after-split.txt', content: 'bad', operationId: 'after-split' }))
        } else {
          await call('claim', { taskId: 20, inputRevision: 1 })
          await call('deliver', { artifactRefs: [], conditions: [{ condition: '接口完成', result: '通过', evidenceRefs: [] }] })
        }
      }, error => { agentError = error }, () => interrupted.push(number))
    }
    const result = await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.deepEqual(result, { state: 'COMPLETE' })
    assert.deepEqual(splitPayload.subtasks.map(item => item.title), ['接口', '页面'])
    assert.equal(agents, 2)
    assert.equal(f.store.size, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('an incorrect dependency can be withdrawn only after the originating attempt stops', async () => {
  const f = await fixture()
  f.context.maxAttempts = 2
  let phase = 'ready', agents = 0, agentError, added, originalProof
  const finding = { missing: '配置', reason: '配置属于当前目标', evidence: '项目内已有配置来源' }
  try {
    const original = f.options.client.call
    const recover = f.options.client.recover
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') {
        const state = { ready: 'READY', stopping: 'STOPPING_WAIT', waiting: 'DEPENDENCY_WAIT', withdrawn: 'READY', complete: 'COMPLETE' }[phase]
        const inputRevision = { ready: 1, stopping: 2, waiting: 2, withdrawn: 3, complete: 3 }[phase]
        return reply({ state, tasks: [taskView({ taskId: 3, inputRevision, state, dependencyFacts: added ? [added] : [] })] })
      }
      if (route === 'tasks/claim') {
        assert.ok(['ready', 'withdrawn'].includes(phase))
        return reply({ accepted: true, claimToken: `claim-secret-${payload.inputRevision}`,
          attemptId: 100 + payload.inputRevision, inputRevision: payload.inputRevision, prerequisites: [],
          leaseUntil: new Date(Date.now() + 120000).toISOString() })
      }
      if (route === 'attempts/dependencies/change') {
        if (payload.change.action === 'ADD') {
          originalProof = payload.command
          added = { operationId: originalProof.operationId, attemptId: originalProof.attemptId,
            change: payload.change, changed: true, accepted: true }
          phase = 'stopping'
          return reply({ accepted: true, changed: true, stopRequested: true, inputRevision: 2 })
        }
        assert.equal(phase, 'waiting')
        assert.deepEqual(payload.change, { ...finding, action: 'REMOVE', prerequisiteTaskId: 9 })
        assert.equal(payload.command.inputRevision, 2)
        for (const field of ['attemptId', 'claimToken', 'hostInstanceId', 'agentInstanceId'])
          assert.equal(payload.command[field], originalProof[field])
        assert.notEqual(payload.command.operationId, originalProof.operationId)
        phase = 'withdrawn'
        return reply({ accepted: true, changed: true, stopRequested: false, retracted: true, inputRevision: 3 })
      }
      if (route === 'attempts/complete') phase = 'complete'
      return original(route, payload)
    }
    f.options.client.recover = async (route, payload) => {
      if (route === 'confirm-stop') {
        assert.equal(phase, 'stopping')
        phase = 'waiting'
      }
      return recover(route, payload)
    }
    const createAgent = async ({ onToolCall }) => {
      const number = ++agents
      return fakeAgent(onToolCall, async call => {
        if (number === 1) {
          await call('claim', { taskId: 3, inputRevision: 1 })
          await assert.rejects(call('retract_dependency', { relationOperationId: 'unknown', ...finding }), /没有可撤回/)
          await call('change_dependency', { action: 'ADD', prerequisiteTaskId: 9,
            missing: '配置', reason: '待上游交付', evidence: '当前目录无配置' })
        } else if (number === 2) {
          const observation = await call('observe', {})
          const { retractableDependency } = observation
          assert.deepEqual(retractableDependency, { taskId: 3, prerequisiteTaskId: 9, relationOperationId: added.operationId })
          assert.doesNotMatch(JSON.stringify(observation), /claim-secret/)
          await assert.rejects(call('claim', { taskId: 3, inputRevision: 2 }))
          await assert.rejects(call('create_file', { path: 'unclaimed.txt', content: 'blocked', operationId: 'no-write' }))
          await call('retract_dependency', { relationOperationId: retractableDependency.relationOperationId, ...finding })
        } else {
          await call('claim', { taskId: 3, inputRevision: 3 })
          await call('deliver', { artifactRefs: [], conditions: [{ condition: '核对配置', result: '通过', evidenceRefs: [] }] })
        }
      }, error => { agentError = error })
    }
    const result = await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.deepEqual(result, { state: 'COMPLETE' })
    assert.equal(agents, 3)
    assert.equal(f.store.size, 0)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

for (const mutation of ['source', 'project-root']) {
  test(`delivery checks the ${mutation} again after the model turn closes`, async () => {
    const f = await fixture()
    f.setClaimPrerequisites(mutation === 'source' ? [source] : [])
    let agentError
    try {
      if (mutation === 'source') await writeFile(path.join(f.project, 'source.txt'), 'original')
      const original = f.options.client.call
      f.options.client.call = async (route, payload) => {
        if (route === 'environment/observe') return reply({ state: 'READY', tasks: [taskView({ taskId: 3, inputRevision: 1,
          state: 'READY', prerequisites: mutation === 'source' ? [source] : [] })] })
        if (route === 'attempts/prerequisites/review') return reply({ accepted: true, outcome: 'ADOPT', stopRequested: false })
        return original(route, payload)
      }
      const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
        await call('claim', { taskId: 3, inputRevision: 1 })
        if (mutation === 'source') {
          await call('read_file', { path: 'source.txt' })
          await call('review_prerequisite', { ...reviewSource, decision: 'ADOPT', finding: '有效',
            evidence: '读取来源', paths: ['source.txt'] })
        }
        await call('deliver', { artifactRefs: [], conditions: [{ condition: '检查', result: '通过', evidenceRefs: [] }] })
        if (mutation === 'source') await writeFile(path.join(f.project, 'source.txt'), 'changed')
        else {
          await rename(f.project, path.join(f.base, 'old-project'))
          await mkdir(f.project)
        }
      }, error => { agentError = error })
      const result = await runPool({ ...f.options, createAgent })
      assert.ifError(agentError)
      assert.deepEqual(result, { state: mutation === 'source' ? 'BUDGET_EXHAUSTED' : 'HOST_STOPPED' })
      assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
      assert.ok(f.calls.some(call => call.route === 'recovery/confirm-stop'))
    } finally { await rm(f.base, { recursive: true, force: true }) }
  })
}

test('ambiguous claim keeps its attempt recovery metadata', async () => {
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
    assert.equal(f.store.get('batch:batch-1:1'), 'batch-secret')
    const { RecoveryJournal } = await import('../src/recovery.mjs')
    const records = await new RecoveryJournal(f.state).list('host-1', 'batch-1', 1)
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
          await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '成果' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
        }
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    f.context.maxSlots = 2
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
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
      f.calls.push({ route, payload })
      renewStarted(); await renewal
      return { result: { accepted: true, data: { leaseUntil: new Date(Date.now() + 120000).toISOString() } }, serverTime: new Date().toISOString(), requestStartedAt: performance.now() }
    }
    let completeTurn
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => { completeTurn = resolve })
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '成果' })
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
    assert(f.calls.findIndex(call => call.route === 'batches/renew')
      < f.calls.findIndex(call => call.route === 'attempts/renew'))
  } finally { t.mock.timers.reset(); await rm(f.base, { recursive: true, force: true }) }
})

test('a rejected batch acceptance keeps the recovery credential without starting a model', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'batches/accept') throw new HostError(409, 'SERVER_REJECTED', '批次已停止')
      return original(route, payload)
    }
    await assert.rejects(runPool({ ...f.options, createAgent: () => { throw new Error('must not start') } }))
    assert.equal((await readBatchRun({ ...f.options, projectDirectory: f.project })).phase, 'prepared')
    assert.equal(f.store.get('batch-recovery:batch-1:1'), 'recovery-secret')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('an unknown accept outcome preserves the root even with no attempt journal', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'batches/accept') throw new Error('response lost')
      return original(route, payload)
    }
    await assert.rejects(runPool(f.options))
    const owner = await readBatchRun({ ...f.options, projectDirectory: f.project })
    assert.equal(owner.batchId, 'batch-1')
    assert.equal(owner.runEpoch, 1)
    assert.equal(owner.phase, 'prepared')
    assert.equal(f.calls.some(call => call.route === 'recovery/finish'), false)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a separate batch can run while a previous batch on the same root awaits recovery', async () => {
  const f = await fixture()
  try {
    const rootConfig = { ...f.options, projectDirectory: f.project, batchId: 'previous-batch' }
    const prior = await createBatchRun(rootConfig)
    await prior.reserve(1); await prior.prepared(); await prior.accepted()
    const child = spawn(process.execPath, ['-e', '']); await once(child, 'exit')
    const runRecord = path.join(f.state, 'runs', 'previous-batch', '1.json')
    const owner = JSON.parse(await readFile(runRecord, 'utf8')); owner.processId = child.pid
    await writeFile(runRecord, JSON.stringify(owner))
    f.store.set('batch:previous-batch:1', 'previous-capability')
    const normalRecover = f.options.client.recover
    f.options.client.recover = async (route, body) => {
      if (body.batchToken === 'previous-capability') {
        assert.equal(route, 'finish')
        assert.equal((await readBatchRun(rootConfig)).batchId, 'previous-batch')
        f.calls.push({ route: 'previous-finish', payload: body })
        return { result: { batchId: 'previous-batch', lifecycle: 'ended' } }
      }
      return normalRecover(route, body)
    }
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => route === 'environment/observe'
      ? { result: { state: 'COMPLETE', tasks: [] } } : original(route, payload)
    assert.deepEqual(await runPool(f.options), { state: 'COMPLETE' })
    assert.equal(f.calls.filter(call => call.route === 'previous-finish').length, 0)
    assert.equal(f.store.get('batch:previous-batch:1'), 'previous-capability')
    assert.equal((await readBatchRun(rootConfig)).batchId, 'previous-batch')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a batch finish rejection retains its capability and batch journal', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => route === 'environment/observe'
      ? { result: { state: 'COMPLETE', tasks: [] } } : original(route, payload)
    f.options.client.recover = async () => { throw new Error('finish unavailable') }
    await assert.rejects(runPool(f.options))
    const owner = await readBatchRun({ ...f.options, projectDirectory: f.project })
    assert.equal(owner.batchId, 'batch-1')
    assert.equal(owner.runEpoch, 1)
    assert.equal(f.store.get('batch:batch-1:1'), 'batch-secret')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('server batch stop seals writes before the stop acknowledgement and finishes the same batch', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] })
  const f = await fixture()
  try {
    let releaseStop; const stopped = new Promise(resolve => { releaseStop = resolve })
    let sawStop; const stopRequested = new Promise(resolve => { sawStop = resolve })
    const normalCall = f.options.client.call
    f.options.client.call = async (route, body) => route === 'batches/renew'
      ? { result: { batchId: 'batch-1', desiredIntent: 'STOP' } } : normalCall(route, body)
    const normalRecover = f.options.client.recover
    f.options.client.recover = async (route, body) => {
      if (route === 'request-stop') { sawStop(); await stopped }
      return normalRecover(route, body)
    }
    let tools; let complete
    const createAgent = async ({ onToolCall }) => {
      tools = (action, payload) => onToolCall({ arguments: { action, payload } })
      return { threadId: 'thread-1', async run() {
        const completion = new Promise(resolve => { complete = resolve })
        setImmediate(async () => {
          await tools('claim', { taskId: 3, inputRevision: 1 })
          await tools('create_file', { operationId: 'write-op', path: 'result.txt', content: 'before-stop' })
          t.mock.timers.tick(10000)
        })
        return { turnId: 'turn-1', completion }
      }, async interrupt(id) { complete({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} }
    }
    const running = runPool({ ...f.options, createAgent })
    await stopRequested
    await assert.rejects(tools('create_file', { operationId: 'write-op', path: 'result.txt', content: 'after-stop' }))
    releaseStop()
    assert.deepEqual(await running, { state: 'HOST_STOPPED' })
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), 'before-stop')
    const finish = f.calls.find(call => call.route === 'recovery/finish')
    assert.equal(finish.payload.batchToken, 'batch-secret')
    assert.equal(finish.payload.reason, 'host_stopped')
    assert.equal(await readBatchRun({ ...f.options, projectDirectory: f.project }), null)
  } finally { t.mock.timers.reset(); await rm(f.base, { recursive: true, force: true }) }
})

test('an elapsed fixed deadline finalizes through recovery without a post-deadline observe', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, body) => {
      if (route === 'environment/observe') throw new HostError(403, 'SERVER_REJECTED', '预算已到期')
      const response = await original(route, body)
      if (route === 'batches/accept') response.result.deadlineAt = new Date(Date.now() - 1000).toISOString()
      return response
    }
    assert.deepEqual(await runPool(f.options), { state: 'BUDGET_EXHAUSTED' })
    assert.equal(f.calls.some(call => call.route === 'environment/observe'), false)
    assert.equal(f.calls.find(call => call.route === 'recovery/finish').payload.reason, 'budget_exhausted')
    assert.equal(await readBatchRun({ ...f.options, projectDirectory: f.project }), null)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('observe crossing the deadline is a budget stop rather than an unknown execution', async t => {
  const f = await fixture(); let now = 1000
  t.mock.method(performance, 'now', () => now)
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, body) => {
      if (route === 'environment/observe') {
        now += 10000
        throw new HostError(403, 'SERVER_REJECTED', '预算已到期')
      }
      return original(route, body)
    }
    assert.deepEqual(await runPool(f.options), { state: 'BUDGET_EXHAUSTED' })
    assert.equal(f.calls.find(call => call.route === 'recovery/finish').payload.reason, 'budget_exhausted')
    assert.equal(await readBatchRun({ ...f.options, projectDirectory: f.project }), null)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('STOP observed before batch renewal confirms attempts and finishes without freezing', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  const f = await fixture(); let stopped = false, renewals = 0
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, body) => {
      if (route === 'batches/renew') renewals++
      if (route === 'environment/observe' && stopped) throw new HostError(403, 'SERVER_REJECTED', '批次已停止')
      return original(route, body)
    }
    let complete
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => { complete = resolve })
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'before-stop' })
        stopped = true
        t.mock.timers.tick(1000)
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { complete({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'HOST_STOPPED' })
    assert.equal(renewals, 0)
    assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
    assert.equal(f.calls.filter(call => call.route === 'recovery/confirm-stop').length, 1)
    assert.equal(f.calls.find(call => call.route === 'recovery/finish').payload.reason, 'host_stopped')
    assert.equal(await readBatchRun({ ...f.options, projectDirectory: f.project }), null)
  } finally { t.mock.timers.reset(); await rm(f.base, { recursive: true, force: true }) }
})

test('checkpoint connection loss seals the active write capability', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint' && !payload.checkpoint.artifactObservations.length) throw new Error('offline')
      return original(route, payload)
    }
    let finish
    const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'before' })
        await assert.rejects(call('checkpoint', { artifactRefs: ['result.txt'] }))
        await assert.rejects(call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'after' }))
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'BUDGET_EXHAUSTED' })
    assert(f.calls.some(call => call.route === 'recovery/confirm-stop'))
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), 'before')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a trace publication failure reports the committed file and preserves its operation record', async () => {
  const f = await fixture()
  let agentError
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint' && payload.checkpoint.artifactObservations.length)
        throw new Error('offline')
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      await assert.rejects(call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'persisted' }),
        /文件已写入，痕迹发布未确认/)
      await assert.rejects(call('create_file', { operationId: 'next-op', path: 'next.txt', content: 'blocked' }))
    }, error => { agentError = error })
    await runPool({ ...f.options, createAgent })
    assert.ifError(agentError)
    assert.equal(await readFile(path.join(f.project, 'result.txt'), 'utf8'), 'persisted')
    const fact = JSON.parse(await readFile(path.join(f.state, 'file-operations', '4', 'write-op.json'), 'utf8'))
    assert.equal(fact.status, 'completed')
    assert.deepEqual(fact.artifactObservations,
      [{ path: 'result.txt', state: 'PRESENT', contentVersion: versionOf('persisted') }])
    assert.match(fact.traceOperationId, /^trace-[a-f0-9-]+$/)
    assert.equal(f.calls.some(call => call.route === 'attempts/complete'), false)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a renamed file publishes its destination and committed content version', async () => {
  const f = await fixture()
  let agentError
  try {
    await writeFile(path.join(f.project, 'draft.txt'), 'shared content')
    const createAgent = async ({ onToolCall }) => fakeAgent(onToolCall, async call => {
      await call('claim', { taskId: 3, inputRevision: 1 })
      const file = await call('read_file', { path: 'draft.txt' })
      await call('rename_file', { operationId: 'rename-op', from: file.path, to: 'final.txt', version: file.version })
      await call('deliver', { artifactRefs: ['final.txt'], conditions: [{ condition: '成果', result: '通过', evidenceRefs: ['final.txt'] }] })
    }, error => { agentError = error })
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.ifError(agentError)
    const published = f.calls.filter(call => call.route === 'attempts/checkpoint' && call.payload.checkpoint.artifactObservations.length)
    assert.equal(published.length, 1)
    assert.deepEqual(published[0].payload.checkpoint.artifactObservations,
      [{ path: 'final.txt', state: 'PRESENT', contentVersion: versionOf('shared content') }])
    assert.equal(published[0].payload.checkpoint.previousContentVersion, versionOf('shared content'))
    assert.equal(published[0].payload.checkpoint.previousPath, 'draft.txt')
    const fact = JSON.parse(await readFile(path.join(f.state, 'file-operations', '4', 'rename-op.json'), 'utf8'))
    assert.match(fact.traceOperationId, /^trace-[a-f0-9-]+$/)
    assert.equal(published[0].payload.command.operationId, fact.traceOperationId)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('stop during a pending claim sends stop after the grant arrives', async () => {
  const f = await fixture(); const abort = new AbortController()
  try {
    let release; const grant = new Promise(resolve => { release = resolve })
    let entered; const claiming = new Promise(resolve => { entered = resolve })
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'tasks/claim') { entered(); await grant; return original(route, payload) }
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
    assert.equal(f.calls.filter(call => call.route === 'recovery/request-stop').length, 1)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a locally stale claim does not block refilling a ready task', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    let observations = 0
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe' && ++observations === 2) return {
        result: { state: 'ACTIVE', tasks: [taskView({ taskId: 3, inputRevision: 1, state: 'ACTIVE' })] },
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
          await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '成果' })
          await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '结果', result: '通过', evidenceRefs: ['result.txt'] }] })
        }
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    f.context.maxSlots = 2
    assert.deepEqual(await runPool({ ...f.options, createAgent }), { state: 'COMPLETE' })
    assert.equal(turns, 2)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('a rejected checkpoint seals the active write capability', async () => {
  const f = await fixture()
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'attempts/checkpoint' && !payload.checkpoint.artifactObservations.length)
        return { result: { accepted: false, reason: 'INPUT_CHANGED' } }
      return original(route, payload)
    }
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'before' })
        await assert.rejects(call('checkpoint', { artifactRefs: ['result.txt'] }))
        await assert.rejects(call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'after' }))
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
    let finish; const completion = new Promise(resolve => { finish = resolve })
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('stop', { reason: '外部接口定义缺失，需要业务确认' })
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt(id) { finish({ threadId: 'thread-1', turn: { id, status: 'interrupted' } }) }, close() {} })
    f.context.maxAttempts = 3
    const result = await runPool({ ...f.options, createAgent })
    assert.deepEqual(result, { state: 'DECISION_REQUIRED', reason: '外部接口定义缺失，需要业务确认' })
    assert.equal(f.calls.filter(call => call.route === 'tasks/claim').length, 1)
    const stopRequests = f.calls.filter(call => call.route === 'recovery/request-stop')
    assert.equal(stopRequests.length, 1)
    assert.equal(stopRequests[0].payload.reason, '外部接口定义缺失，需要业务确认')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('dependency wait keeps the batch until the task becomes ready', async () => {
  const f = await fixture(); let ready = false; let observed
  const firstWait = new Promise(resolve => { observed = resolve })
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, body) => {
      if (route === 'environment/observe' && !ready) {
        observed()
        return { result: { state: 'DEPENDENCY_WAIT', tasks: [] } }
      }
      return original(route, body)
    }
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        await call('create_file', { operationId: 'write-op', path: 'result.txt', content: '结果' })
        await call('deliver', { artifactRefs: ['result.txt'], conditions: [{ condition: '完成', result: '通过', evidenceRefs: ['result.txt'] }] })
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, close() {} })
    const running = runPool({ ...f.options, createAgent })
    await firstWait
    assert.ok(await readBatchRun({ ...f.options, projectDirectory: f.project }))
    assert.equal(f.calls.some(item => item.route === 'recovery/finish'), false)
    ready = true
    assert.deepEqual(await running, { state: 'COMPLETE' })
    assert.equal(f.calls.filter(item => item.route === 'batches/accept').length, 1)
    assert.equal(f.calls.filter(item => item.route === 'tasks/claim').length, 1)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('stop confirms only after a stalled agent process group exits', async () => {
  const f = await fixture(); const abort = new AbortController(); let claimed, complete, releaseClose, closeEntered
  const didClaim = new Promise(resolve => { claimed = resolve })
  const closing = new Promise(resolve => { closeEntered = resolve })
  const closeGate = new Promise(resolve => { releaseClose = resolve })
  try {
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => { complete = resolve })
      setImmediate(async () => { await onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } }); claimed() })
      return { turnId: 'turn-1', completion }
    }, interrupt() { return new Promise(() => {}) }, async close() { closeEntered(); await closeGate; complete(null) } })
    const running = runPool({ ...f.options, signal: abort.signal, createAgent })
    await didClaim; abort.abort(); await closing
    assert.equal(f.calls.some(call => call.route === 'recovery/confirm-stop'), false)
    assert.equal(f.calls.some(call => call.route === 'recovery/finish'), false)
    releaseClose()
    assert.deepEqual(await running, { state: 'USER_STOPPED' })
    assert.equal(f.calls.filter(call => call.route === 'recovery/confirm-stop').length, 1)
    assert.equal(f.calls.filter(call => call.route === 'recovery/finish').length, 1)
  } finally { releaseClose(); await rm(f.base, { recursive: true, force: true }) }
})

test('server attempt budget prevents starting another agent after reconnect', async () => {
  const f = await fixture()
  try {
    f.context.attemptsUsed = 1
    let agents = 0
    const result = await runPool({ ...f.options, createAgent() { agents++; throw new Error('must not start') } })
    assert.deepEqual(result, { state: 'BUDGET_EXHAUSTED' })
    assert.equal(agents, 0)
    assert.equal(f.calls.find(call => call.route === 'recovery/finish').payload.reason, 'budget_exhausted')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('failed process group close preserves the attempt and batch journal', async () => {
  const f = await fixture()
  try {
    let proof
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        await onToolCall({ arguments: { action: 'claim', payload: { taskId: 3, inputRevision: 1 } } })
        resolve({ threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } })
      }))
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, async close() { throw new Error('group still running') } })
    assert.deepEqual(await runPool({ ...f.options, createAgent, onQuiescent: value => { proof = value } }), { state: 'RECOVERY_REQUIRED' })
    assert.equal(proof, undefined)
    assert.equal(f.calls.some(call => call.route === 'recovery/confirm-stop'), false)
    assert.equal(f.calls.some(call => call.route === 'recovery/finish'), false)
    assert.ok(await readBatchRun({ ...f.options, projectDirectory: f.project }))
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('another batch on the same host does not prevent this batch finishing', async () => {
  const f = await fixture()
  try {
    const { RecoveryJournal } = await import('../src/recovery.mjs')
    const journal = new RecoveryJournal(f.state)
    await journal.save({ projectId: 8, bindingId: 9, batchId: 'other-batch', deviceId: f.identity.deviceId,
      projectDirectory: f.project, hostInstanceId: f.identity.hostInstanceId, runEpoch: 1,
      agentInstanceId: 'other-agent', taskId: 10, inputRevision: 1, attemptId: 11,
      claimOperationId: 'other-claim', processId: process.pid, stopConfirmed: false, claimOutcome: null })
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => route === 'environment/observe'
      ? { result: { state: 'COMPLETE', tasks: [] } } : original(route, payload)
    assert.deepEqual(await runPool(f.options), { state: 'COMPLETE' })
    assert.equal(f.calls.filter(call => call.route === 'recovery/finish').length, 1)
    assert.equal((await journal.list(f.identity.hostInstanceId, 'other-batch', 1)).length, 1)
    assert.equal(await readBatchRun({ ...f.options, projectDirectory: f.project }), null)
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('empty observation keeps the batch until an explicit stop', async () => {
  const f = await fixture(); const abort = new AbortController(); let observed
  const first = new Promise(resolve => { observed = resolve })
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'environment/observe') { observed(); return { result: { state: 'EMPTY', tasks: [] } } }
      return original(route, payload)
    }
    const running = runPool({ ...f.options, signal: abort.signal })
    await first
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(f.calls.some(call => call.route === 'recovery/finish'), false)
    assert.ok(await readBatchRun({ ...f.options, projectDirectory: f.project }))
    abort.abort()
    assert.deepEqual(await running, { state: 'USER_STOPPED' })
    assert.equal(f.calls.find(call => call.route === 'recovery/finish').payload.reason, 'host_stopped')
  } finally { await rm(f.base, { recursive: true, force: true }) }
})

test('late claim after invalid completion is sealed before recovery proof', async t => {
  const f = await fixture(); let resolveClaim, markClaim, complete, closeEntered, attemptedWrite, proof
  const claimGate = new Promise(resolve => { resolveClaim = resolve })
  const claiming = new Promise(resolve => { markClaim = resolve })
  const closing = new Promise(resolve => { closeEntered = resolve })
  const timers = []; const originalInterval = globalThis.setInterval
  t.mock.method(globalThis, 'setInterval', (...args) => { const timer = originalInterval(...args); timers.push(timer); return timer })
  try {
    const original = f.options.client.call
    f.options.client.call = async (route, payload) => {
      if (route === 'tasks/claim') { markClaim(); await claimGate }
      return original(route, payload)
    }
    const createAgent = async ({ onToolCall }) => ({ threadId: 'thread-1', async run() {
      const completion = new Promise(resolve => { complete = resolve })
      setImmediate(async () => {
        const call = (action, payload) => onToolCall({ arguments: { action, payload } })
        await call('claim', { taskId: 3, inputRevision: 1 })
        try { await call('create_file', { operationId: 'write-op', path: 'result.txt', content: 'late' }) }
        catch { attemptedWrite = true }
      })
      return { turnId: 'turn-1', completion }
    }, async interrupt() {}, async close() { closeEntered() } })
    const running = runPool({ ...f.options, createAgent, onQuiescent: value => { proof = value } })
    await claiming; complete(null); await closing
    assert.equal(proof, undefined)
    resolveClaim()
    assert.deepEqual(await running, { state: 'RECOVERY_REQUIRED' })
    assert.ok(proof)
    assert.equal(attemptedWrite, true)
    assert.ok(timers.length >= 2)
    assert.ok(timers.every(timer => timer._destroyed))
    await assert.rejects(readFile(path.join(f.project, 'result.txt'), 'utf8'), { code: 'ENOENT' })
  } finally { resolveClaim(); await rm(f.base, { recursive: true, force: true }) }
})
