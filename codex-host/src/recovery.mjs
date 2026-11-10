import { mkdir, readFile, readdir, rename, unlink, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'

const FIELDS = Object.freeze(['projectId', 'bindingId', 'batchId', 'deviceId', 'projectDirectory',
  'hostInstanceId', 'agentInstanceId', 'taskId', 'inputRevision', 'attemptId', 'claimOperationId', 'processId'])

function validate(record) {
  if (!record || Object.keys(record).some(key => !FIELDS.includes(key))) throw new Error('恢复关联字段无效')
  for (const key of ['projectId', 'bindingId', 'taskId', 'inputRevision', 'processId']) {
    if (!Number.isSafeInteger(record[key]) || record[key] <= 0) throw new Error('恢复关联标识无效')
  }
  if (record.attemptId !== null && (!Number.isSafeInteger(record.attemptId) || record.attemptId <= 0))
    throw new Error('恢复尝试标识无效')
  for (const key of ['batchId', 'deviceId', 'projectDirectory', 'hostInstanceId', 'agentInstanceId', 'claimOperationId']) {
    if (typeof record[key] !== 'string' || !record[key]) throw new Error('恢复关联字段缺失')
  }
  if (!/^[A-Za-z0-9_-]+$/.test(record.hostInstanceId) || !/^[A-Za-z0-9_-]+$/.test(record.agentInstanceId)
      || !path.isAbsolute(record.projectDirectory)) throw new Error('恢复关联身份无效')
  return Object.fromEntries(FIELDS.map(key => [key, record[key]]))
}

function fileId(record) { return `${record.hostInstanceId}-${record.agentInstanceId}.json` }

/** 只保存本机执行关联；任务状态、范围及检查点以服务端记录为准。 */
export class RecoveryJournal {
  constructor(stateDirectory) { this.directory = path.join(stateDirectory, 'attempts') }
  async save(input) {
    const record = validate(input)
    await mkdir(this.directory, { recursive: true, mode: 0o700 })
    const temp = path.join(this.directory, `.attempt-${randomUUID()}.tmp`)
    try {
      await writeFile(temp, JSON.stringify(record), { flag: 'wx', mode: 0o600 })
      await rename(temp, path.join(this.directory, fileId(record)))
    } finally { await unlink(temp).catch(() => {}) }
  }
  async list(hostInstanceId) {
    let names
    try { names = await readdir(this.directory) } catch (error) { if (error.code === 'ENOENT') return []; throw error }
    const result = []
    for (const name of names.filter(value => value.endsWith('.json'))) {
      let record
      try { record = validate(JSON.parse(await readFile(path.join(this.directory, name), 'utf8'))) }
      catch { throw new Error('恢复关联记录无法读取') }
      if (fileId(record) !== name) throw new Error('恢复关联记录归属无效')
      if (!hostInstanceId || record.hostInstanceId === hostInstanceId) result.push(record)
    }
    return result
  }
  async remove(record) { await unlink(path.join(this.directory, fileId(validate(record)))) }
}

/** 仅在服务确认停止后删除恢复关联。 */
export async function recoverAttempts({ records, identity, client, vault, journal }) {
  for (const record of records) {
    if (record.deviceId !== identity.deviceId) throw new Error('恢复设备与尝试不匹配')
    try { process.kill(record.processId, 0); throw new Error('原宿主仍在运行') }
    catch (error) { if (error.code !== 'ESRCH') throw new Error('无法确认原宿主退出') }
    if (record.attemptId == null) {
      const batchToken = await vault.get(`batch:${record.batchId}`)
      const response = await client.call('attempts/recover-claim', {
        batchToken, bindingId: record.bindingId, deviceId: record.deviceId, taskId: record.taskId,
        hostInstanceId: record.hostInstanceId, agentInstanceId: record.agentInstanceId,
        claimOperationId: record.claimOperationId, operationId: `recover-claim-${record.claimOperationId}`,
        confirmation: { stopped: true, detail: '原宿主退出，执行写入能力封存' },
      })
      if (!response.result?.accepted) throw new Error('领取恢复停止确认遭拒')
      await journal.remove(record)
      continue
    }
    const claimToken = await vault.get(`attempt:${record.hostInstanceId}:${record.agentInstanceId}`)
    const command = { attemptId: record.attemptId, claimToken, hostInstanceId: record.hostInstanceId,
      agentInstanceId: record.agentInstanceId, operationId: `recover-${randomUUID()}` }
    const common = { projectId: record.projectId, bindingId: record.bindingId, deviceId: record.deviceId,
      taskId: record.taskId, command }
    const prior = await client.call('attempts/recovery', common)
    if (!prior.result?.stopConfirmedAt) {
      const requested = await client.call('attempts/request-stop', { ...common, reason: '宿主恢复停止执行' })
      if (!requested.result?.accepted) throw new Error('恢复停止请求遭拒')
      command.operationId = `recover-confirm-${randomUUID()}`
      const confirmation = { stopped: true, detail: '原宿主退出，执行写入能力封存' }
      const stopped = await client.call('attempts/confirm-stop', { ...common, confirmation })
      if (!stopped.result?.accepted) throw new Error('恢复停止确认遭拒')
    }
    await vault.remove(`attempt:${record.hostInstanceId}:${record.agentInstanceId}`)
    await journal.remove(record)
  }
}
