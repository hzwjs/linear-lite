import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile, link, unlink, realpath, stat, readdir } from 'node:fs/promises'
import path from 'node:path'

export class HostError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code }
}

export async function loadHostIdentity(stateDirectory) {
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 })
  const filename = path.join(stateDirectory, 'device.json')
  const temporary = path.join(stateDirectory, `.device-${randomUUID()}.tmp`)
  try {
    await writeFile(temporary, JSON.stringify({ deviceId: randomUUID() }), { mode: 0o600, flag: 'wx' })
    try { await link(temporary, filename) } catch (error) { if (error.code !== 'EEXIST') throw error }
  } finally { await unlink(temporary).catch(() => {}) }
  let stored
  try { stored = JSON.parse(await readFile(filename, 'utf8')) }
  catch { throw new HostError(500, 'IDENTITY_INVALID', '宿主设备标识无法读取') }
  if (typeof stored.deviceId !== 'string' || !/^[a-f0-9-]{36}$/.test(stored.deviceId))
    throw new HostError(500, 'IDENTITY_INVALID', '宿主设备标识无效')
  return Object.freeze({ deviceId: stored.deviceId, hostInstanceId: randomUUID() })
}

export async function validateProjectDirectory(identity, input) {
  if (!input || Object.keys(input).some(key => !['deviceId', 'projectDirectory'].includes(key))
      || typeof input.deviceId !== 'string' || typeof input.projectDirectory !== 'string')
    throw new HostError(400, 'PARAMETER_INVALID', '目录参数无效')
  if (input.deviceId !== identity.deviceId)
    throw new HostError(409, 'DEVICE_MISMATCH', '设备标识与本机宿主不匹配')
  const declared = input.projectDirectory
  if (!declared || declared !== declared.trim() || !path.isAbsolute(declared) || declared.includes('\0'))
    throw new HostError(400, 'DIRECTORY_INVALID', '项目目录须为本机绝对路径')
  try {
    const projectDirectory = await realpath(declared)
    if (!(await stat(projectDirectory)).isDirectory())
      throw new HostError(400, 'DIRECTORY_INVALID', '项目目录不存在或不是目录')
    if (projectDirectory !== projectDirectory.trim())
      throw new HostError(400, 'DIRECTORY_INVALID', '项目目录声明无效')
    // realpath 不保证在大小写不敏感文件系统中转换拼写；目录项逐段精确核验。
    let parent = path.parse(projectDirectory).root
    for (const part of projectDirectory.slice(parent.length).split(path.sep).filter(Boolean)) {
      if (!(await readdir(parent)).includes(part))
        throw new HostError(400, 'DIRECTORY_INVALID', '项目目录包含大小写或名称别名')
      parent = path.join(parent, part)
    }
    return { deviceId: identity.deviceId, projectDirectory, docsDirectory: path.join(projectDirectory, 'docs') }
  } catch (error) {
    if (error instanceof HostError) throw error
    throw new HostError(400, 'DIRECTORY_INVALID', '项目目录不存在或无法访问')
  }
}
