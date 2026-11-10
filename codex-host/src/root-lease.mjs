import { mkdir, writeFile, readFile, rm, stat } from 'node:fs/promises'
import path from 'node:path'
import { HostError, validateProjectDirectory } from './device.mjs'

/** 物理目录锁不按租约时间抢占；残留锁需恢复流程证明旧写入单元停止。 */
export async function acquireProjectRoot({ stateDirectory, identity, projectId, projectDirectory }) {
  const root = await validateProjectDirectory(identity, { deviceId: identity.deviceId, projectDirectory })
  const physical = await stat(root.projectDirectory, { bigint: true })
  const locks = path.join(stateDirectory, 'roots')
  await mkdir(locks, { recursive: true, mode: 0o700 })
  const lock = path.join(locks, `${physical.dev}-${physical.ino}`)
  try { await mkdir(lock, { mode: 0o700 }) }
  catch (error) {
    if (error.code === 'EEXIST') throw new HostError(409, 'ROOT_IN_USE', '项目物理目录由其他协作运行持有')
    throw new HostError(500, 'ROOT_LOCK_FAILED', '项目目录锁创建失败')
  }
  const owner = { projectId, deviceId: identity.deviceId, hostInstanceId: identity.hostInstanceId,
    projectDirectory: root.projectDirectory, processId: process.pid }
  try { await writeFile(path.join(lock, 'owner.json'), JSON.stringify(owner), { flag: 'wx', mode: 0o600 }) }
  catch { await rm(lock, { recursive: true, force: true }); throw new HostError(500, 'ROOT_LOCK_FAILED', '项目目录锁保存失败') }
  let released = false
  return {
    ...root,
    async release() {
      if (released) return
      const current = JSON.parse(await readFile(path.join(lock, 'owner.json'), 'utf8'))
      if (current.hostInstanceId !== owner.hostInstanceId || current.projectId !== owner.projectId)
        throw new HostError(409, 'ROOT_OWNER_MISMATCH', '项目目录锁归属变化')
      await rm(lock, { recursive: true }); released = true
    },
  }
}

export async function readProjectRootOwner({ stateDirectory, identity, projectId, projectDirectory }) {
  const root = await validateProjectDirectory(identity, { deviceId: identity.deviceId, projectDirectory })
  const physical = await stat(root.projectDirectory, { bigint: true })
  const lock = path.join(stateDirectory, 'roots', `${physical.dev}-${physical.ino}`)
  let owner
  try { owner = JSON.parse(await readFile(path.join(lock, 'owner.json'), 'utf8')) }
  catch (error) {
    if (error.code === 'ENOENT') return null
    throw new HostError(409, 'ROOT_RECOVERY_REQUIRED', '项目目录锁需要恢复核验')
  }
  if (owner.deviceId !== identity.deviceId || owner.projectId !== projectId
      || owner.projectDirectory !== root.projectDirectory || !Number.isInteger(owner.processId)
      || owner.processId <= 0 || typeof owner.hostInstanceId !== 'string')
    throw new HostError(409, 'ROOT_OWNER_MISMATCH', '项目目录锁归属变化')
  return owner
}

/** confirmStopped 由恢复模块执行服务端逐尝试确认；任一失败保留目录锁。 */
export async function recoverProjectRoot(config, confirmStopped) {
  const owner = await readProjectRootOwner(config)
  if (!owner) return { recovered: false }
  function requireOldHostGone() {
    try { process.kill(owner.processId, 0) }
    catch (error) { if (error.code === 'ESRCH') return; throw new HostError(409, 'ROOT_RECOVERY_REQUIRED', '旧宿主进程状态无法确认') }
    throw new HostError(409, 'ROOT_IN_USE', '旧宿主进程保持活动')
  }
  requireOldHostGone()
  if (typeof confirmStopped !== 'function') throw new HostError(409, 'ROOT_RECOVERY_REQUIRED', '缺少执行停止恢复证明')
  await confirmStopped(Object.freeze({ ...owner }))
  requireOldHostGone()
  const current = await readProjectRootOwner(config)
  if (!current || JSON.stringify(current) !== JSON.stringify(owner))
    throw new HostError(409, 'ROOT_OWNER_MISMATCH', '项目目录锁归属变化')
  const physical = await stat(owner.projectDirectory, { bigint: true })
  await rm(path.join(config.stateDirectory, 'roots', `${physical.dev}-${physical.ino}`), { recursive: true })
  return { recovered: true, owner }
}
