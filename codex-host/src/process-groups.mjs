import { readFile, readdir, rmdir, unlink } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'
import path from 'node:path'
import { syncDirectory, writeJournalFile } from './atomic-journal.mjs'

const FIELDS = ['projectId', 'batchId', 'deviceId', 'projectDirectory', 'hostInstanceId', 'runEpoch',
  'agentInstanceId', 'processId', 'phase', 'processGroupId']

function valid(input) {
  if (!input || Object.keys(input).length !== FIELDS.length || Object.keys(input).some(key => !FIELDS.includes(key)))
    throw new Error('执行进程关联无效')
  for (const key of ['projectId', 'runEpoch', 'processId'])
    if (!Number.isSafeInteger(input[key]) || input[key] <= 0) throw new Error('执行进程关联无效')
  for (const key of ['batchId', 'deviceId', 'projectDirectory', 'hostInstanceId', 'agentInstanceId'])
    if (typeof input[key] !== 'string' || !input[key]) throw new Error('执行进程关联无效')
  if (!path.isAbsolute(input.projectDirectory) || !/^[A-Za-z0-9_-]+$/.test(input.batchId) || !/^[A-Za-z0-9_-]+$/.test(input.hostInstanceId)
      || !/^[A-Za-z0-9_-]+$/.test(input.agentInstanceId)
      || !['idle', 'starting', 'running'].includes(input.phase)
      || (input.phase === 'running' ? !Number.isSafeInteger(input.processGroupId) || input.processGroupId <= 1
        : input.processGroupId !== null)) throw new Error('执行进程关联无效')
  return Object.fromEntries(FIELDS.map(key => [key, input[key]]))
}

function filename(record) { return `${record.hostInstanceId}-${record.agentInstanceId}.json` }

/** spawn 前保存 starting，崩溃时无法确认的进程归属保留冻结。 */
export class ProcessGroupJournal {
  constructor(stateDirectory) { this.stateDirectory = stateDirectory; this.directory = path.join(stateDirectory, 'process-groups') }
  scoped(batchId, runEpoch) {
    if (typeof batchId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(batchId)
        || !Number.isSafeInteger(runEpoch) || runEpoch <= 0) throw new Error('批次运行关联无效')
    return path.join(this.directory, batchId, String(runEpoch))
  }
  async save(input) {
    const record = valid(input)
    await writeJournalFile(path.join(this.scoped(record.batchId, record.runEpoch), filename(record)), record, this.stateDirectory)
  }
  async list(hostInstanceId, batchId, runEpoch) {
    const directory = this.scoped(batchId, runEpoch)
    let files
    try { files = await readdir(directory) } catch (error) { if (error.code === 'ENOENT') return []; throw error }
    const records = []
    for (const file of files.filter(value => value.endsWith('.json'))) {
      let record
      try { record = valid(JSON.parse(await readFile(path.join(directory, file), 'utf8'))) }
      catch { throw new Error('INvalid') }
      if (filename(record) !== file || record.batchId !== batchId || record.runEpoch !== runEpoch)
        throw new Error('批次运行关联无效')
      if (!hostInstanceId || record.hostInstanceId === hostInstanceId) records.push(record)
    }
    return records
  }
  async removeIfPresent(input) {
    try { await this.remove(input) }
    catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  async remove(input) {
    const record = valid(input)
    const directory = this.scoped(record.batchId, record.runEpoch)
    await unlink(path.join(directory, filename(record)))
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

/** 判断只接受 ESRCH；权限、状态未知和存活都保留冻结。 */
export function groupIsGone(processGroupId, kill = process.kill) {
  if (!Number.isSafeInteger(processGroupId) || processGroupId <= 1) throw new Error('执行进程组标识无效')
  try { kill(-processGroupId, 0); return false }
  catch (error) { if (error.code === 'ESRCH') return true; throw error }
}

export async function stopProcessGroup(processGroupId, { kill = process.kill, wait = delay } = {}) {
  if (groupIsGone(processGroupId, kill)) return
  try { kill(-processGroupId, 'SIGTERM') } catch (error) { if (error.code !== 'ESRCH') throw error }
  for (let i = 0; i < 30; i++) {
    if (groupIsGone(processGroupId, kill)) return
    await wait(100)
  }
  try { kill(-processGroupId, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error }
  for (let i = 0; i < 30; i++) {
    if (groupIsGone(processGroupId, kill)) return
    await wait(100)
  }
  throw new Error('执行进程组未确认退出')
}

export function assertGroupsStopped(groups, attempts, owner, kill = process.kill) {
  const matches = group => group.batchId === owner.batchId && group.projectId === owner.projectId
    && group.deviceId === owner.deviceId && group.projectDirectory === owner.projectDirectory
    && group.hostInstanceId === owner.hostInstanceId && group.processId === owner.processId && group.runEpoch === owner.runEpoch
  if (groups.some(group => !matches(group)) || attempts.some(attempt => !groups.some(group =>
    group.agentInstanceId === attempt.agentInstanceId))) throw new Error('执行进程关联不完整')
  for (const group of groups) {
    if (group.phase === 'starting' || group.phase === 'running' && !groupIsGone(group.processGroupId, kill))
      throw new Error('执行进程组未确认退出')
  }
}
