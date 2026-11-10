import { constants } from 'node:fs'
import { lstat, mkdir, open, readdir, realpath, stat, unlink } from 'node:fs/promises'
import path from 'node:path'

function normalized(value) {
  if (typeof value !== 'string' || !value || value.startsWith('/') || value.includes('\\') || value.includes('\0')
      || /^[A-Za-z]:/.test(value)) throw new Error('路径须为项目相对路径')
  const key = value.endsWith('/') ? value.slice(0, -1) : value
  if (!key || key.split('/').some(part => !part || part === '.' || part === '..')) throw new Error('路径须为规范化相对路径')
  return key
}

/** 宿主持有唯一项目写入能力；封存后拒绝新写入并等待在途操作。 */
export async function createWriteGate({ projectDirectory, stateDirectory, writeScopes, isAuthorized, knownCredentials }) {
  if (typeof projectDirectory !== 'string' || !path.isAbsolute(projectDirectory)
      || await realpath(projectDirectory) !== projectDirectory || typeof isAuthorized !== 'function'
      || typeof stateDirectory !== 'string' || !path.isAbsolute(stateDirectory)
      || !Array.isArray(writeScopes) || !Array.isArray(knownCredentials)
      || knownCredentials.some(value => typeof value !== 'string' || !value)) throw new Error('写入边界无效')
  const privateState = await realpath(stateDirectory)
  const rootStat = await stat(projectDirectory, { bigint: true })
  if (!rootStat.isDirectory()) throw new Error('项目目录无效')
  const scopes = writeScopes.map(normalized)
  let accepting = true
  let tail = Promise.resolve()
  const changed = new Set()

  function requireOpen() {
    if (!accepting || !isAuthorized()) throw new Error('写入能力已停止或租约失效')
  }

  async function targetFor(relative) {
    const key = normalized(relative)
    if (key.split('/').some(part => part.toLowerCase() === '.git')) throw new Error('项目 Git 数据由宿主管理')
    if (!scopes.some(scope => key === scope || key.startsWith(scope + '/'))) throw new Error('路径超出持有范围')
    const rootNow = await stat(projectDirectory, { bigint: true })
    if (rootNow.dev !== rootStat.dev || rootNow.ino !== rootStat.ino) throw new Error('项目物理目录已变化')
    const target = path.join(projectDirectory, key)
    if (target === privateState || target.startsWith(privateState + path.sep)) throw new Error('宿主私有数据禁止修改')
    const parent = await realpath(path.dirname(target))
    const expected = path.dirname(target)
    if (parent !== expected || (parent !== projectDirectory && !parent.startsWith(projectDirectory + path.sep)))
      throw new Error('路径包含符号链接或别名')
    let current = projectDirectory
    for (const component of key.split('/').slice(0, -1)) {
      if (!(await readdir(current)).includes(component)) throw new Error('目标路径存在别名')
      current = path.join(current, component)
    }
    try {
      const entry = await lstat(target)
      if (entry.isSymbolicLink()) throw new Error('路径包含符号链接或别名')
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    return { key, target }
  }

  function enqueue(action) {
    requireOpen()
    const work = tail.then(async () => { requireOpen(); return action() })
    tail = work.catch(() => {})
    return work
  }

  return {
    async writeFile(relative, content) {
      if (typeof relative !== 'string' || typeof content !== 'string') throw new Error('文件参数无效')
      if (knownCredentials.some(value => relative.includes(value) || content.includes(value)))
        throw new Error('文件内容包含执行凭据')
      return enqueue(async () => {
        const { key, target } = await targetFor(relative)
        requireOpen()
        try {
          if (!(await lstat(target)).isFile()) throw new Error('目标不是普通文件')
        } catch (error) { if (error.code !== 'ENOENT') throw error }
        const handle = await open(target, constants.O_WRONLY | constants.O_CREAT | constants.O_NOFOLLOW | constants.O_NONBLOCK, 0o644)
        try {
          if (!(await readdir(path.dirname(target))).includes(path.basename(target)) || await realpath(target) !== target)
            throw new Error('目标路径存在别名')
          const info = await handle.stat()
          if (!info.isFile() || info.nlink > 1) throw new Error('目标不是可写的独占普通文件')
          changed.add(key)
          await handle.truncate(0)
          await handle.writeFile(content, 'utf8')
        } finally { await handle.close() }
        return { path: key }
      })
    },
    async makeDirectory(relative) {
      if (typeof relative !== 'string' || knownCredentials.some(value => relative.includes(value)))
        throw new Error('路径无效')
      return enqueue(async () => {
        const { key, target } = await targetFor(relative)
        requireOpen()
        try { await mkdir(target); changed.add(key) }
        catch (error) { if (error.code !== 'EEXIST' || !(await lstat(target)).isDirectory()) throw error }
        if (!(await readdir(path.dirname(target))).includes(path.basename(target)) || await realpath(target) !== target)
          throw new Error('目标路径存在别名')
        return { path: key }
      })
    },
    async removeFile(relative) {
      if (typeof relative !== 'string' || knownCredentials.some(value => relative.includes(value))) throw new Error('路径无效')
      return enqueue(async () => {
        const { key, target } = await targetFor(relative)
        requireOpen()
        const info = await lstat(target)
        if (!info.isFile() || info.nlink > 1 || !(await readdir(path.dirname(target))).includes(path.basename(target))
            || await realpath(target) !== target) throw new Error('目标不是可删除的独占普通文件')
        await unlink(target)
        changed.add(key)
        return { path: key }
      })
    },
    async sealAndDrain() { accepting = false; await tail },
    changedPaths() { return [...changed] },
    get accepting() { return accepting },
  }
}
