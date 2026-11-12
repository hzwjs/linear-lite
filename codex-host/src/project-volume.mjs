import { execFile } from 'node:child_process'
import { lstat, realpath, stat } from 'node:fs/promises'
import path from 'node:path'

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const systemEnv = { PATH: '/usr/bin:/bin:/usr/sbin:/sbin' }

function runHelper(helperPath, directory) {
  return new Promise((resolve, reject) => {
    execFile(helperPath, [directory], { encoding: 'utf8', env: systemEnv, timeout: 10_000, maxBuffer: 1024 * 1024 },
      (error, stdout) => {
        if (error) return reject(error)
        try { resolve(JSON.parse(stdout)) } catch (parseError) { reject(parseError) }
      })
  })
}

function validateDirectory(value) {
  return typeof value === 'string' && path.isAbsolute(value) && path.normalize(value) === value
    && value !== path.parse(value).root && !value.endsWith(path.sep)
}

function identityOf(info) {
  if (!info.isDirectory() || typeof info.dev !== 'bigint' || typeof info.ino !== 'bigint'
      || info.dev < 0n || info.ino <= 0n) throw new Error('项目卷目录身份无效')
  return { device: info.dev.toString(), inode: info.ino.toString() }
}

function sameSnapshot(first, second) {
  return !!second && first.projectDirectory === second.projectDirectory
    && first.volumeDirectory === second.volumeDirectory && first.volumeParentDirectory === second.volumeParentDirectory
    && first.stateDirectory === second.stateDirectory && first.codexHome === second.codexHome
    && ['root', 'base', 'volumeParent', 'state', 'codex'].every(key => second[key]
      && first[key].device === second[key].device && first[key].inode === second[key].inode)
}

function matchesIdentity(info, identity) {
  return typeof info.dev === 'bigint' && typeof info.ino === 'bigint'
    && info.dev.toString() === identity.device && info.ino.toString() === identity.inode
}

async function hasProjectAncestor(candidate, identity, fs, includeSelf = true) {
  let current = includeSelf ? candidate : path.dirname(candidate)
  for (;;) {
    const info = await fs.stat(current, { bigint: true })
    if (!info.isDirectory() || typeof info.dev !== 'bigint' || typeof info.ino !== 'bigint')
      throw new Error('挂载祖先目录无效')
    if (matchesIdentity(info, identity)) return true
    const parent = path.dirname(current)
    if (parent === current) return false
    current = parent
  }
}

async function snapshot(projectDirectory, stateDirectory, codexHome, fs) {
  const parent = path.dirname(projectDirectory)
  const volumeParent = path.dirname(parent)
  if (parent === volumeParent) throw new Error('项目卷目录无效')
  const rootLink = await fs.lstat(projectDirectory, { bigint: true })
  if (rootLink.isSymbolicLink() || !rootLink.isDirectory()) throw new Error('项目目录不能是符号链接')
  const [root, parentPath, volumeParentPath, statePath, codexPath] = await Promise.all([
    fs.realpath(projectDirectory), fs.realpath(parent), fs.realpath(volumeParent),
    fs.realpath(stateDirectory), fs.realpath(codexHome),
  ])
  if (root !== projectDirectory || parentPath !== parent || volumeParentPath !== volumeParent)
    throw new Error('项目目录不是规范路径')
  const [rootStat, parentStat, volumeParentStat, stateStat, codexStat] = await Promise.all([
    fs.stat(root, { bigint: true }), fs.stat(parentPath, { bigint: true }),
    fs.stat(volumeParentPath, { bigint: true }), fs.stat(statePath, { bigint: true }), fs.stat(codexPath, { bigint: true }),
  ])
  const rootId = identityOf(rootStat)
  if (rootLink.dev !== rootStat.dev || rootLink.ino !== rootStat.ino) throw new Error('项目目录已变化')
  return { projectDirectory: root, root: rootId, volumeDirectory: parentPath, base: identityOf(parentStat),
    volumeParentDirectory: volumeParentPath, volumeParent: identityOf(volumeParentStat),
    stateDirectory: statePath, state: identityOf(stateStat), codexHome: codexPath, codex: identityOf(codexStat) }
}

/** 卷 UUID 与 helper 路径由可信配置提供；该检查不证明卷内文件安全。 */
export async function readProjectVolumeIdentity({ projectDirectory, volumeUUID, stateDirectory, codexHome, helperPath }, dependencies = {}) {
  const fs = dependencies.fs ?? { lstat, realpath, stat }
  const platform = dependencies.platform ?? process.platform
  const inspect = dependencies.runHelper ?? runHelper
  if (platform !== 'darwin') throw new Error('项目卷校验不支持此平台')
  if (![projectDirectory, stateDirectory, codexHome, helperPath].every(validateDirectory)
      || typeof volumeUUID !== 'string' || !uuidPattern.test(volumeUUID)) throw new Error('项目卷配置无效')
  try {
    const first = await snapshot(projectDirectory, stateDirectory, codexHome, fs)
    const helper = await fs.lstat(helperPath, { bigint: true })
    if (helper.isSymbolicLink() || !helper.isFile() || typeof helper.dev !== 'bigint'
        || typeof helper.ino !== 'bigint' || helper.ino <= 0n || await fs.realpath(helperPath) !== helperPath
        || await hasProjectAncestor(path.dirname(helperPath), first.base, fs)) throw new Error('helper 路径无效')
    const proof = await inspect(helperPath, projectDirectory)
    const volume = proof?.base, project = proof?.root
    if (!volume || !project || volume.fsType !== 'apfs' || project.fsType !== 'apfs'
        || typeof volume.volumeUUID !== 'string' || !uuidPattern.test(volume.volumeUUID)
        || volume.volumeUUID.toUpperCase() !== volumeUUID.toUpperCase()
        || typeof project.volumeUUID !== 'string' || !uuidPattern.test(project.volumeUUID)
        || project.volumeUUID.toUpperCase() !== volumeUUID.toUpperCase()
        || volume.device !== first.base.device || volume.inode !== first.base.inode
        || project.device !== first.root.device || project.inode !== first.root.inode
        || !validateDirectory(volume.mountPoint) || !validateDirectory(project.mountPoint)
        || !Number.isInteger(volume.mountFlags) || volume.mountFlags < 0 || volume.mountFlags > 0xffffffff
        || volume.mountFlags !== project.mountFlags || (volume.mountFlags & 0x00200000) !== 0)
      throw new Error('项目卷身份无效')
    for (const mountPoint of [volume.mountPoint, project.mountPoint]) {
      const mountedRoot = await fs.stat(await fs.realpath(mountPoint), { bigint: true })
      if (!mountedRoot.isDirectory() || !matchesIdentity(mountedRoot, first.base)) throw new Error('卷挂载点无效')
    }
    if (first.root.device !== first.base.device || first.root.inode === first.base.inode
        || [first.volumeParent.device, first.state.device, first.codex.device].includes(first.root.device))
      throw new Error('项目卷未与私有目录分离')
    if (await hasProjectAncestor(first.stateDirectory, first.base, fs)
        || await hasProjectAncestor(first.codexHome, first.base, fs))
      throw new Error('私有目录处于项目内')
    const mounts = proof?.mounts
    if (!Array.isArray(mounts) || mounts.length === 0) throw new Error('挂载列表无效')
    let baseMounts = 0
    for (const mount of mounts) {
      if (typeof mount !== 'string' || !path.isAbsolute(mount) || path.normalize(mount) !== mount)
        throw new Error('挂载列表无效')
      const actual = await fs.realpath(mount)
      const mountIdentity = await fs.stat(actual, { bigint: true })
      if (!mountIdentity.isDirectory() || typeof mountIdentity.dev !== 'bigint' || typeof mountIdentity.ino !== 'bigint')
        throw new Error('挂载目录身份无效')
      if (matchesIdentity(mountIdentity, first.base)) baseMounts++
      if (await hasProjectAncestor(actual, first.base, fs, false))
        throw new Error('项目卷内存在嵌套挂载')
    }
    if (baseMounts !== 1) throw new Error('项目挂载点无法确认')
    const last = await snapshot(projectDirectory, stateDirectory, codexHome, fs)
    if (!sameSnapshot(first, last)) throw new Error('项目卷目录身份已变化')
    const helperAfter = await fs.lstat(helperPath, { bigint: true })
    if (!helperAfter.isFile() || helperAfter.isSymbolicLink() || helperAfter.ino !== helper.ino
        || helperAfter.dev !== helper.dev) throw new Error('helper 身份已变化')
    return { ...first, volumeUUID: volume.volumeUUID.toUpperCase(), mountFlags: volume.mountFlags, helperPath,
      helper: { device: helper.dev.toString(), inode: helper.ino.toString() } }
  } catch {
    throw new Error('项目卷身份无法确认')
  }
}

/** 批次运行前持有首次身份，每次使用时重读并比较。 */
export async function requireProjectVolumeIdentity(options, expected, dependencies = {}) {
  if (!expected || typeof expected !== 'object') throw new Error('缺少项目卷身份')
  const current = await readProjectVolumeIdentity(options, dependencies)
  if (current.volumeUUID !== expected.volumeUUID || current.mountFlags !== expected.mountFlags
      || current.helperPath !== expected.helperPath
      || !expected.helper || current.helper.device !== expected.helper.device
      || current.helper.inode !== expected.helper.inode || !sameSnapshot(current, expected))
    throw new Error('项目卷身份已变化')
  return current
}
