import { randomUUID } from 'node:crypto'
import { performance } from 'node:perf_hooks'
import { createAppServer } from './app-server.mjs'
import { createWriteGate } from './executor.mjs'
import { createArtifactObserver, readProjectRootIdentity, requireProjectRootIdentity } from './artifact-observation.mjs'
import { createCheckpoint } from './checkpoint.mjs'
import { HOST_ACTION_PROMPT, validateHostAction } from './host-actions.mjs'
import { sourcePlans, readCurrentFacts, sourceFacts, projectContextFacts, contextPaths } from './collaboration-observations.mjs'
import { leaseDeadline, budgetDeadline } from './api-client.mjs'
import { createBatchRun, readBatchRun } from './batch-journal.mjs'
import { RecoveryJournal, recoverBatch, recoverLocalAttempt } from './recovery.mjs'
import { ProcessGroupJournal } from './process-groups.mjs'
import { createLocalRecoveryProof, createLocalAttemptProof } from './recovery-proof.mjs'
import { HostError } from './device.mjs'

const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const operation = prefix => `${prefix}-${randomUUID()}`
const actionRejections = new Map([
  ['documents/read', '文档不可用，请重新查看项目文档'],
  ['attempts/adopt-context', '上下文引用无效或文档版本已变化，请重读后采用'],
  ['attempts/dependencies/change', '关系无效、成环或任务状态已变化，请检查缺失依据和来源后重新观察'],
  ['attempts/prerequisites/review', '复核内容无效或前置交付已变化，请重新观察后复核'],
  ['attempts/checkpoint', '检查点无效，请检查成果路径和采用事实'],
])
const controlRejection = error => error instanceof HostError
  && (['AUTH_REQUIRED', 'PROJECT_ROOT_CHANGED', 'HOST_PROTOCOL_MISMATCH'].includes(error.code)
      || error.code === 'SERVER_REJECTED' && [401, 403, 409].includes(error.status))
const actionRejected = message => new HostError(409, 'ACTION_REJECTED', message)
const invalidSource = source => !source || !Number.isSafeInteger(source.attemptId) || source.attemptId <= 0
  || !Number.isSafeInteger(source.inputRevision) || source.inputRevision <= 0
  || !Array.isArray(source.evidence?.artifactRefs) || !Array.isArray(source.evidence?.artifactObservations)
  || !source.evidence.traceOperationIds || typeof source.evidence.traceOperationIds !== 'object'
  || Array.isArray(source.evidence.traceOperationIds)
const invalidPrerequisite = source => invalidSource(source) || !Number.isSafeInteger(source.taskId) || source.taskId <= 0
  || !Array.isArray(source.traceCandidates) || source.traceCandidates.some(invalidSource)
const command = slot => ({ attemptId: slot.record.attemptId, claimToken: slot.token,
  inputRevision: slot.record.inputRevision, hostInstanceId: slot.record.hostInstanceId,
  agentInstanceId: slot.record.agentInstanceId, operationId: operation('op') })
const stopCommand = slot => { const c = command(slot); delete c.inputRevision; return c }
const beijingLocal = (time = new Date()) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
  day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(time).replace(' ', 'T')

/** 每个批次具有独立运行记录；领取和写入由 Agent 触发。 */
export async function runPool({ client, identity, projectId, batchId,
  stateDirectory, vault, signal, createAgent = createAppServer, onQuiescent }) {
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || typeof batchId !== 'string'
      || !batchId || batchId.length > 36)
    throw new Error('执行批次参数无效')
  if (signal?.aborted) throw new Error('宿主正在停止')
  const context = (await client.call('batches/context', { projectId, batchId })).result
  if (!context || context.batchId !== batchId || context.projectId !== projectId || context.deviceId !== identity.deviceId
      || !Number.isSafeInteger(context.bindingId) || context.bindingId <= 0
      || !Number.isSafeInteger(context.maxSlots) || context.maxSlots < 1
      || !Number.isSafeInteger(context.maxAttempts) || context.maxAttempts < 1
      || !Number.isSafeInteger(context.attemptsUsed) || context.attemptsUsed < 0)
    throw new Error('项目设备绑定无效')
  const { maxSlots } = context
  let maxAttempts = context.maxAttempts
  const rootConfig = { stateDirectory, identity, projectId, batchId, projectDirectory: context.projectDirectory }
  const journal = new RecoveryJournal(stateDirectory)
  const processJournal = new ProcessGroupJournal(stateDirectory)
  if (await readBatchRun(rootConfig)) {
    await recoverBatch({ ...rootConfig, client, vault })
  }
  const root = await createBatchRun(rootConfig)
  if (root.projectDirectory !== context.projectDirectory) {
    await root.release()
    throw new Error('项目物理目录与固定绑定不匹配')
  }
  let projectRootIdentity
  try { projectRootIdentity = await readProjectRootIdentity(root.projectDirectory) }
  catch (error) { await root.release(); throw error }
  if (signal?.aborted) {
    await root.release()
    throw new Error('宿主正在停止')
  }
  let frozen = false
  let poolFailed = false
  let batch
  let issued
  let batchHeartbeat
  let batchRenewal
  let batchStopped = false
  let ownerSnapshot
  let allSlotsQuiescent = true
  let hardOutstanding = 0
  const localRecoveries = new Map()
  let finishReason = 'host_stopped'
  const slots = new Set()
  const claimedTaskIds = new Set()
  const stoppedRelations = new Map()
  let started = context.attemptsUsed
  let reserved = 0
  let countWithoutClaim = 0
  let decisionReason = null
  let end
  const secrets = []
  const knownCredentials = () => [...secrets, ...client.knownCredentials()]
  function credentialFree(value) {
    const encoded = JSON.stringify(value)
    if (knownCredentials().some(secret => encoded.includes(secret))) throw new Error('宿主操作包含凭据')
    return value
  }
  const openObserver = () => createArtifactObserver({
    projectDirectory: root.projectDirectory, projectRootIdentity, stateDirectory, knownCredentials: knownCredentials(),
  })
  function closeAgent(slot) {
    if (!slot.closingAgent) slot.closingAgent = Promise.resolve().then(() => slot.agent.close())
    return slot.closingAgent
  }
  let stoppedByUser = false
  let abortHandler
  function markSlotFailure(slot) { frozen = true; slot.recoveryNeeded = true }
  function refreshFrozen() {
    frozen = poolFailed || !allSlotsQuiescent || hardOutstanding > 0 || localRecoveries.size > 0
      || [...slots].some(slot => slot.recoveryNeeded)
  }
  function retryLocalRecovery(entry) {
    if (entry.inFlight) return
    entry.inFlight = recoverLocalAttempt({ stateDirectory, identity, client, vault,
      owner: ownerSnapshot, record: entry.record, proof: entry.proof }).then(result => {
      entry.record = result.record
      if (!result.cleaned) {
        entry.proof = createLocalAttemptProof(ownerSnapshot, result.record, identity)
        return
      }
      localRecoveries.delete(entry.record.agentInstanceId)
      claimedTaskIds.delete(entry.record.taskId)
      if (entry.provisional && ['FENCED', 'REJECTED'].includes(entry.record.claimOutcome)) started--
    }).catch(() => {}).finally(() => { entry.inFlight = null; refreshFrozen() })
  }
  try {
    const prepared = (await client.call('batches/prepare-accept', { projectId, batchId, hostInstanceId: identity.hostInstanceId })).result
    if (prepared?.batchId !== batchId || !prepared.recoveryToken || !Number.isSafeInteger(prepared.runEpoch) || prepared.runEpoch <= 0)
      throw new Error('批次准备响应无效')
    await root.reserve(prepared.runEpoch)
    await vault.put(`batch-recovery:${batchId}:${prepared.runEpoch}`, prepared.recoveryToken)
    secrets.push(prepared.recoveryToken)
    await root.prepared()
    const accepted = await client.call('batches/accept', { projectId, batchId, hostInstanceId: identity.hostInstanceId,
      recoveryToken: prepared.recoveryToken })
    issued = accepted.result
    if (issued?.batchId !== batchId || !issued.batchToken || !Number.isSafeInteger(issued.runEpoch) || issued.runEpoch !== prepared.runEpoch)
      throw new Error('批次接受响应无效')
    await vault.put(`batch:${batchId}:${prepared.runEpoch}`, issued.batchToken)
    await root.accepted()
    ownerSnapshot = await readBatchRun(rootConfig)
    secrets.push(issued.batchToken)
    end = budgetDeadline({ deadlineAt: issued.deadlineAt, serverTime: accepted.serverTime,
      requestStartedAt: accepted.requestStartedAt, responseReceivedAt: accepted.responseReceivedAt })
    batch = { batchToken: issued.batchToken, bindingId: context.bindingId, deviceId: identity.deviceId,
      projectDirectory: root.projectDirectory }
    const stopCommon = slot => ({ batchToken: batch.batchToken, projectId, bindingId: context.bindingId, deviceId: identity.deviceId,
      taskId: slot.record.taskId, command: stopCommand(slot) })
    async function checkProjectRoot() {
      try { await requireProjectRootIdentity(root.projectDirectory, projectRootIdentity); return true }
      catch {
        batchStopped = true
        for (const running of slots) void stop(running, '项目物理目录已变化')
        return false
      }
    }
    async function toolRequest(slot, route, payload) {
      try { return await client.call(route, payload) }
      catch (error) {
        if (actionRejections.has(route) && error instanceof HostError && error.code === 'SERVER_REJECTED'
            && [400, 404, 409].includes(error.status))
          throw new HostError(error.status, 'ACTION_REJECTED', actionRejections.get(route))
        if (route === 'environment/observe' && controlRejection(error)) {
          batchStopped = true
          for (const running of slots) void stop(running, '批次运行权停止')
        } else if (slot?.record) { markSlotFailure(slot); void stop(slot, '协作服务连接失败') }
        throw error
      }
    }
    async function observe(slot, includeContexts = false) {
      if (!await checkProjectRoot()) throw new HostError(409, 'PROJECT_ROOT_CHANGED', '项目物理目录已变化')
      async function environment(sourceObservations) {
        const { result } = await toolRequest(slot, 'environment/observe', { batch, sourceObservations })
        if (!Array.isArray(result?.tasks) || result.tasks.some(task =>
          !Array.isArray(task?.prerequisites) || !Array.isArray(task.attempts)
          || !Array.isArray(task.contextFacts) || !Array.isArray(task.prerequisiteReviewFacts)
          || !Array.isArray(task.dependencyFacts) || !Array.isArray(task.prerequisiteReviewStatuses)
          || task.prerequisites.some(invalidPrerequisite)
          || task.contextFacts.some(context => !Array.isArray(context?.refs))
          || task.prerequisiteReviewFacts.some(attempt => !Array.isArray(attempt?.reviews)
            || attempt.reviews.some(fact => !Array.isArray(fact?.review?.observations))))) {
          throw new Error('协作环境缺少执行事实')
        }
        return result
      }
      let view = await environment([])
      const plans = sourcePlans(view.tasks)
      const localPaths = includeContexts ? contextPaths(view.tasks, context.bindingId) : new Set()
      for (const { paths } of plans) for (const path of paths) localPaths.add(path)
      let facts = new Map()
      if (localPaths.size) {
        // Failed local probes are unknown. Only a successful read can establish a version or absence.
        const observer = await openObserver().catch(() => null)
        if (observer) facts = await readCurrentFacts(observer, [...localPaths])
      }
      const sourceObservations = sourceFacts(plans, facts)
      if (sourceObservations.some(source => source.observations.length)) view = await environment(sourceObservations)
      if (includeContexts) projectContextFacts(view.tasks, context.bindingId, facts)
      return { ...view, sourceObservations }
    }
    async function attemptRequest(slot, route, fields, operationId) {
      const proof = command(slot)
      if (operationId) proof.operationId = operationId
      const { result } = await toolRequest(slot, route, { batch, taskId: slot.record.taskId, command: proof, ...fields })
      return { result, operationId: proof.operationId }
    }
    function requireReceipt(slot, result, stopOnChange = false) {
      if (typeof result?.accepted !== 'boolean'
          || stopOnChange && result.accepted && typeof result.stopRequested !== 'boolean') {
        markSlotFailure(slot)
        void stop(slot, '协作操作回执无效')
        throw new Error('协作操作回执无效')
      }
      if (!result.accepted || stopOnChange && result.stopRequested) {
        void stop(slot, result.accepted ? '协作事实触发尝试停止' : '尝试操作遭拒')
      }
      if (!result.accepted) throw actionRejected('尝试操作遭拒，宿主将停止本次尝试')
      return credentialFree(result)
    }
    function canReviewRelation(relation, view) {
      return view.tasks.some(task => task.taskId === relation.taskId && task.state === 'DEPENDENCY_WAIT'
        && task.inputRevision === relation.inputRevision && task.dependencyFacts.some(fact =>
          fact.operationId === relation.relationOperationId && fact.attemptId === relation.proof.attemptId
          && fact.accepted && fact.changed && fact.change.action === 'ADD'
          && fact.change.prerequisiteTaskId === relation.prerequisiteTaskId))
    }
    async function retractDependency(slot, { relationOperationId, ...finding }) {
      const relation = slot.retraction
      if (!relation || relation.relationOperationId !== relationOperationId || !stoppedRelations.has(relationOperationId)
          || slot.stopRequested || signal?.aborted || performance.now() >= end)
        throw actionRejected('没有可撤回的已停止关系')
      const view = await observe(slot)
      if (slot.stopRequested || batchStopped || performance.now() >= end || !canReviewRelation(relation, view))
        throw actionRejected('关系或输入版本已变化，请重新观察')
      const { result } = await toolRequest(slot, 'attempts/dependencies/change', {
        batch, taskId: relation.taskId,
        command: { ...relation.proof, inputRevision: relation.inputRevision, operationId: operation('op') },
        change: { ...finding, action: 'REMOVE', prerequisiteTaskId: relation.prerequisiteTaskId },
      })
      const receipt = requireReceipt(slot, result, true)
      if (receipt.changed) stoppedRelations.delete(relationOperationId)
      return receipt
    }
    async function adoptContext(slot, payload) {
      const contextRefs = []
      for (const id of new Set(payload.documentIds)) {
        const ref = slot.documentReads.get(id)
        if (!ref) throw actionRejected('请在本次尝试中读取所选文档')
        contextRefs.push(ref)
      }
      const projectPaths = [...new Set(payload.projectPaths)]
      for (const path of projectPaths) {
        if (!slot.projectFileReads.has(path)) throw actionRejected('请在本次尝试中读取所选项目文件')
      }
      if (projectPaths.length) {
        const observations = await slot.gate.observeArtifacts({ paths: projectPaths })
        for (const fact of observations) {
          const ref = slot.projectFileReads.get(fact.path)
          if (fact.contentVersion !== ref.contentVersion) throw actionRejected('项目文件已变化，请重读后采用')
          contextRefs.push(ref)
        }
      }
      if (!contextRefs.length) throw actionRejected('请选择本次尝试已读取的资料')
      const { result } = await attemptRequest(slot, 'attempts/adopt-context', { contextRefs })
      const receipt = requireReceipt(slot, result)
      for (const ref of contextRefs) {
        const key = ref.kind === 'DOCUMENT' ? `DOCUMENT:${ref.documentId}` : `PROJECT_FILE:${ref.projectPath}`
        slot.adoptedRefs.set(key, ref)
      }
      return receipt
    }
    async function reviewPrerequisite(slot, { paths, ...review }) {
      const observations = paths.length ? await slot.gate.observeArtifacts({ paths: [...new Set(paths)] }) : []
      if (review.decision === 'ADOPT') {
        const adopted = slot.prerequisiteAdoptions.get(review.sourceTaskId)
        for (const fact of observations) {
          const seen = slot.visibleObservations.get(fact.path)
          const previous = adopted?.observations.find(item => ownPublishedVersion(slot, item, adopted.operationIndex)?.path === fact.path)
          if (seen && (seen.state !== fact.state || seen.contentVersion !== fact.contentVersion)
              && (!previous || !ownPublishedVersionMatches(slot, previous, fact, adopted.operationIndex)))
            throw actionRejected('前置成果已变化，请重新观察或读取后复核')
        }
      }
      const { result, operationId } = await attemptRequest(slot, 'attempts/prerequisites/review',
        { review: { ...review, observations } })
      const receipt = requireReceipt(slot, result, true)
      if (receipt.outcome === 'ADOPT') {
        slot.prerequisiteAdoptions.set(review.sourceTaskId,
          { reviewOperationId: operationId, observations, operationIndex: slot.gate.operationFacts().length })
        for (const fact of observations) slot.visibleObservations.set(fact.path, fact)
      } else slot.prerequisiteAdoptions.delete(review.sourceTaskId)
      return receipt
    }
    async function publishFileTrace(slot, fact) {
      if (!fact.artifactObservations.length) return fact
      const checkpoint = { occurredAt: beijingLocal(new Date(fact.occurredAt)),
        artifactRefs: fact.artifactObservations.map(item => item.path),
        artifactObservations: fact.artifactObservations, previousContentVersion: fact.previousContentVersion,
        previousPath: fact.previousPath,
        contextRefs: [...slot.adoptedRefs.values()] }
      try {
        const { result } = await attemptRequest(slot, 'attempts/checkpoint', { checkpoint }, fact.traceOperationId)
        requireReceipt(slot, result)
        slot.publishedTraces.add(fact.traceOperationId)
      } catch {
        markSlotFailure(slot)
        void stop(slot, '文件已写入，痕迹发布未确认')
        throw new Error('文件已写入，痕迹发布未确认')
      }
      return fact
    }
    async function adoptReadTrace(slot, file) {
      for (const source of slot.prerequisites) {
        const observations = source.evidence.artifactObservations
        if (!Array.isArray(observations)) throw new Error('协作环境缺少成果痕迹')
        if (!observations.some(item => item.path === file.path && item.state === 'PRESENT'
            && item.contentVersion === file.contentVersion)) continue
        const adopted = slot.prerequisiteAdoptions.get(source.taskId)
        if (adopted?.observations.some(item => item.path === file.path && item.contentVersion === file.contentVersion)) continue
        const paths = [...new Set([...(adopted?.observations.map(item => {
          const current = ownPublishedVersion(slot, item, adopted.operationIndex)
          if (!current) throw actionRejected('前置成果已变化，请重新复核')
          return current.path
        }) ?? []), file.path])]
        await reviewPrerequisite(slot, { sourceTaskId: source.taskId, sourceAttemptId: source.attemptId,
          sourceInputRevision: source.inputRevision, decision: 'ADOPT', finding: '已读取当前成果',
          evidence: `已读取 ${file.path} 的 ${file.contentVersion}`, paths })
      }
    }
    function ownPublishedVersion(slot, previous, operationIndex) {
      if (previous.state !== 'PRESENT') return previous
      let path = previous.path
      let version = previous.contentVersion
      for (const fact of slot.gate.operationFacts().slice(operationIndex)) {
        if (!fact.changedPaths.includes(path)) continue
        if (!['patch', 'replace', 'rename'].includes(fact.action) || !slot.publishedTraces.has(fact.traceOperationId)
            || fact.previousContentVersion !== version || fact.artifactObservations.length !== 1
            || (fact.action === 'rename' ? fact.previousPath !== path : fact.artifactObservations[0].path !== path)) return null
        path = fact.artifactObservations[0].path
        version = fact.artifactObservations[0].contentVersion
      }
      return { path, state: 'PRESENT', contentVersion: version }
    }
    function ownPublishedVersionMatches(slot, previous, current, operationIndex) {
      const expected = ownPublishedVersion(slot, previous, operationIndex)
      return expected !== null && current?.path === expected.path && current.state === expected.state
        && current.contentVersion === expected.contentVersion
    }
    async function deliveryAdoptions(slot, observeArtifacts) {
      if (slot.prerequisites.some(source => !slot.prerequisiteAdoptions.has(source.taskId)))
        throw actionRejected('交付前请复核并采用前置成果')
      const adoptions = [...slot.prerequisiteAdoptions.values()]
      const paths = new Set(adoptions.flatMap(adoption => adoption.observations.map(fact => {
        const expected = ownPublishedVersion(slot, fact, adoption.operationIndex)
        if (!expected) throw actionRejected('前置成果已变化，请重新复核')
        return expected.path
      })))
      if (!paths.size) return adoptions.map(({ reviewOperationId, observations }) => ({ reviewOperationId, observations }))
      let observations
      try { observations = await observeArtifacts({ paths: [...paths] }) }
      catch { throw actionRejected('前置成果读取待核验，请重新观察后复核') }
      const byPath = new Map(observations.map(fact => [fact.path, fact]))
      return adoptions.map(adoption => ({
        reviewOperationId: adoption.reviewOperationId,
        observations: adoption.observations.map(previous => {
          const expected = ownPublishedVersion(slot, previous, adoption.operationIndex)
          const current = expected && byPath.get(expected.path)
          if (!ownPublishedVersionMatches(slot, previous, current, adoption.operationIndex))
            throw actionRejected('前置成果已变化，请重新复核')
          return current
        }),
      }))
    }
    function stop(slot, reason) {
      if (slot.stopsClosed) return Promise.resolve()
      if (!slot.stopOperations) slot.stopOperations = new Set()
      const operation = stopSlot(slot, reason).catch(() => { markSlotFailure(slot); slot.stopFailed = true })
      slot.stopOperations.add(operation)
      void operation.finally(() => slot.stopOperations.delete(operation)).catch(() => {})
      return operation
    }
    async function stopSlot(slot, reason) {
      if (!slot.stopping) slot.stopping = (async () => {
        slot.stopRequested = true
        await slot.gate?.sealAndDrain()
      })()
      await slot.stopping
      if (slot.record?.attemptId && slot.token && slot.stopRequestAttemptId !== slot.record.attemptId) {
        slot.stopRequestAttemptId = slot.record.attemptId
        slot.stopRequestPromise = (async () => {
          try {
            const response = await client.recover('request-stop', { ...stopCommon(slot), reason })
            if (response.result?.accepted !== true) markSlotFailure(slot)
          } catch { markSlotFailure(slot) }
        })()
      }
      let closing
      if (slot.turnId && !slot.terminal && slot.interruptedTurnId !== slot.turnId) {
        slot.interruptedTurnId = slot.turnId
        // 中断回合可能没有响应，关闭受控进程组并等待退出是停止证明。
        void slot.agent.interrupt(slot.turnId).catch(() => {})
        closing = closeAgent(slot).catch(() => { markSlotFailure(slot) })
      }
      await Promise.all([slot.stopRequestPromise, closing])
    }
    async function renewBatch() {
      if (!batchRenewal) {
        const work = (async () => {
          try {
            const renewed = await client.call('batches/renew', { batchToken: batch.batchToken })
            if (renewed.result?.batchId !== batchId || renewed.result.desiredIntent !== 'RUN')
              throw new Error('批次运行权已停止')
            leaseDeadline({ leaseUntil: renewed.result.runLeaseUntil,
              serverTime: renewed.serverTime, requestStartedAt: renewed.requestStartedAt })
          } catch (error) {
            batchStopped = true
            for (const running of slots) void stop(running, '批次运行权停止')
            throw error
          }
        })()
        batchRenewal = work
        void work.finally(() => { if (batchRenewal === work) batchRenewal = null }).catch(() => {})
      }
      return batchRenewal
    }
    batchHeartbeat = setInterval(() => { if (!batchStopped) void renewBatch().catch(() => {}) }, 10000)
    async function heartbeat(slot) {
      if (!slot.record?.attemptId || slot.stopRequested || slot.renewing) return
      slot.renewing = true
      try {
        await renewBatch()
        const renewed = await client.call('attempts/renew', { batch, taskId: slot.record.taskId, command: command(slot) })
        if (renewed.result?.accepted !== true) throw new Error('租约续期遭拒')
        slot.deadline = leaseDeadline({ leaseUntil: renewed.result.data?.leaseUntil,
          serverTime: renewed.serverTime, requestStartedAt: renewed.requestStartedAt })
      } catch { if (!batchStopped) markSlotFailure(slot); await stop(slot, '租约续期失败') }
      finally { slot.renewing = false }
    }
    async function callTool(slot, params) {
      const input = validateHostAction(params?.arguments)
      const payload = credentialFree(input.payload)
      if (input.action === 'observe') {
        const view = credentialFree(await observe(slot, true))
        for (const source of view.sourceObservations) {
          for (const fact of source.observations) slot.visibleObservations.set(fact.path, fact)
        }
        if (slot.retraction && canReviewRelation(slot.retraction, view)) {
          const { taskId, prerequisiteTaskId, relationOperationId } = slot.retraction
          return { ...view, retractableDependency: { taskId, prerequisiteTaskId, relationOperationId } }
        }
        return view
      }
      if (input.action === 'retract_dependency') return retractDependency(slot, payload)
      if (input.action === 'claim') {
        if (slot.retraction || slot.record || batchStopped || decisionReason || reserved + started >= maxAttempts
            || performance.now() >= end || signal?.aborted)
          throw new Error('领取额度不可用')
        const view = await observe(slot)
        const task = view.tasks.find(t => t.taskId === payload.taskId)
        if (!task) throw new Error('任务不可领取')
        if (task.inputRevision !== payload.inputRevision || task.state !== 'READY') {
          slot.claimRejected = true
          throw new Error('任务不可领取')
        }
        if (decisionReason || reserved + started >= maxAttempts || slot.stopRequested || signal?.aborted || performance.now() >= end
            || claimedTaskIds.has(task.taskId)) throw new Error('领取额度不可用')
        claimedTaskIds.add(task.taskId)
        reserved++
        const claimOperationId = operation('claim')
        slot.record = { projectId, bindingId: context.bindingId, batchId, runEpoch: issued.runEpoch, deviceId: identity.deviceId,
          projectDirectory: root.projectDirectory, hostInstanceId: identity.hostInstanceId,
          agentInstanceId: slot.agentInstanceId, taskId: task.taskId, inputRevision: task.inputRevision,
          attemptId: null, claimOperationId, processId: process.pid, stopConfirmed: false, claimOutcome: null }
        try {
          await journal.save(slot.record)
          let grant
          started++; slot.provisional = true
          try { grant = await client.call('tasks/claim', { batch, taskId: task.taskId, inputRevision: task.inputRevision,
            hostInstanceId: identity.hostInstanceId, agentInstanceId: slot.agentInstanceId, operationId: claimOperationId,
            sourceObservations: view.sourceObservations }) }
          catch (error) { markSlotFailure(slot); throw error }
          if (typeof grant.result?.accepted !== 'boolean') {
            markSlotFailure(slot)
            throw new Error('领取响应无效')
          }
          if (grant.result.accepted === false) {
            started--; slot.provisional = false
            await journal.remove(slot.record); claimedTaskIds.delete(task.taskId); slot.record = null; slot.claimRejected = true
            return { accepted: false, reason: grant.result?.reason }
          }
          slot.token = grant.result.claimToken
          if (typeof slot.token !== 'string' || !slot.token || !Number.isSafeInteger(grant.result.attemptId)
              || grant.result.attemptId <= 0) { markSlotFailure(slot); throw new Error('领取响应无效') }
          secrets.push(slot.token)
          await vault.put(`attempt:${identity.hostInstanceId}:${slot.agentInstanceId}`, slot.token)
          slot.record.attemptId = grant.result.attemptId
          await journal.save(slot.record)
          slot.provisional = false
          if (!Array.isArray(grant.result.prerequisites) || grant.result.prerequisites.some(invalidPrerequisite)
              || grant.result.prerequisites.some(source => source.traceCandidates.length !== 0)
              || grant.result.inputRevision !== task.inputRevision) {
            markSlotFailure(slot)
            throw new Error('领取响应无效')
          }
          slot.prerequisites = grant.result.prerequisites
          slot.deadline = leaseDeadline({ leaseUntil: grant.result.leaseUntil, serverTime: grant.serverTime,
            requestStartedAt: grant.requestStartedAt })
          slot.gate = await createWriteGate({ projectDirectory: root.projectDirectory, projectRootIdentity, stateDirectory,
            attemptId: slot.record.attemptId, agentInstanceId: slot.agentInstanceId, knownCredentials: knownCredentials(), isAuthorized: () => !slot.stopRequested && performance.now() < slot.deadline })
          if (slot.stopRequested) { await slot.gate.sealAndDrain(); await stop(slot, '执行请求停止') }
          slot.heartbeat = setInterval(() => {
            if (slot.heartbeatPromise) return
            const pending = heartbeat(slot)
            slot.heartbeatPromise = pending
            void pending.finally(() => { if (slot.heartbeatPromise === pending) slot.heartbeatPromise = null })
          }, 10000)
          return { accepted: true, task: { taskId: task.taskId, title: task.title, description: task.description,
            inputRevision: task.inputRevision, prerequisites: slot.prerequisites } }
        } catch (error) { if (slot.record) markSlotFailure(slot); throw error }
        finally { reserved-- }
      }
      if (!slot.gate || slot.stopRequested || performance.now() >= slot.deadline) throw new Error('尝试不可写')
      if (input.action === 'list_files') return slot.gate.listFiles(payload)
      if (input.action === 'read_file') {
        const file = credentialFree(await slot.gate.readFile(payload))
        slot.projectFileReads.set(file.path, { kind: 'PROJECT_FILE', projectPath: file.path, contentVersion: file.contentVersion })
        slot.visibleObservations.set(file.path, { state: 'PRESENT', contentVersion: file.contentVersion })
        await adoptReadTrace(slot, file)
        return file
      }
      if (input.action === 'create_file') return publishFileTrace(slot, await slot.gate.createFile(payload))
      if (input.action === 'apply_patch') return publishFileTrace(slot, await slot.gate.applyPatch(payload))
      if (input.action === 'replace_file') return publishFileTrace(slot, await slot.gate.replaceFile(payload))
      if (input.action === 'remove_file') return slot.gate.removeFile(payload)
      if (input.action === 'rename_file') return publishFileTrace(slot, await slot.gate.renameFile(payload))
      if (input.action === 'make_directory') return slot.gate.makeDirectory(payload)
      if (input.action === 'list_documents') {
        const { result } = await attemptRequest(slot, 'documents/list', {})
        if (!Array.isArray(result?.documents)) throw new Error('文档目录回执无效')
        return result
      }
      if (input.action === 'read_document') {
        const { result } = await attemptRequest(slot, 'documents/read', payload)
        if (result?.id !== payload.documentId || result.projectId !== projectId
            || !Number.isSafeInteger(result.version) || result.version <= 0) throw new Error('文档回执无效')
        credentialFree(result)
        slot.documentReads.set(result.id, { kind: 'DOCUMENT', documentId: result.id, documentVersion: result.version })
        return result
      }
      if (input.action === 'adopt_context') return adoptContext(slot, payload)
      if (input.action === 'change_dependency') {
        const { result, operationId } = await attemptRequest(slot, 'attempts/dependencies/change', { change: payload })
        const receipt = requireReceipt(slot, result, true)
        if (payload.action === 'ADD' && receipt.changed && receipt.stopRequested && Number.isSafeInteger(receipt.inputRevision)) {
          slot.stoppedRelation = { taskId: slot.record.taskId, prerequisiteTaskId: payload.prerequisiteTaskId,
            relationOperationId: operationId, inputRevision: receipt.inputRevision, proof: stopCommand(slot), reviewed: false }
        }
        return receipt
      }
      if (input.action === 'split_task') {
        const { result } = await attemptRequest(slot, 'attempts/split', { split: payload })
        const receipt = requireReceipt(slot, result, true)
        // 服务端为每个子任务追加一次尝试预算，宿主按回执同步，避免拆分耗尽原预算。
        if (receipt.accepted && Number.isSafeInteger(receipt.addedAttempts) && receipt.addedAttempts > 0)
          maxAttempts += receipt.addedAttempts
        return receipt
      }
      if (input.action === 'review_prerequisite') return reviewPrerequisite(slot, payload)
      if (input.action === 'checkpoint') {
        const checkpoint = { ...payload, occurredAt: beijingLocal(), artifactObservations: [], previousContentVersion: null, previousPath: null,
          contextRefs: [...slot.adoptedRefs.values()] }
        const { result } = await attemptRequest(slot, 'attempts/checkpoint', { checkpoint })
        return requireReceipt(slot, result)
      }
      if (input.action === 'deliver') {
        await deliveryAdoptions(slot, input => slot.gate.observeArtifacts(input))
        slot.delivery = payload
        return { accepted: true }
      }
      if (input.action === 'stop') {
        if (!payload.reason.trim() || payload.reason.length > 2048)
          throw new Error('停止原因无效')
        decisionReason = payload.reason
        for (const running of slots) void stop(running, payload.reason)
        return { accepted: true }
      }
      throw new Error('宿主操作无效')
    }
    async function execute(slot) {
      try {
        slot.groupRecord = { projectId, batchId, deviceId: identity.deviceId, projectDirectory: root.projectDirectory,
          hostInstanceId: identity.hostInstanceId, runEpoch: issued.runEpoch, agentInstanceId: slot.agentInstanceId,
          processId: process.pid, phase: 'idle', processGroupId: null }
        await processJournal.save(slot.groupRecord)
        const mark = async (phase, processGroupId = null) => {
          const next = { ...slot.groupRecord, phase, processGroupId }
          await processJournal.save(next)
          slot.groupRecord = next
        }
        const groups = { starting: () => mark('starting'), started: pid => mark('running', pid), stopped: () => mark('idle') }
        slot.toolTail = Promise.resolve()
        slot.agent = await createAgent({ cwd: root.projectDirectory, groups, name: `Linear Lite 协作 · ${batchId}`, onToolCall: p => {
          const work = slot.toolTail.then(async () => {
            if (slot.terminal) throw new Error('回合已结束')
            return credentialFree(await callTool(slot, p))
          })
          slot.toolTail = work.catch(() => {})
          return work
        } })
        if (slot.stopRequested || signal?.aborted || performance.now() >= end) return
        const prompt = slot.retraction
          ? `${HOST_ACTION_PROMPT}\n本回合复查一条已停止尝试留下的关系。观察其事实，判断有误时撤回；回合没有领取或写入资格。`
          : HOST_ACTION_PROMPT
        const run = await slot.agent.run(prompt)
        slot.turnId = run.turnId
        if (slot.stopRequested || signal?.aborted || performance.now() >= end) await stop(slot, '执行结束')
        let event = await run.completion
        if (!event && slot.stopRequested) {
          try { await closeAgent(slot) } catch { markSlotFailure(slot); return }
          if (slot.groupRecord.phase === 'idle') event = { threadId: slot.agent.threadId,
            turn: { id: slot.turnId, status: 'interrupted' } }
        }
        if (!event || event.threadId !== slot.agent.threadId || event.turn?.id !== slot.turnId) { markSlotFailure(slot); return }
        slot.terminal = true
        await slot.toolTail
        await slot.gate?.sealAndDrain()
        clearInterval(slot.heartbeat)
        await slot.heartbeatPromise
        await closeAgent(slot)
        slot.agent = null
        if (slot.groupRecord.phase !== 'idle') throw new Error('执行进程组未确认退出')
        if (!slot.record) { if (!slot.claimRejected && !slot.retraction) countWithoutClaim++; return }
        if (!slot.record.attemptId || !slot.token) { markSlotFailure(slot); return }
        let evidence
        if (event.turn.status === 'completed' && slot.delivery && !slot.stopRequested && performance.now() < slot.deadline
            && await checkProjectRoot()) {
          const changedPaths = slot.gate.changedPaths()
          const candidate = { ...slot.delivery, changedPaths, contextRefs: [...slot.adoptedRefs.values()] }
          if (slot.delivery.artifactRefs.length) candidate.projectCheckpoint = await createCheckpoint({
            stateDirectory, projectDirectory: root.projectDirectory, projectRootIdentity,
            artifactRefs: slot.delivery.artifactRefs, changedPaths, knownCredentials: knownCredentials() })
          try {
            candidate.prerequisiteAdoptions = await deliveryAdoptions(slot, async input => (await openObserver()).observeArtifacts(input))
            evidence = candidate
          } catch (error) {
            if (!(error instanceof HostError) || error.code !== 'ACTION_REJECTED') throw error
            await stop(slot, error.message)
          }
        }
        if (evidence && await checkProjectRoot() && !slot.stopRequested && performance.now() < slot.deadline) {
          const response = await client.call('attempts/complete', { batch, taskId: slot.record.taskId,
            command: command(slot), evidence,
            confirmation: { stopped: true, detail: '宿主写能力已封存并排空，Codex 回合已结束' } })
          if (response.result?.accepted !== true) throw new Error('成果提交遭拒')
        } else {
          await stop(slot, '执行回合结束')
          const response = await client.recover('confirm-stop', { ...stopCommon(slot),
            confirmation: { stopped: true, detail: '宿主写能力已封存并排空，Codex 回合已结束' } })
          if (response.result?.accepted !== true) throw new Error('停止确认遭拒')
          if (slot.stoppedRelation) stoppedRelations.set(slot.stoppedRelation.relationOperationId, slot.stoppedRelation)
        }
        clearInterval(slot.heartbeat)
        await journal.remove(slot.record)
        claimedTaskIds.delete(slot.record.taskId)
        await vault.remove(`attempt:${identity.hostInstanceId}:${slot.agentInstanceId}`)
        slot.record = null
      } catch { markSlotFailure(slot); await stop(slot, '执行异常') }
      finally {
        // 停止接收后续工具调用；正在途中的领取可能仍会建立写入门禁。
        slot.terminal = true
        slot.stopRequested = true
        clearInterval(slot.heartbeat)
        let quiescent = true
        try { await slot.gate?.sealAndDrain() } catch { markSlotFailure(slot); quiescent = false }
        try { if (slot.agent) await closeAgent(slot) } catch { markSlotFailure(slot); quiescent = false }
        try { await slot.toolTail } catch { markSlotFailure(slot); quiescent = false }
        clearInterval(slot.heartbeat)
        try { await slot.heartbeatPromise } catch { markSlotFailure(slot); quiescent = false }
        slot.stopsClosed = true
        const stopped = await Promise.allSettled([...(slot.stopOperations ?? [])])
        if (slot.stopFailed || stopped.some(result => result.status === 'rejected')) { markSlotFailure(slot); quiescent = false }
        try { await slot.gate?.sealAndDrain() } catch { markSlotFailure(slot); quiescent = false }
        if (slot.gate && slot.gate.accepting !== false) quiescent = false
        if (slot.groupRecord?.phase !== 'idle') quiescent = false
        if (!slot.record && slot.groupRecord?.phase === 'idle') {
          try { await processJournal.removeIfPresent(slot.groupRecord) } catch { markSlotFailure(slot); quiescent = false }
        }
        if (slot.recoveryNeeded && slot.record && quiescent) {
          const proof = createLocalAttemptProof(ownerSnapshot, slot.record, identity)
          localRecoveries.set(slot.record.agentInstanceId, { record: slot.record, proof, provisional: slot.provisional, inFlight: null })
        } else if (!quiescent) {
          allSlotsQuiescent = false
          hardOutstanding++
        } else if (!slot.record) slot.recoveryNeeded = false
        slots.delete(slot)
        refreshFrozen()
      }
    }
    abortHandler = () => { stoppedByUser = true; for (const slot of slots) void stop(slot, '用户停止') }
    signal?.addEventListener('abort', abortHandler, { once: true })
    function startSlot(retraction) {
      const slot = { agentInstanceId: randomUUID(), stopRequested: false, retraction, documentReads: new Map(),
        projectFileReads: new Map(), visibleObservations: new Map(), adoptedRefs: new Map(), prerequisiteAdoptions: new Map(),
        publishedTraces: new Set() }
      slots.add(slot)
      slot.finished = execute(slot)
    }
    while (!batchStopped && !signal?.aborted && !decisionReason && performance.now() < end) {
      for (const entry of localRecoveries.values()) retryLocalRecovery(entry)
      let observation
      try { observation = await observe() }
      catch (error) {
        if (!controlRejection(error)) throw error
        batchStopped = true
        break
      }
      if (observation.state === 'COMPLETE' && slots.size === 0) break
      const readyCount = observation.tasks.filter(task => task.state === 'READY' && !claimedTaskIds.has(task.taskId)).length
      while (!batchStopped && !decisionReason && observation.state === 'READY' && slots.size + localRecoveries.size + hardOutstanding < maxSlots && started + reserved < maxAttempts && !countWithoutClaim
          && [...slots].filter(slot => !slot.record?.attemptId).length < readyCount) {
        startSlot()
      }
      if (!batchStopped && !frozen && !slots.size && !countWithoutClaim && started < maxAttempts && observation.state === 'DEPENDENCY_WAIT') {
        const relation = [...stoppedRelations.values()].find(candidate => !candidate.reviewed && canReviewRelation(candidate, observation))
        if (relation) {
          relation.reviewed = true
          startSlot(relation)
        }
      }
      if (!slots.size && localRecoveries.size === 0 && (frozen || started >= maxAttempts || countWithoutClaim
          || !['EMPTY', 'DEPENDENCY_WAIT', 'STOPPING_WAIT', 'TASK_WAIT', 'PREREQUISITE_REVIEW_WAIT', 'GOAL_AUTHORIZATION_WAIT'].includes(observation.state))) break
      await Promise.race([wait(1000), ...[...slots].map(slot => slot.finished)])
    }
    if (signal?.aborted) stoppedByUser = true
    for (const slot of slots) void stop(slot, stoppedByUser ? '用户停止' : decisionReason ?? '批次预算结束')
    const pending = Promise.all([...slots].map(slot => slot.finished))
    if (slots.size && await Promise.race([pending.then(() => false), wait(10000).then(() => true)])) {
      frozen = true
      allSlotsQuiescent = false
    }
    await Promise.allSettled([...localRecoveries.values()].map(entry => entry.inFlight).filter(Boolean))
    refreshFrozen()
    if (frozen) return { state: 'RECOVERY_REQUIRED' }
    if (batchStopped) {
      if (performance.now() >= end) { finishReason = 'budget_exhausted'; return { state: 'BUDGET_EXHAUSTED' } }
      return { state: 'HOST_STOPPED' }
    }
    if (stoppedByUser) return { state: 'USER_STOPPED' }
    if (decisionReason) { finishReason = 'needs_decision'; return { state: 'DECISION_REQUIRED', reason: decisionReason } }
    if (performance.now() >= end) { finishReason = 'budget_exhausted'; return { state: 'BUDGET_EXHAUSTED' } }
    let observation
    try { observation = await observe() }
    catch (error) {
      if (!controlRejection(error)) throw error
      if (performance.now() >= end) { finishReason = 'budget_exhausted'; return { state: 'BUDGET_EXHAUSTED' } }
      return { state: 'HOST_STOPPED' }
    }
    if (observation.state === 'COMPLETE') { finishReason = 'completed'; return { state: 'COMPLETE' } }
    if (started >= maxAttempts || performance.now() >= end) { finishReason = 'budget_exhausted'; return { state: 'BUDGET_EXHAUSTED' } }
    finishReason = 'needs_decision'
    if (countWithoutClaim) return { state: 'DECISION_REQUIRED' }
    return { state: observation.state }
  } catch {
    poolFailed = true
    frozen = true
    for (const slot of slots) {
      slot.stopRequested = true
      await slot.gate?.sealAndDrain().catch(() => {})
      if (slot.turnId && !slot.terminal) void slot.agent.interrupt(slot.turnId).catch(() => {})
      if (slot.agent) void closeAgent(slot).catch(() => {})
    }
    await Promise.race([Promise.all([...slots].map(slot => slot.finished)), wait(10000)])
    throw new Error('协作批次未完成')
  }
  finally {
    clearInterval(batchHeartbeat)
    await batchRenewal?.catch(() => {})
    await Promise.allSettled([...localRecoveries.values()].map(entry => entry.inFlight).filter(Boolean))
    signal?.removeEventListener('abort', abortHandler)
    if (slots.size) frozen = true
    let finishError
    if (!frozen) {
      try {
        const ownAttempts = await journal.list(null, batchId, issued.runEpoch)
        if (ownAttempts.length) frozen = true
        else {
          const finished = await client.recover('finish', { batchToken: batch.batchToken, reason: finishReason,
            confirmation: { stopped: true, detail: '执行写入能力已封存并排空，全部回合与任务停止已确认' } })
          if (finished.result?.batchId !== batchId || finished.result.lifecycle !== 'ended') throw new Error('批次停止未确认')
          await root.release()
          await vault.removeIfPresent(`batch:${batchId}:${issued.runEpoch}`)
          await vault.removeIfPresent(`batch-recovery:${batchId}:${issued.runEpoch}`)
        }
      } catch (error) { frozen = true; finishError = error }
    }
    // 同进程恢复只能使用本次池的退出交接，不从 active 为空推断。
    if (frozen && !slots.size && allSlotsQuiescent && typeof onQuiescent === 'function') {
      ownerSnapshot = await readBatchRun(rootConfig)
      if (ownerSnapshot) onQuiescent(createLocalRecoveryProof(ownerSnapshot, identity))
    }
    if (finishError) throw finishError
  }
}
