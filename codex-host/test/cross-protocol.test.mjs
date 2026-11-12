import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, readFile, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createCollaborationClient } from '../src/api-client.mjs'
import { runPool } from '../src/pool.mjs'

const sha256 = value => `sha256:${createHash('sha256').update(value).digest('hex')}`

test('v3 client carries missing-source facts through repair, adoption and completed delivery', {
  skip: process.platform !== 'darwin',
}, async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll149-cross-protocol-')))
  const projectDirectory = path.join(root, 'project')
  const stateDirectory = path.join(root, 'state')
  await mkdir(projectDirectory)
  await mkdir(stateDirectory)
  const credentials = new Map()
  const vault = {
    async put(key, value) { credentials.set(key, value) },
    async get(key) { return credentials.get(key) },
    async remove(key) { credentials.delete(key) },
    async removeIfPresent(key) { credentials.delete(key) },
  }
  const identity = { deviceId: 'device-149', hostInstanceId: 'host-149' }
  const source = { taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
    evidence: { artifactRefs: ['required.md'], artifactObservations: [], traceOperationIds: {},
      projectCheckpoint: 'linear-lite-snapshot:history' } }
  const sourceIdentity = { sourceTaskId: 9, sourceAttemptId: 90, sourceInputRevision: 2 }
  const missing = { path: 'required.md', state: 'MISSING' }
  const repaired = { path: 'required.md', state: 'PRESENT', contentVersion: sha256('修补成果') }
  const calls = []
  let completed = false
  let adoptedReviewOperation
  let fetchFailure
  let agentFailure
  const origin = 'https://fixture.invalid'
  const batchToken = 'fixture-batch-capability'
  const claimToken = 'fixture-claim-capability'
  const authorization = 'fixture-oauth-capability'
  const deadlineAt = new Date(Date.now() + 30000).toISOString()
  const reply = data => Response.json({ code: 200, data }, { headers: {
    'X-Linear-Lite-Host-Protocol': '3', 'X-Linear-Lite-Server-Time': new Date().toISOString(),
  } })
  const client = createCollaborationClient({
    baseUrl: origin,
    getAccessToken: async () => authorization,
    async fetchImpl(raw, options) {
      try {
        const url = new URL(raw)
        assert.equal(url.origin, origin)
        assert.ok(url.pathname.startsWith('/api/collaboration-host/v3/'))
        assert.equal(options.method, 'POST')
        assert.equal(options.redirect, 'error')
        const route = url.pathname.slice('/api/collaboration-host/v3/'.length)
        const payload = JSON.parse(options.body)
        const recovery = route.startsWith('recovery/')
        assert.equal(options.headers.Authorization, recovery ? undefined : `Bearer ${authorization}`)
        calls.push({ route, payload })
        const now = Date.now()
        if (route === 'batches/context') return reply({ batchId: 'batch-149', bindingId: 2, projectId: 1,
          deviceId: identity.deviceId, projectDirectory, maxSlots: 1, maxAttempts: 2, attemptsUsed: 0 })
        if (route === 'batches/prepare-accept') return reply({ batchId: 'batch-149', runEpoch: 1,
          recoveryToken: 'fixture-recovery-capability' })
        if (route === 'batches/accept') return reply({ batchId: 'batch-149', runEpoch: 1, batchToken, deadlineAt,
          runLeaseUntil: new Date(now + 30000).toISOString() })
        if (route === 'batches/renew') return reply({ batchId: 'batch-149', desiredIntent: 'RUN',
          runLeaseUntil: new Date(now + 30000).toISOString() })
        if (route === 'environment/observe') return reply({ state: completed ? 'COMPLETE' : 'READY',
          tasks: [{ taskId: 3, title: '处理缺失成果', description: '补齐并交付', inputRevision: 1,
            state: completed ? 'COMPLETE' : 'READY', prerequisites: [source], attempts: [],
            prerequisiteReviewFacts: [], contextFacts: [], dependencyFacts: [], prerequisiteReviewStatuses: [] }] })
        if (route === 'tasks/claim') {
          assert.equal(payload.taskId, 3)
          assert.equal(payload.batch.batchToken, batchToken)
          assert.deepEqual(payload.sourceObservations, [{ sourceAttemptId: 90, sourceInputRevision: 2,
            observations: [missing] }])
          return reply({ accepted: true, attemptId: 4, inputRevision: 1, claimToken, prerequisites: [source],
            leaseUntil: new Date(now + 20000).toISOString() })
        }
        if (route === 'documents/list') return reply({ documents: [{ id: 16, title: '项目约定' }] })
        if (route === 'documents/read') return reply({ id: 16, projectId: 1, version: 4, content: '约定内容' })
        if (route === 'attempts/adopt-context') {
          assert.deepEqual(payload.contextRefs, [{ kind: 'DOCUMENT', documentId: 16, documentVersion: 4 }])
          return reply({ accepted: true, contextRefs: payload.contextRefs })
        }
        if (route === 'attempts/checkpoint') {
          if (payload.checkpoint.artifactObservations.length) {
            assert.deepEqual(payload.checkpoint.artifactObservations, [repaired])
            assert.equal(payload.checkpoint.previousContentVersion, null)
          } else {
            assert.equal(payload.checkpoint.blockedReason, '来源成果缺失')
          }
          assert.deepEqual(payload.checkpoint.contextRefs, [{ kind: 'DOCUMENT', documentId: 16, documentVersion: 4 }])
          return reply({ accepted: true })
        }
        if (route === 'attempts/prerequisites/review') {
          assert.deepEqual(payload.review.observations,
            payload.review.decision === 'REPAIR_IN_SCOPE' ? [missing] : [repaired])
          if (payload.review.decision === 'ADOPT') adoptedReviewOperation = payload.command.operationId
          return reply({ accepted: true, outcome: payload.review.decision, stopRequested: false })
        }
        if (route === 'attempts/complete') {
          assert.equal(payload.command.claimToken, claimToken)
          assert.equal(payload.confirmation.stopped, true)
          assert.deepEqual(payload.evidence.changedPaths, ['required.md'])
          assert.deepEqual(payload.evidence.contextRefs, [{ kind: 'DOCUMENT', documentId: 16, documentVersion: 4 }])
          assert.deepEqual(payload.evidence.prerequisiteAdoptions, [{ reviewOperationId: adoptedReviewOperation,
            observations: [repaired] }])
          assert.match(payload.evidence.projectCheckpoint, /^linear-lite-snapshot:/)
          const evidence = JSON.stringify(payload.evidence)
          for (const secret of [authorization, batchToken, claimToken]) assert.ok(!evidence.includes(secret))
          completed = true
          return reply({ accepted: true })
        }
        if (route === 'recovery/finish') {
          assert.equal(payload.batchToken, batchToken)
          assert.equal(payload.reason, 'completed')
          assert.equal(payload.confirmation.stopped, true)
          return reply({ batchId: 'batch-149', lifecycle: 'ended' })
        }
        throw new Error(`unexpected fixture route ${route}`)
      } catch (error) {
        fetchFailure = error
        throw error
      }
    },
  })
  const createAgent = async ({ onToolCall }) => ({
    threadId: 'thread-149',
    async run() {
      const completion = new Promise(resolve => setImmediate(async () => {
        try {
          const call = (action, payload) => onToolCall({ arguments: { action, payload } })
          const observed = await call('observe', {})
          assert.equal(observed.state, 'READY')
          assert.deepEqual(observed.sourceObservations[0].observations, [missing])
          await call('claim', { taskId: 3, inputRevision: 1 })
          await call('list_documents', {})
          await call('read_document', { documentId: 16 })
          await call('adopt_context', { documentIds: [16], projectPaths: [] })
          await call('checkpoint', { artifactRefs: [], blockedReason: '来源成果缺失' })
          await call('review_prerequisite', { ...sourceIdentity, decision: 'REPAIR_IN_SCOPE',
            finding: '成果缺失', evidence: '宿主确认缺少文件', paths: ['required.md'] })
          await call('create_file', { path: 'required.md', content: '修补成果', operationId: 'repair-149' })
          await call('read_file', { path: 'required.md' })
          await call('review_prerequisite', { ...sourceIdentity, decision: 'ADOPT',
            finding: '成果满足当前任务', evidence: '读取修补后的文件', paths: ['required.md'] })
          await call('deliver', { conditions: [{ condition: '成果可用', result: '通过', evidenceRefs: ['required.md'] }],
            artifactRefs: ['required.md'] })
          resolve({ threadId: 'thread-149', turn: { id: 'turn-149', status: 'completed' } })
        } catch (error) {
          agentFailure = error
          resolve(null)
        }
      }))
      return { turnId: 'turn-149', completion }
    },
    async interrupt() {},
    async close() {},
  })
  try {
    const result = await runPool({ client, identity, projectId: 1, batchId: 'batch-149', stateDirectory, vault, createAgent })
    assert.ifError(fetchFailure)
    assert.ifError(agentFailure)
    assert.deepEqual(result, { state: 'COMPLETE' })
    assert.equal(await readFile(path.join(projectDirectory, 'required.md'), 'utf8'), '修补成果')
    assert.equal(credentials.size, 0)
    assert.equal(calls.filter(call => call.route === 'tasks/claim').length, 1)
    assert.equal(calls.filter(call => call.route === 'attempts/prerequisites/review').length, 2)
    assert.equal(calls.filter(call => call.route === 'attempts/complete').length, 1)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

for (const [label, makeInvalid, invalidAfterFirstRead = false] of [
  ['missing prerequisites', task => { delete task.prerequisites }],
  ['null attempts', task => { task.attempts = null }],
  ['missing context facts', task => { delete task.contextFacts }],
  ['null prerequisite review facts', task => { task.prerequisiteReviewFacts = null }],
  ['missing dependency facts', task => { delete task.dependencyFacts }],
  ['null prerequisite review statuses', task => { task.prerequisiteReviewStatuses = null }],
  ['missing nested review observations', task => {
    task.prerequisiteReviewFacts = [{ attemptId: 4, reviews: [{ review: {} }] }]
  }],
  ['missing source artifact references', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [], evidence: {} }]
  }],
  ['missing source trace observations', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [], evidence: { artifactRefs: [], traceOperationIds: {} } }]
  }],
  ['missing source trace operation mapping', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [], evidence: { artifactRefs: [], artifactObservations: [] } }]
  }],
  ['missing trace candidates', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2,
      evidence: { artifactRefs: [], artifactObservations: [], traceOperationIds: {} } }]
  }],
  ['invalid trace candidate', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [{ attemptId: 91 }],
      evidence: { artifactRefs: [], artifactObservations: [], traceOperationIds: {} } }]
  }],
  ['missing prerequisites in the refreshed observation', task => {
    task.prerequisites = [{ taskId: 9, attemptId: 90, inputRevision: 2, traceCandidates: [],
      evidence: { artifactRefs: ['result.md'], artifactObservations: [], traceOperationIds: {} } }]
  }, true],
]) {
  test(`v3 rejects ${label} before starting an agent or claiming a task`, async () => {
    const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll149-invalid-observation-')))
    const projectDirectory = path.join(root, 'project')
    const stateDirectory = path.join(root, 'state')
    await mkdir(projectDirectory)
    await mkdir(stateDirectory)
    const store = new Map()
    const vault = { async put(key, value) { store.set(key, value) }, async get(key) { return store.get(key) },
      async remove(key) { store.delete(key) }, async removeIfPresent(key) { store.delete(key) } }
    const identity = { deviceId: 'device-149', hostInstanceId: 'host-149' }
    const routes = []
    let agentsStarted = 0
    let fetchFailure
    const task = { taskId: 3, title: '待处理任务', inputRevision: 1, state: 'READY',
      prerequisites: [], attempts: [], contextFacts: [], prerequisiteReviewFacts: [],
      dependencyFacts: [], prerequisiteReviewStatuses: [] }
    makeInvalid(task)
    const deadlineAt = new Date(Date.now() + 30000).toISOString()
    let observations = 0
    const reply = data => Response.json({ code: 200, data }, { headers: {
      'X-Linear-Lite-Host-Protocol': '3', 'X-Linear-Lite-Server-Time': new Date().toISOString(),
    } })
    const client = createCollaborationClient({
      baseUrl: 'https://fixture.invalid',
      getAccessToken: async () => 'fixture-oauth',
      async fetchImpl(raw) {
        try {
          const route = new URL(raw).pathname
          routes.push(route)
          const prefix = '/api/collaboration-host/v3/'
          assert.ok(route.startsWith(prefix))
          switch (route.slice(prefix.length)) {
            case 'batches/context': return reply({ batchId: 'batch-149', bindingId: 2, projectId: 1,
              deviceId: identity.deviceId, projectDirectory, maxSlots: 1, maxAttempts: 1, attemptsUsed: 0 })
            case 'batches/prepare-accept': return reply({ batchId: 'batch-149', runEpoch: 1, recoveryToken: 'fixture-recovery' })
            case 'batches/accept': return reply({ batchId: 'batch-149', runEpoch: 1, batchToken: 'fixture-batch', deadlineAt })
            case 'environment/observe':
              observations++
              if (invalidAfterFirstRead && observations === 2) delete task.prerequisites
              return reply({ state: 'READY', tasks: [task] })
            default: throw new Error(`unexpected fixture route ${route}`)
          }
        } catch (error) {
          fetchFailure = error
          throw error
        }
      },
    })
    try {
      await assert.rejects(runPool({ client, identity, projectId: 1, batchId: 'batch-149', stateDirectory, vault,
        createAgent: async () => { agentsStarted++; throw new Error('agent must not start') } }), /协作批次未完成/)
      assert.ifError(fetchFailure)
      assert.equal(agentsStarted, 0)
      assert.ok(routes.some(route => route.endsWith('/environment/observe')))
      assert.equal(observations, invalidAfterFirstRead ? 2 : 1)
      assert.ok(!routes.some(route => route.endsWith('/tasks/claim')))
      assert.equal(store.get('batch:batch-149:1'), 'fixture-batch')
      assert.equal(store.get('batch-recovery:batch-149:1'), 'fixture-recovery')
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  })
}
