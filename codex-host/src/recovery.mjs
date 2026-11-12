import { readFile, readdir, rmdir, unlink } from 'node:fs/promises'
import path from 'node:path'
import { syncDirectory, writeJournalFile } from './atomic-journal.mjs'
import { createHash, randomUUID } from 'node:crypto'
import { recoverBatchRun } from './batch-journal.mjs'
import { ProcessGroupJournal, assertGroupsStopped, groupIsGone } from './process-groups.mjs'
import { matchesLocalRecoveryProof, matchesLocalAttemptProof } from './recovery-proof.mjs'
import { readBatchRun } from './batch-journal.mjs'

const FIELDS = Object.freeze(['projectId', 'bindingId', 'batchId', 'deviceId', 'projectDirectory',
  'hostInstanceId', 'runEpoch', 'agentInstanceId', 'taskId', 'inputRevision', 'attemptId', 'claimOperationId', 'processId', 'stopConfirmed', 'claimOutcome'])

function validate(record) {
  if (!record || Object.keys(record).some(key => !FIELDS.includes(key))) throw new Error('恢复关联字段无效')
  for (const key of ['projectId', 'bindingId', 'taskId', 'inputRevision', 'processId', 'runEpoch']) {
    if (!Number.isSafeInteger(record[key]) || record[key] <= 0) throw new Error('恢复关联标识无效')
  }
  if (typeof record.stopConfirmed !== 'boolean' || (record.stopConfirmed
      ? !['FENCED', 'REJECTED', 'CLAIMED'].includes(record.claimOutcome) : record.claimOutcome !== null))
    throw new Error('停止确认事实无效')
  if (record.attemptId !== null && (!Number.isSafeInteger(record.attemptId) || record.attemptId <= 0))
    throw new Error('恢复尝试标识无效')
  for (const key of ['batchId', 'deviceId', 'projectDirectory', 'hostInstanceId', 'agentInstanceId', 'claimOperationId']) {
    if (typeof record[key] !== 'string' || !record[key]) throw new Error('恢复关联字段缺失')
  }
  if (!/^[A-Za-z0-9_-]+$/.test(record.batchId) || !/^[A-Za-z0-9_-]+$/.test(record.hostInstanceId) || !/^[A-Za-z0-9_-]+$/.test(record.agentInstanceId)
      || !path.isAbsolute(record.projectDirectory)) throw new Error('恢复关联身份无效')
  return Object.fromEntries(FIELDS.map(key => [key, record[key]]))
}

function fileId(record) { return `${record.hostInstanceId}-${record.agentInstanceId}.json` }

/** 只保存本机执行关联；任务状态、范围及检查点以服务端记录为准。 */
export class RecoveryJournal {
  constructor(stateDirectory) { this.stateDirectory = stateDirectory; this.directory = path.join(stateDirectory, 'attempts') }
  scoped(batchId, runEpoch) {
    if (typeof batchId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(batchId)
        || !Number.isSafeInteger(runEpoch) || runEpoch <= 0) throw new Error('批次运行关联无效')
    return path.join(this.directory, batchId, String(runEpoch))
  }
  async save(input) {
    const record = validate(input)
    await writeJournalFile(path.join(this.scoped(record.batchId, record.runEpoch), fileId(record)), record, this.stateDirectory)
  }
  async list(hostInstanceId, batchId, runEpoch) {
    const directory = this.scoped(batchId, runEpoch)
    let files
    try { files = await readdir(directory) } catch (error) { if (error.code === 'ENOENT') return []; throw error }
    const records = []
    for (const file of files.filter(value => value.endsWith('.json'))) {
      let record
      try { record = validate(JSON.parse(await readFile(path.join(directory, file), 'utf8'))) }
      catch { throw new Error('INvalidate') }
      if (fileId(record) !== file || record.batchId !== batchId || record.runEpoch !== runEpoch)
        throw new Error('批次运行关联无效')
      if (!hostInstanceId || record.hostInstanceId === hostInstanceId) records.push(record)
    }
    return records
  }
  async remove(input) {
    const record = validate(input)
    const directory = this.scoped(record.batchId, record.runEpoch)
    await unlink(path.join(directory, fileId(record)))
    await syncDirectory(directory)
    // 多个尝试共享代次目录，非空时保留目录。
    try {
      await rmdir(directory)
      await rmdir(path.dirname(directory))
      await syncDirectory(this.directory)
    } catch (error) {
      if (error.code !== 'ENOTEMPTY' && error.code !== 'ENOENT') throw error
    }
  }
}

/** 文件编辑 helper 的进程组是独立的写入单元，事实保留供后续复核。 */
async function assertEditOperationsStopped(stateDirectory, record, owner, identity, localProof, attemptProof) {
  if (record.attemptId == null) return
  const directory = path.join(stateDirectory, 'file-operations', String(record.attemptId))
  let files
  try { files = await readdir(directory) } catch (error) { if (error.code === 'ENOENT') return; throw error }
  const allowedFields = new Set(['attemptId', 'agentInstanceId', 'operationId', 'action', 'status',
    'processGroupId', 'changedPaths', 'artifactObservations', 'previousContentVersion',
    'previousPath', 'traceOperationId', 'occurredAt', 'cleanupPaths'])
  for (const file of files.filter(name => name.endsWith('.json'))) {
    let fact
    try { fact = JSON.parse(await readFile(path.join(directory, file), 'utf8')) }
    catch { throw new Error('编辑进程记录无法读取') }
    if (!fact || typeof fact !== 'object' || Array.isArray(fact)
        || Object.keys(fact).some(key => !allowedFields.has(key))
        || fact.attemptId !== record.attemptId || fact.agentInstanceId !== record.agentInstanceId
        || typeof fact.operationId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(fact.operationId)
        || file !== `${fact.operationId}.json` || !['pending', 'completed', 'rejected', 'unknown'].includes(fact.status)
        || !['create', 'patch', 'replace', 'rename', 'remove', 'mkdir'].includes(fact.action)
        || !Array.isArray(fact.changedPaths) || fact.changedPaths.some(value => typeof value !== 'string')
        || !Array.isArray(fact.artifactObservations)
        || fact.artifactObservations.some(value => !value || typeof value.path !== 'string' || value.state !== 'PRESENT'
          || typeof value.contentVersion !== 'string' || !/^sha256:[a-f0-9]{64}$/.test(value.contentVersion))
        || (fact.previousContentVersion !== null && (typeof fact.previousContentVersion !== 'string'
          || !/^sha256:[a-f0-9]{64}$/.test(fact.previousContentVersion)))
        || (['create', 'patch', 'replace', 'rename'].includes(fact.action)
          ? typeof fact.traceOperationId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(fact.traceOperationId)
          : Object.hasOwn(fact, 'traceOperationId'))
        || (fact.status === 'completed' && (!Object.hasOwn(fact, 'previousPath')
          || (fact.previousPath !== null && typeof fact.previousPath !== 'string')))
        || (Object.hasOwn(fact, 'cleanupPaths') && (!Array.isArray(fact.cleanupPaths)
          || fact.cleanupPaths.some(value => typeof value !== 'string')))
        || typeof fact.occurredAt !== 'string' || !Number.isFinite(Date.parse(fact.occurredAt))
        || (fact.processGroupId !== null && (!Number.isSafeInteger(fact.processGroupId) || fact.processGroupId <= 1))
        || (fact.processGroupId === null && ['completed', 'unknown'].includes(fact.status)))
      throw new Error('编辑进程记录归属无效')
    if (fact.processGroupId === null) {
      if (fact.status === 'pending' && !matchesLocalRecoveryProof(localProof, owner, identity)
          && !matchesLocalAttemptProof(attemptProof, owner, record, identity))
        throw new Error('编辑进程归属待确认')
    } else if (!groupIsGone(fact.processGroupId)) {
      throw new Error('编辑进程组未确认退出')
    }
  }
}

/** 命令事实独立于编辑 helper；未知归属不得用于停止确认。 */
async function assertCommandOperationsStopped(stateDirectory, record) {
  if (record.attemptId == null) return
  const directory = path.join(stateDirectory, 'command-operations', String(record.attemptId))
  let files
  try { files = await readdir(directory) } catch (error) { if (error.code === 'ENOENT') return; throw error }
  const allowedFields = new Set(['attemptId', 'agentInstanceId', 'operationId', 'status',
    'processGroupId', 'startedAt', 'finishedAt', 'exitCode', 'timedOut'])
  for (const file of files.filter(name => name.endsWith('.json'))) {
    let fact
    try { fact = JSON.parse(await readFile(path.join(directory, file), 'utf8')) }
    catch { throw new Error('命令进程记录无法读取') }
    if (!fact || typeof fact !== 'object' || Array.isArray(fact)
        || Object.keys(fact).length !== allowedFields.size || Object.keys(fact).some(key => !allowedFields.has(key))
        || fact.attemptId !== record.attemptId || fact.agentInstanceId !== record.agentInstanceId
        || typeof fact.operationId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(fact.operationId)
        || file !== `${fact.operationId}.json` || !['pending', 'completed', 'failed', 'rejected', 'unknown'].includes(fact.status)
        || (fact.processGroupId !== null && (!Number.isSafeInteger(fact.processGroupId) || fact.processGroupId <= 1))
        || typeof fact.startedAt !== 'string' || !Number.isFinite(Date.parse(fact.startedAt))
        || (fact.finishedAt !== null && (typeof fact.finishedAt !== 'string' || !Number.isFinite(Date.parse(fact.finishedAt))))
        || (fact.status === 'pending' ? fact.finishedAt !== null : fact.finishedAt === null)
        || (fact.exitCode !== null && !Number.isSafeInteger(fact.exitCode)) || typeof fact.timedOut !== 'boolean'
        || (['completed', 'failed'].includes(fact.status) && fact.processGroupId === null)
        || (fact.status === 'completed' && fact.exitCode !== 0))
      throw new Error('命令进程记录归属无效')
    if (fact.status === 'unknown' || (fact.status === 'pending' && fact.processGroupId === null))
      throw new Error('命令进程归属待确认')
    if (fact.processGroupId !== null && !groupIsGone(fact.processGroupId))
      throw new Error('命令进程组未确认退出')
  }
}

/** 先持久停止确认，再由调用方清理进程和凭据记录。 */
export async function recoverAttempts({ records, identity, client, vault, journal, owner, localProof, attemptProof }) {
  const confirmed = []
  for (const record of records) {
    if (record.deviceId !== identity.deviceId) throw new Error('恢复设备与尝试不匹配')
    const currentOwner = (matchesLocalRecoveryProof(localProof, owner, identity)
      || matchesLocalAttemptProof(attemptProof, owner, record, identity))
      && record.processId === owner.processId && record.hostInstanceId === owner.hostInstanceId
    if (!currentOwner) {
      try { process.kill(record.processId, 0); throw new Error('原宿主仍在运行') }
      catch (error) { if (error.code !== 'ESRCH') throw new Error('无法确认原宿主退出') }
    }
    await assertEditOperationsStopped(path.dirname(journal.directory), record, owner, identity, localProof, attemptProof)
    await assertCommandOperationsStopped(path.dirname(journal.directory), record)
    if (record.stopConfirmed) { confirmed.push(record); continue }
    const batchToken = await vault.get(`batch:${record.batchId}:${record.runEpoch}`)
    if (typeof batchToken !== 'string' || !batchToken) throw new Error('缺少原批次恢复凭据')
    let claimOutcome = 'CLAIMED'
    if (record.attemptId == null) {
      const response = await client.recover('recover-claim', {
        batchToken, bindingId: record.bindingId, deviceId: record.deviceId, taskId: record.taskId,
        inputRevision: record.inputRevision, hostInstanceId: record.hostInstanceId, agentInstanceId: record.agentInstanceId,
        claimOperationId: record.claimOperationId,
        operationId: `recover-claim-${createHash('sha256').update(record.claimOperationId).digest('hex')}`,
        confirmation: { stopped: true, detail: '执行能力封存，登记进程组退出' },
      })
      claimOutcome = response.result?.data?.claimOutcome
      if (response.result?.accepted !== true || !['FENCED', 'REJECTED', 'CLAIMED'].includes(claimOutcome)
          || (claimOutcome === 'CLAIMED'
            ? !Number.isSafeInteger(response.result.data.attemptId) || response.result.data.attemptId <= 0
            : response.result.data.attemptId !== null))
        throw new Error('领取恢复停止确认遭拒')
    } else {
      const claimToken = await vault.get(`attempt:${record.hostInstanceId}:${record.agentInstanceId}`)
      if (typeof claimToken !== 'string' || !claimToken) throw new Error('缺少原尝试恢复凭据')
      const command = { attemptId: record.attemptId, claimToken, hostInstanceId: record.hostInstanceId,
        agentInstanceId: record.agentInstanceId, operationId: `recover-${randomUUID()}` }
      const common = { batchToken, projectId: record.projectId, bindingId: record.bindingId, deviceId: record.deviceId,
        taskId: record.taskId, command }
      const prior = await client.recover('read', common)
      const fact = prior.result
      if (!fact || fact.id !== record.attemptId || fact.taskId !== record.taskId || fact.bindingId !== record.bindingId
          || fact.batchId !== record.batchId || fact.hostInstanceId !== record.hostInstanceId
          || fact.agentInstanceId !== record.agentInstanceId || fact.inputRevision !== record.inputRevision
          || (fact.stopConfirmedAt !== null && (typeof fact.stopConfirmedAt !== 'string'
            || !Number.isFinite(Date.parse(fact.stopConfirmedAt))))) throw new Error('恢复尝试事实无效')
      if (fact.stopConfirmedAt === null) {
        const requested = await client.recover('request-stop', { ...common, reason: '宿主恢复停止执行' })
        if (requested.result?.accepted !== true) throw new Error('恢复停止请求遭拒')
        command.operationId = `recover-confirm-${randomUUID()}`
        const confirmation = { stopped: true, detail: '执行能力封存，登记进程组退出' }
        const stopped = await client.recover('confirm-stop', { ...common, confirmation })
        if (stopped.result?.accepted !== true) throw new Error('恢复停止确认遭拒')
      }
    }
    const next = { ...record, stopConfirmed: true, claimOutcome }
    await journal.save(next)
    confirmed.push(next)
  }
  return confirmed
}

/** 只处理已证明封存的单个尝试，不结束批次。 */
export async function recoverLocalAttempt({ stateDirectory, identity, client, vault, owner, record, proof }) {
  if (!matchesLocalAttemptProof(proof, owner, record, identity)) throw new Error('尝试恢复证明无效')
  const current = await readBatchRun({ stateDirectory, batchId: owner.batchId })
  if (!current || JSON.stringify(current) !== JSON.stringify(owner) || current.phase !== 'accepted')
    throw new Error('批次运行归属变化')
  const journal = new RecoveryJournal(stateDirectory), groups = new ProcessGroupJournal(stateDirectory)
  const records = await journal.list(null, owner.batchId, owner.runEpoch)
  if (records.some(value => value.hostInstanceId !== owner.hostInstanceId || value.processId !== owner.processId
      || value.projectId !== owner.projectId || value.deviceId !== owner.deviceId
      || value.projectDirectory !== owner.projectDirectory)) throw new Error('尝试恢复归属不完整')
  const saved = records.find(value => value.agentInstanceId === record.agentInstanceId)
  if (!saved || JSON.stringify(saved) !== JSON.stringify(validate(record))) throw new Error('尝试恢复记录变化')
  const allGroups = await groups.list(null, owner.batchId, owner.runEpoch)
  if (allGroups.some(value => value.hostInstanceId !== owner.hostInstanceId || value.processId !== owner.processId
      || value.projectId !== owner.projectId || value.deviceId !== owner.deviceId
      || value.projectDirectory !== owner.projectDirectory)) throw new Error('执行进程关联不完整')
  const ownedGroups = allGroups.filter(group => group.agentInstanceId === record.agentInstanceId)
  assertGroupsStopped(ownedGroups, record.stopConfirmed ? [] : [record], owner)
  const [confirmed] = await recoverAttempts({ records: [record], identity, client, vault, journal, owner, attemptProof: proof })
  try {
    for (const group of ownedGroups) await groups.removeIfPresent(group)
    await vault.removeIfPresent(`attempt:${confirmed.hostInstanceId}:${confirmed.agentInstanceId}`)
    await journal.remove(confirmed)
    return { cleaned: true, record: confirmed }
  } catch {
    return { cleaned: false, record: confirmed }
  }
}

/** 恢复按批次和代次收口，其他批次不参与本次判断。 */
export async function recoverBatch({ stateDirectory, identity, client, vault, batchId, localProof }) {
  const journal = new RecoveryJournal(stateDirectory)
  const groups = new ProcessGroupJournal(stateDirectory)
  const recovered = await recoverBatchRun({ stateDirectory, identity, batchId }, async owner => {
    // 零代次表示尚未请求接受，无法存在该代次的尝试或进程记录。
    if (owner.phase === 'preparing' && owner.runEpoch === 0) return
    const records = await journal.list(null, owner.batchId, owner.runEpoch)
    const processGroups = await groups.list(null, owner.batchId, owner.runEpoch)
    if (records.some(record => record.batchId !== owner.batchId || record.runEpoch !== owner.runEpoch
        || record.projectId !== owner.projectId || record.projectDirectory !== owner.projectDirectory
        || record.deviceId !== owner.deviceId || record.hostInstanceId !== owner.hostInstanceId
        || record.processId !== owner.processId))
      throw new Error('恢复关联归属无效')
    if (processGroups.some(group => group.hostInstanceId !== owner.hostInstanceId || group.processId !== owner.processId
        || group.projectId !== owner.projectId || group.deviceId !== owner.deviceId
        || group.projectDirectory !== owner.projectDirectory)) throw new Error('执行进程关联不完整')
    if (owner.phase === 'preparing') {
      if (records.length || processGroups.length) throw new Error('准备阶段存在执行记录')
      return
    }
    if (owner.phase === 'prepared') {
      if (records.length || processGroups.length) throw new Error('接受阶段存在执行记录')
      const recoveryToken = await vault.get(`batch-recovery:${owner.batchId}:${owner.runEpoch}`)
      if (typeof recoveryToken !== 'string' || !recoveryToken) throw new Error('缺少批次接受恢复凭据')
      const finished = await client.recover('finish-accept', { recoveryToken,
        confirmation: { stopped: true, detail: '批次接受结果收口，执行进程未启动' } })
      if (finished.result?.batchId !== owner.batchId || finished.result.lifecycle !== 'ended')
        throw new Error('批次接受结果未确认')
      return
    }
    let pending = false
    let recoveryError
    for (const record of records) {
      const ownGroups = processGroups.filter(group => group.agentInstanceId === record.agentInstanceId)
      try {
        assertGroupsStopped(ownGroups, record.stopConfirmed ? [] : [record], owner)
        const [confirmed] = await recoverAttempts({ records: [record], identity, client, vault, journal, owner, localProof })
        for (const group of ownGroups) await groups.removeIfPresent(group)
        await vault.removeIfPresent(`attempt:${confirmed.hostInstanceId}:${confirmed.agentInstanceId}`)
        await journal.remove(confirmed)
      } catch (error) { pending = true; recoveryError = error }
    }
    if (pending) throw new Error('部分执行尝试等待恢复', { cause: recoveryError })
    const remaining = await journal.list(null, owner.batchId, owner.runEpoch)
    const remainingGroups = await groups.list(null, owner.batchId, owner.runEpoch)
    if (remaining.length) throw new Error('执行尝试等待恢复')
    assertGroupsStopped(remainingGroups, [], owner)
    const batchToken = await vault.get(`batch:${owner.batchId}:${owner.runEpoch}`)
    if (typeof batchToken !== 'string' || !batchToken) throw new Error('缺少原批次恢复凭据')
    const finished = await client.recover('finish', { batchToken, reason: 'host_stopped',
      confirmation: { stopped: true, detail: '执行能力封存，登记进程组退出，任务停止确认' } })
    if (finished.result?.batchId !== owner.batchId || finished.result.lifecycle !== 'ended') throw new Error('原批次停止未确认')
    for (const group of remainingGroups) await groups.removeIfPresent(group)
  }, localProof)
  if (recovered.recovered) {
    if (recovered.owner.runEpoch > 0) await vault.removeIfPresent(`batch:${batchId}:${recovered.owner.runEpoch}`)
    if (recovered.owner.runEpoch > 0) await vault.removeIfPresent(`batch-recovery:${batchId}:${recovered.owner.runEpoch}`)
  }
  return recovered
}
