import { mkdir, readFile, readdir, rename, rmdir, unlink } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import { HostError, validateProjectDirectory } from './device.mjs'
import { matchesLocalRecoveryProof } from './recovery-proof.mjs'
import { syncDirectory, writeJournalFile } from './atomic-journal.mjs'

const fields = ['projectId', 'batchId', 'runEpoch', 'deviceId', 'hostInstanceId', 'projectDirectory', 'processId', 'phase']
const safeId = /^[A-Za-z0-9_-]+$/

function validate(owner) {
  if (!owner || Object.keys(owner).length !== fields.length || Object.keys(owner).some(key => !fields.includes(key))
      || !Number.isSafeInteger(owner.projectId) || owner.projectId <= 0
      || !Number.isSafeInteger(owner.runEpoch) || owner.runEpoch < 0
      || !Number.isSafeInteger(owner.processId) || owner.processId <= 0
      || typeof owner.batchId !== 'string' || owner.batchId.length > 36 || !safeId.test(owner.batchId)
      || typeof owner.hostInstanceId !== 'string' || !safeId.test(owner.hostInstanceId)
      || typeof owner.deviceId !== 'string' || !owner.deviceId
      || typeof owner.projectDirectory !== 'string' || !path.isAbsolute(owner.projectDirectory)
      || !['preparing', 'prepared', 'accepted'].includes(owner.phase)
      || (owner.phase !== 'preparing' && owner.runEpoch === 0)) throw new Error('批次运行记录无效')
  return Object.fromEntries(fields.map(key => [key, owner[key]]))
}

function directory(stateDirectory, batchId) {
  if (typeof batchId !== 'string' || batchId.length > 36 || !safeId.test(batchId)) throw new Error('批次标识无效')
  return path.join(stateDirectory, 'runs', batchId)
}

async function readEntry(stateDirectory, batchId) {
  const dir = directory(stateDirectory, batchId)
  let names
  try { names = await readdir(dir) } catch (error) { if (error.code === 'ENOENT') return null; throw error }
  const records = names.filter(name => name === 'pending.json' || /^[1-9][0-9]*\.json$/.test(name))
  if (records.length !== 1) throw new Error('批次运行记录需要恢复核验')
  const name = records[0]
  const owner = validate(JSON.parse(await readFile(path.join(dir, name), 'utf8')))
  if (owner.batchId !== batchId || (name !== 'pending.json' && name !== `${owner.runEpoch}.json`))
    throw new Error('批次运行记录归属无效')
  return { dir, name, owner }
}

async function store(entry, owner, targetName = entry.name) {
  await writeJournalFile(path.join(entry.dir, entry.name), validate(owner), path.dirname(path.dirname(entry.dir)))
  if (targetName !== entry.name) {
    await rename(path.join(entry.dir, entry.name), path.join(entry.dir, targetName))
    await syncDirectory(entry.dir)
  }
}

/** 目录只用于运行工作空间；单批次保留独立运行记录。 */
export async function createBatchRun({ stateDirectory, identity, projectId, projectDirectory, batchId }) {
  const root = await validateProjectDirectory(identity, { deviceId: identity.deviceId, projectDirectory })
  const dir = directory(stateDirectory, batchId)
  const base = path.dirname(dir)
  await mkdir(base, { recursive: true, mode: 0o700 })
  await syncDirectory(path.dirname(base))
  const temporary = path.join(base, `.run-${randomUUID()}`)
  const owner = validate({ projectId, batchId, runEpoch: 0, deviceId: identity.deviceId,
    hostInstanceId: identity.hostInstanceId, projectDirectory: root.projectDirectory,
    processId: process.pid, phase: 'preparing' })
  try {
    await mkdir(temporary, { mode: 0o700 })
    await writeJournalFile(path.join(temporary, 'pending.json'), owner, stateDirectory)
    await rename(temporary, dir)
    await syncDirectory(base)
  } catch (error) {
    await unlink(path.join(temporary, 'pending.json')).catch(() => {})
    await rmdir(temporary).catch(() => {})
    if (error.code === 'EEXIST' || error.code === 'ENOTEMPTY')
      throw new HostError(409, 'BATCH_RECOVERY_REQUIRED', '批次运行记录等待恢复')
    throw error
  }
  let expected = owner
  async function update(next) {
    const entry = await readEntry(stateDirectory, batchId)
    if (!entry || JSON.stringify(entry.owner) !== JSON.stringify(expected)) throw new Error('批次运行归属变化')
    await store(entry, next, next.runEpoch > 0 ? `${next.runEpoch}.json` : 'pending.json')
    expected = next
  }
  return {
    ...root,
    async reserve(runEpoch) {
      if (!Number.isSafeInteger(runEpoch) || runEpoch <= 0 || expected.phase !== 'preparing' || expected.runEpoch !== 0)
        throw new Error('批次运行代次无效')
      await update({ ...expected, runEpoch })
    },
    async prepared() {
      if (expected.phase !== 'preparing' || expected.runEpoch <= 0) throw new Error('批次准备状态无效')
      await update({ ...expected, phase: 'prepared' })
    },
    async accepted() {
      if (expected.phase !== 'prepared') throw new Error('批次接受状态无效')
      await update({ ...expected, phase: 'accepted' })
    },
    async release() { await removeBatchRun({ stateDirectory, batchId }, expected) },
  }
}

export async function readBatchRun({ stateDirectory, batchId }) {
  return (await readEntry(stateDirectory, batchId))?.owner ?? null
}

export async function listBatchRuns({ stateDirectory, identity }) {
  const base = path.join(stateDirectory, 'runs')
  let names
  try { names = await readdir(base) } catch (error) { if (error.code === 'ENOENT') return []; throw error }
  const owners = []
  for (const name of names.filter(value => !value.startsWith('.'))) {
    try {
      const owner = await readBatchRun({ stateDirectory, batchId: name })
      if (owner && owner.deviceId === identity.deviceId) owners.push(owner)
    } catch { /* 异常记录保留于对应批次。 */ }
  }
  return owners
}

export async function removeBatchRun({ stateDirectory, batchId }, expected) {
  const entry = await readEntry(stateDirectory, batchId)
  if (!entry || JSON.stringify(entry.owner) !== JSON.stringify(expected)) throw new Error('批次运行归属变化')
  const names = await readdir(entry.dir)
  const temporary = names.filter(name => /^\.journal-[a-f0-9-]+\.tmp$/.test(name))
  if (names.some(name => name !== entry.name && !temporary.includes(name))) throw new Error('批次运行目录包含未知记录')
  for (const name of temporary) await unlink(path.join(entry.dir, name))
  await unlink(path.join(entry.dir, entry.name))
  await syncDirectory(entry.dir)
  await rmdir(entry.dir)
  await syncDirectory(path.dirname(entry.dir))
}

/** 原宿主退出或完成进程内交接后，调用方负责停止证明与服务端确认。 */
export async function recoverBatchRun(config, confirmStopped, localProof) {
  const owner = await readBatchRun(config)
  if (!owner) return { recovered: false }
  if (owner.deviceId !== config.identity.deviceId) throw new Error('恢复设备不匹配')
  function requireOldHostGone() {
    if (matchesLocalRecoveryProof(localProof, owner, config.identity)) return
    try { process.kill(owner.processId, 0) }
    catch (error) { if (error.code === 'ESRCH') return; throw new Error('旧宿主状态无法确认') }
    throw new Error('旧宿主进程保持活动')
  }
  requireOldHostGone()
  await confirmStopped(Object.freeze({ ...owner }))
  requireOldHostGone()
  await removeBatchRun(config, owner)
  return { recovered: true, owner }
}
