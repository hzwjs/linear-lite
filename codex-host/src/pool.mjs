import { randomUUID } from 'node:crypto'
import { performance } from 'node:perf_hooks'
import { createAppServer } from './app-server.mjs'
import { createWriteGate } from './executor.mjs'
import { createCheckpoint, checkpointAvailable } from './checkpoint.mjs'
import { leaseDeadline } from './api-client.mjs'
import { acquireProjectRoot, readProjectRootOwner, recoverProjectRoot } from './root-lease.mjs'
import { RecoveryJournal, recoverAttempts } from './recovery.mjs'

const PROMPT = `你在 Linear Lite 共享项目中工作。调用 host_action 的 observe 观察任务，自行选择 READY 任务并调用 claim，参数为 taskId 和 inputRevision。文件内容通过 write_file（path、content）写入，目录通过 make_directory（path）创建，文件通过 remove_file（path）删除；普通命令处于只读环境。按需调用 checkpoint（artifactRefs、finding、blockedReason）留下事实。完成后调用 deliver（conditions、artifactRefs），随后结束回合。无法完成时调用 stop（reason）并结束回合。不要操作 Git 或输出凭据。`
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const operation = prefix => `${prefix}-${randomUUID()}`
const command = slot => ({ attemptId: slot.record.attemptId, claimToken: slot.token,
  inputRevision: slot.record.inputRevision, hostInstanceId: slot.record.hostInstanceId,
  agentInstanceId: slot.record.agentInstanceId, operationId: operation('op') })
const stopCommand = slot => { const c = command(slot); delete c.inputRevision; return c }
const beijingLocal = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
  day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date()).replace(' ', 'T')

/** 一个批次持有物理目录锁；领取和写入由 Agent 触发。 */
export async function runPool({ client, identity, projectId, taskIds, expiresAt, maxSlots, maxAttempts,
  maxDurationMs, stateDirectory, vault, authToken, signal, createAgent = createAppServer }) {
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || !Array.isArray(taskIds) || !taskIds.length
      || new Set(taskIds).size !== taskIds.length || taskIds.some(id => !Number.isSafeInteger(id) || id <= 0)
      || !Number.isSafeInteger(maxSlots) || maxSlots < 1 || !Number.isSafeInteger(maxAttempts) || maxAttempts < 1
      || !Number.isSafeInteger(maxDurationMs) || maxDurationMs < 1
      || typeof expiresAt !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(expiresAt)
      || !Number.isFinite(Date.parse(expiresAt)) || typeof authToken !== 'string' || !authToken)
    throw new Error('执行批次参数无效')
  const binding = (await client.call('bindings/get', { projectId, deviceId: identity.deviceId })).result
  if (!binding || binding.projectId !== projectId || binding.deviceId !== identity.deviceId || !binding.id)
    throw new Error('项目设备绑定无效')
  const rootConfig = { stateDirectory, identity, projectId, projectDirectory: binding.projectDirectory }
  const journal = new RecoveryJournal(stateDirectory)
  if (await readProjectRootOwner(rootConfig)) {
    await recoverProjectRoot(rootConfig, async owner => {
      const records = await journal.list(owner.hostInstanceId)
      if (records.some(r => r.projectId !== projectId || r.bindingId !== binding.id || r.deviceId !== identity.deviceId
          || r.projectDirectory !== owner.projectDirectory || r.processId !== owner.processId)) throw new Error('恢复关联归属无效')
      await recoverAttempts({ records, identity, client, vault, journal })
    })
  }
  const root = await acquireProjectRoot(rootConfig)
  let frozen = false
  let batch
  let batchId
  const slots = new Set()
  let started = 0
  let reserved = 0
  let countWithoutClaim = 0
  let decisionReason = null
  const end = performance.now() + maxDurationMs
  const secrets = [authToken]
  let stoppedByUser = false
  let abortHandler
  try {
    const issued = (await client.call('batches/issue', { projectId, bindingId: binding.id,
      deviceId: identity.deviceId, projectDirectory: root.projectDirectory, taskIds, expiresAt })).result
    if (!issued?.batchId || !issued.batchToken) throw new Error('批次签发响应无效')
    batchId = issued.batchId
    await vault.put(`batch:${batchId}`, issued.batchToken)
    secrets.push(issued.batchToken)
    batch = { batchToken: issued.batchToken, bindingId: binding.id, deviceId: identity.deviceId,
      projectDirectory: root.projectDirectory }
    const stopCommon = slot => ({ projectId, bindingId: binding.id, deviceId: identity.deviceId,
      taskId: slot.record.taskId, command: stopCommand(slot) })
    async function toolRequest(slot, route, payload) {
      try { return await client.call(route, payload) }
      catch (error) {
        if (slot?.record) { frozen = true; void stop(slot, '协作服务连接失败') }
        throw error
      }
    }
    async function observe(slot) {
      const first = (await toolRequest(slot, 'environment/observe', { batch, available: [] })).result
      if (!Array.isArray(first.tasks)) throw new Error('协作环境无效')
      const available = []
      const seen = new Set()
      for (const task of first.tasks) for (const p of task.prerequisites ?? []) {
        const key = `${p.attemptId}:${p.inputRevision}`
        if (!p.attemptId || !p.inputRevision || seen.has(key)) continue
        const evidence = p.evidence
        if (await checkpointAvailable({ stateDirectory, projectDirectory: root.projectDirectory,
          projectCheckpoint: evidence?.projectCheckpoint, artifactRefs: evidence?.artifactRefs })) {
          available.push({ attemptId: p.attemptId, inputRevision: p.inputRevision }); seen.add(key)
        }
      }
      return available.length ? (await toolRequest(slot, 'environment/observe', { batch, available })).result : first
    }
    async function stop(slot, reason) {
      if (!slot.stopping) slot.stopping = (async () => {
        slot.stopRequested = true
        await slot.gate?.sealAndDrain()
      })()
      await slot.stopping
      if (slot.record?.attemptId && slot.token && slot.stopRequestAttemptId !== slot.record.attemptId) {
        slot.stopRequestAttemptId = slot.record.attemptId
        slot.stopRequestPromise = (async () => {
          try {
            const response = await client.call('attempts/request-stop', { ...stopCommon(slot), reason })
            if (!response.result?.accepted) frozen = true
          } catch { frozen = true }
        })()
      }
      await slot.stopRequestPromise
      if (slot.turnId && !slot.terminal && slot.interruptedTurnId !== slot.turnId) {
        slot.interruptedTurnId = slot.turnId
        try { await slot.agent.interrupt(slot.turnId) } catch { frozen = true }
      }
    }
    async function heartbeat(slot) {
      if (!slot.record?.attemptId || slot.stopRequested || slot.renewing) return
      slot.renewing = true
      try {
        const renewed = await client.call('attempts/renew', { batch, taskId: slot.record.taskId, command: command(slot) })
        if (!renewed.result?.accepted) throw new Error('租约续期遭拒')
        slot.deadline = leaseDeadline({ leaseUntil: renewed.result.data?.leaseUntil,
          serverTime: renewed.serverTime, requestStartedAt: renewed.requestStartedAt })
      } catch { frozen = true; await stop(slot, '租约续期失败') }
      finally { slot.renewing = false }
    }
    async function callTool(slot, params) {
      const input = params?.arguments
      if (!input || typeof input.action !== 'string' || !input.payload || typeof input.payload !== 'object'
          || Array.isArray(input.payload)) throw new Error('宿主操作无效')
      const payload = input.payload
      const serialized = JSON.stringify(payload)
      if (secrets.some(secret => serialized.includes(secret))) throw new Error('宿主操作包含凭据')
      if (input.action === 'observe') return observe(slot)
      if (input.action === 'claim') {
        if (slot.record || decisionReason || reserved + started >= maxAttempts || performance.now() >= end || signal?.aborted)
          throw new Error('领取额度不可用')
        if (!Number.isSafeInteger(payload.taskId) || payload.taskId <= 0
            || !Number.isSafeInteger(payload.inputRevision) || payload.inputRevision <= 0) throw new Error('领取参数无效')
        const view = await observe(slot)
        const task = view.tasks.find(t => t.taskId === payload.taskId)
        if (!task) throw new Error('任务不可领取')
        if (task.inputRevision !== payload.inputRevision || task.state !== 'READY') {
          slot.claimRejected = true
          throw new Error('任务不可领取')
        }
        if (decisionReason || reserved + started >= maxAttempts || slot.stopRequested || signal?.aborted || performance.now() >= end)
          throw new Error('领取额度不可用')
        reserved++
        const claimOperationId = operation('claim')
        slot.record = { projectId, bindingId: binding.id, batchId, deviceId: identity.deviceId,
          projectDirectory: root.projectDirectory, hostInstanceId: identity.hostInstanceId,
          agentInstanceId: slot.agentInstanceId, taskId: task.taskId, inputRevision: task.inputRevision,
          attemptId: null, claimOperationId, processId: process.pid }
        try {
          await journal.save(slot.record)
          let grant
          try { grant = await client.call('tasks/claim', { batch, taskId: task.taskId, inputRevision: task.inputRevision,
            hostInstanceId: identity.hostInstanceId, agentInstanceId: slot.agentInstanceId, operationId: claimOperationId,
            available: await availableFor(view) }) }
          catch (error) { frozen = true; throw error }
          if (!grant.result?.accepted) {
            await journal.remove(slot.record); slot.record = null; slot.claimRejected = true
            return { accepted: false, reason: grant.result?.reason }
          }
          started++
          slot.token = grant.result.claimToken
          if (!slot.token || !grant.result.attemptId) { frozen = true; throw new Error('领取响应无效') }
          secrets.push(slot.token)
          await vault.put(`attempt:${identity.hostInstanceId}:${slot.agentInstanceId}`, slot.token)
          slot.record.attemptId = grant.result.attemptId
          await journal.save(slot.record)
          slot.deadline = leaseDeadline({ leaseUntil: grant.result.leaseUntil, serverTime: grant.serverTime,
            requestStartedAt: grant.requestStartedAt })
          slot.gate = await createWriteGate({ projectDirectory: root.projectDirectory, stateDirectory,
            writeScopes: task.writeScopes, knownCredentials: secrets, isAuthorized: () => !slot.stopRequested && performance.now() < slot.deadline })
          if (slot.stopRequested) { await slot.gate.sealAndDrain(); await stop(slot, '执行请求停止') }
          slot.heartbeat = setInterval(() => {
            if (slot.heartbeatPromise) return
            const pending = heartbeat(slot)
            slot.heartbeatPromise = pending
            void pending.finally(() => { if (slot.heartbeatPromise === pending) slot.heartbeatPromise = null })
          }, 10000)
          return { accepted: true, task: { taskId: task.taskId, title: task.title, description: task.description,
            inputRevision: task.inputRevision, writeScopes: task.writeScopes } }
        } catch (error) { if (slot.record) frozen = true; throw error }
        finally { reserved-- }
      }
      if (!slot.gate || slot.stopRequested || performance.now() >= slot.deadline) throw new Error('尝试不可写')
      if (input.action === 'write_file') return slot.gate.writeFile(payload.path, payload.content)
      if (input.action === 'make_directory') return slot.gate.makeDirectory(payload.path)
      if (input.action === 'remove_file') return slot.gate.removeFile(payload.path)
      if (input.action === 'checkpoint') {
        const checkpoint = { occurredAt: beijingLocal(), artifactRefs: payload.artifactRefs }
        if (payload.finding !== undefined) checkpoint.finding = payload.finding
        if (payload.blockedReason !== undefined) checkpoint.blockedReason = payload.blockedReason
        const response = await toolRequest(slot, 'attempts/checkpoint', { batch, taskId: slot.record.taskId, command: command(slot), checkpoint })
        if (!response.result?.accepted) {
          await stop(slot, '检查点遭拒')
          throw new Error('检查点遭拒')
        }
        return { accepted: true }
      }
      if (input.action === 'deliver') {
        if (!Array.isArray(payload.conditions) || !Array.isArray(payload.artifactRefs) || !payload.artifactRefs.length)
          throw new Error('缺少验收成果')
        slot.delivery = { conditions: payload.conditions, artifactRefs: payload.artifactRefs }
        return { accepted: true }
      }
      if (input.action === 'stop') {
        if (typeof payload.reason !== 'string' || !payload.reason.trim() || payload.reason.length > 2048)
          throw new Error('停止原因无效')
        decisionReason = payload.reason
        for (const running of slots) void stop(running, payload.reason)
        return { accepted: true }
      }
      throw new Error('宿主操作无效')
    }
    async function availableFor(view) {
      const available = []; const seen = new Set()
      for (const task of view.tasks) for (const p of task.prerequisites ?? []) {
        const key = `${p.attemptId}:${p.inputRevision}`
        if (!p.attemptId || !p.inputRevision || seen.has(key)) continue
        if (await checkpointAvailable({ stateDirectory, projectDirectory: root.projectDirectory,
          projectCheckpoint: p.evidence?.projectCheckpoint, artifactRefs: p.evidence?.artifactRefs })) {
          available.push({ attemptId: p.attemptId, inputRevision: p.inputRevision }); seen.add(key)
        }
      }
      return available
    }
    async function execute(slot) {
      try {
        slot.toolTail = Promise.resolve()
        slot.agent = await createAgent({ cwd: root.projectDirectory, onToolCall: p => {
          const work = slot.toolTail.then(() => {
            if (slot.terminal) throw new Error('回合已结束')
            return callTool(slot, p)
          })
          slot.toolTail = work.catch(() => {})
          return work
        } })
        if (slot.stopRequested || signal?.aborted || performance.now() >= end) return
        const run = await slot.agent.run(PROMPT)
        slot.turnId = run.turnId
        if (slot.stopRequested || signal?.aborted || performance.now() >= end) await stop(slot, '执行结束')
        const event = await run.completion
        if (!event || event.threadId !== slot.agent.threadId || event.turn?.id !== slot.turnId) { frozen = true; return }
        slot.terminal = true
        await slot.toolTail
        await slot.gate?.sealAndDrain()
        clearInterval(slot.heartbeat)
        await slot.heartbeatPromise
        if (!slot.record) { if (!slot.claimRejected) countWithoutClaim++; return }
        if (!slot.record.attemptId || !slot.token) { frozen = true; return }
        if (event.turn.status === 'completed' && slot.delivery && !slot.stopRequested && performance.now() < slot.deadline) {
          const changedPaths = slot.gate.changedPaths()
          const projectCheckpoint = await createCheckpoint({ stateDirectory, projectDirectory: root.projectDirectory,
            artifactRefs: slot.delivery.artifactRefs, changedPaths, knownCredentials: secrets })
          if (slot.stopRequested || performance.now() >= slot.deadline) throw new Error('租约已到期')
          const response = await client.call('attempts/complete', { batch, taskId: slot.record.taskId,
            command: command(slot), evidence: { ...slot.delivery, changedPaths, projectCheckpoint },
            confirmation: { stopped: true, detail: '宿主写能力已封存并排空，Codex 回合已结束' } })
          if (!response.result?.accepted) throw new Error('成果提交遭拒')
        } else {
          await stop(slot, '执行回合结束')
          const response = await client.call('attempts/confirm-stop', { ...stopCommon(slot),
            confirmation: { stopped: true, detail: '宿主写能力已封存并排空，Codex 回合已结束' } })
          if (!response.result?.accepted) throw new Error('停止确认遭拒')
        }
        clearInterval(slot.heartbeat)
        await vault.remove(`attempt:${identity.hostInstanceId}:${slot.agentInstanceId}`)
        await journal.remove(slot.record)
        slot.record = null
      } catch { frozen = true; await stop(slot, '执行异常') }
      finally { clearInterval(slot.heartbeat); slot.agent?.close(); slots.delete(slot) }
    }
    abortHandler = () => { stoppedByUser = true; for (const slot of slots) void stop(slot, '用户停止') }
    signal?.addEventListener('abort', abortHandler, { once: true })
    while (!frozen && !signal?.aborted && !decisionReason && performance.now() < end) {
      const observation = await observe()
      if (observation.state === 'COMPLETE' && slots.size === 0) break
      const readyCount = observation.tasks.filter(task => task.state === 'READY').length
      while (!decisionReason && observation.state === 'READY' && slots.size < maxSlots && started + reserved < maxAttempts && !countWithoutClaim
          && [...slots].filter(slot => !slot.record?.attemptId).length < readyCount) {
        const slot = { agentInstanceId: randomUUID(), stopRequested: false }
        slots.add(slot); slot.finished = execute(slot)
      }
      if (!slots.size) break
      await Promise.race([wait(1000), ...[...slots].map(slot => slot.finished)])
    }
    if (signal?.aborted) stoppedByUser = true
    for (const slot of slots) void stop(slot, stoppedByUser ? '用户停止' : decisionReason ?? '批次预算结束')
    const pending = Promise.all([...slots].map(slot => slot.finished))
    if (slots.size && await Promise.race([pending.then(() => false), wait(10000).then(() => true)])) frozen = true
    if (frozen) return { state: 'RECOVERY_REQUIRED' }
    const observation = await observe()
    if (stoppedByUser) return { state: 'USER_STOPPED' }
    if (decisionReason) return { state: 'DECISION_REQUIRED', reason: decisionReason }
    if (observation.state === 'COMPLETE') return { state: 'COMPLETE' }
    if (started >= maxAttempts || performance.now() >= end) return { state: 'BUDGET_EXHAUSTED' }
    if (countWithoutClaim) return { state: 'DECISION_REQUIRED' }
    return { state: observation.state }
  } catch {
    frozen = true
    for (const slot of slots) {
      slot.stopRequested = true
      await slot.gate?.sealAndDrain()
      if (slot.turnId && !slot.terminal) await slot.agent.interrupt(slot.turnId).catch(() => {})
    }
    await Promise.race([Promise.all([...slots].map(slot => slot.finished)), wait(10000)])
    throw new Error('协作批次未完成')
  }
  finally {
    signal?.removeEventListener('abort', abortHandler)
    if (slots.size) frozen = true
    if (!frozen && !(await journal.list(identity.hostInstanceId)).length) {
      if (batchId) await vault.remove(`batch:${batchId}`)
      await root.release()
    }
  }
}
