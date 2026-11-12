import { constants, write } from 'node:fs'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { lstat, mkdir, open, readdir, readlink, realpath, rename, stat, unlink, link } from 'node:fs/promises'
import path from 'node:path'
import { TextDecoder, promisify } from 'node:util'

// This process runs inside the host's project sandbox. It never receives a shell command.
class Rejected extends Error {}
const reject = message => { throw new Rejected(message) }
const contentVersionOf = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`
const writePipe = promisify(write)
let committed = false
const cleanupPaths = []

function normalized(value) {
  if (typeof value !== 'string' || !value || value.startsWith('/') || value.includes('\\') || value.includes('\0')
      || /^[A-Za-z]:/.test(value)) reject('路径须为项目相对路径')
  const key = value.endsWith('/') ? value.slice(0, -1) : value
  if (!key || key.split('/').some(part => !part || part === '.' || part === '..')) reject('路径须为规范化相对路径')
  if (key.split('/').some(part => part.toLowerCase() === '.git')) reject('项目 Git 数据由宿主管理')
  return key
}

async function run(input) {
  const { root, rootDevice, rootInode, privateState, credentials, action, payload } = input
  const requireSafe = (...values) => {
    if (values.some(value => typeof value !== 'string' || credentials.some(secret => value.includes(secret))))
      reject('操作参数无效或包含执行凭据')
  }
  async function checkRoot() {
    const info = await stat(root, { bigint: true })
    if (!info.isDirectory() || info.dev.toString() !== rootDevice || info.ino.toString() !== rootInode
        || await realpath(root) !== root) reject('项目物理目录已变化')
  }
  async function targetFor(relative) {
    requireSafe(relative)
    const key = normalized(relative)
    await checkRoot()
    const target = path.join(root, key)
    if (target === privateState || target.startsWith(privateState + path.sep)) reject('宿主私有数据禁止访问')
    let current = root
    for (const component of key.split('/').slice(0, -1)) {
      if (!(await readdir(current)).includes(component)) reject('目标路径存在别名')
      current = path.join(current, component)
      const info = await lstat(current, { bigint: true })
      if (!info.isDirectory() || info.dev.toString() !== rootDevice || info.isSymbolicLink() || await realpath(current) !== current)
        reject('路径包含符号链接或别名')
    }
    if (current !== root && !current.startsWith(root + path.sep)) reject('路径超出项目')
    try {
      const info = await lstat(target, { bigint: true })
      if (!(await readdir(current)).includes(path.basename(target))) reject('目标路径存在别名')
      if (info.isSymbolicLink() || info.dev.toString() !== rootDevice) reject('路径包含符号链接或跨卷挂载')
      return { key, target, parent: current, exists: true }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
      return { key, target, parent: current, exists: false }
    }
  }
  async function readCurrent(item) {
    if (!item.exists) reject('文件不存在')
    const handle = await open(item.target, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    try {
      const info = await handle.stat({ bigint: true })
      if (!info.isFile() || info.nlink !== 1n || info.dev.toString() !== rootDevice) reject('目标不是可编辑的普通文件')
      const current = await lstat(item.target, { bigint: true })
      if (!current.isFile() || current.nlink !== 1n || current.dev !== info.dev || current.ino !== info.ino
          || await realpath(item.target) !== item.target
          || !(await readdir(item.parent)).includes(path.basename(item.target))) reject('文件身份发生变化')
      const bytes = await handle.readFile()
      const after = await handle.stat({ bigint: true })
      const named = await lstat(item.target, { bigint: true })
      if (after.dev !== info.dev || after.ino !== info.ino || after.size !== info.size
          || after.mtimeNs !== info.mtimeNs || after.ctimeNs !== info.ctimeNs
          || named.dev !== info.dev || named.ino !== info.ino || named.nlink !== 1n)
        reject('读取期间文件发生变化')
      let content
      try { content = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes) }
      catch { reject('文件不是有效的 UTF-8 文本') }
      requireSafe(content)
      const contentVersion = contentVersionOf(bytes)
      return { content, contentVersion, version: `${contentVersion}:${info.dev}:${info.ino}`,
        identity: { dev: info.dev, ino: info.ino, uid: info.uid, gid: info.gid,
          mode: info.mode & 0o7777n, ctimeNs: info.ctimeNs } }
    } finally { await handle.close() }
  }
  // Directory observations describe names and bytes, without following links.
  async function observe(paths, capture = false, changedPaths = []) {
    if (!Array.isArray(paths) || !Array.isArray(changedPaths)) reject('成果路径无效')
    const compare = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))
    const stable = (a, b) => a.dev === b.dev && a.ino === b.ino && a.mode === b.mode
      && a.size === b.size && a.mtimeNs === b.mtimeNs && a.ctimeNs === b.ctimeNs
    const files = new Map(), directories = new Set(), links = new Map(), deletedPaths = new Set()
    let offset = 0
    async function walk(key, expect, entries, checks, recurse = true) {
      const target = path.join(root, key)
      if (target === privateState || target.startsWith(privateState + path.sep)) reject('宿主私有数据禁止访问')
      const info = await lstat(target, { bigint: true })
      if (info.dev.toString() !== rootDevice) reject('成果路径跨卷')
      checks.push({ target, info })
      if (info.isSymbolicLink()) {
        if (expect || !recurse) reject('成果路径包含符号链接')
        const value = await readlink(target)
        requireSafe(value)
        if (!stable(info, await lstat(target, { bigint: true }))) reject('读取期间成果发生变化')
        entries.push({ path: key, type: 'symlink', target: value })
        if (capture) links.set(key, value)
        return
      }
      if (await realpath(target) !== target) reject('成果路径包含别名')
      if (info.isDirectory()) {
        if (expect === 'file') reject('成果路径类型不匹配')
        entries.push({ path: key, type: 'directory' })
        if (capture) directories.add(key)
        if (!recurse) return
        const names = (await readdir(target)).sort(compare)
        for (const name of names) {
          requireSafe(name)
          if (name.toLowerCase() === '.git') reject('项目 Git 数据由宿主管理')
          await walk(`${key}/${name}`, null, entries, checks)
        }
        if (!stable(info, await lstat(target, { bigint: true }))
            || JSON.stringify(names) !== JSON.stringify((await readdir(target)).sort(compare))) reject('读取期间成果发生变化')
        return
      }
      if (!info.isFile() || expect === 'directory' || info.nlink !== 1n) reject('成果不是可读取的普通文件')
      const handle = await open(target, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
      try {
        const before = await handle.stat({ bigint: true })
        if (!stable(info, before) || before.nlink !== 1n) reject('成果文件身份发生变化')
        const needles = credentials.map(value => Buffer.from(value))
        const carrySize = Math.max(0, ...needles.map(value => value.length - 1))
        let carry = Buffer.alloc(0), size = 0
        const start = offset, hash = createHash('sha256'), buffer = Buffer.alloc(65536)
        for (;;) {
          const { bytesRead } = await handle.read(buffer, 0, buffer.length, null)
          if (!bytesRead) break
          const chunk = buffer.subarray(0, bytesRead), scanned = Buffer.concat([carry, chunk])
          if (needles.some(needle => scanned.includes(needle))) reject('成果包含执行凭据')
          carry = carrySize ? Buffer.from(scanned.subarray(Math.max(0, scanned.length - carrySize))) : Buffer.alloc(0)
          hash.update(chunk)
          size += bytesRead
          if (capture) {
            let written = 0
            while (written < bytesRead) {
              const result = await writePipe(4, chunk, written, bytesRead - written, null)
              if (!result.bytesWritten) reject('快照写入失败')
              written += result.bytesWritten
            }
            offset += bytesRead
          }
        }
        const after = await handle.stat({ bigint: true })
        const named = await lstat(target, { bigint: true })
        if (!stable(before, after) || !stable(before, named) || named.nlink !== 1n || await realpath(target) !== target)
          reject('读取期间成果发生变化')
        const contentVersion = `sha256:${hash.digest('hex')}`
        entries.push({ path: key, type: 'file', contentVersion })
        if (capture) files.set(key, { path: key, sha256: contentVersion.slice(7), offset: start, size })
      } finally { await handle.close() }
    }
    async function inspect(value, typed) {
      requireSafe(value)
      const key = normalized(value)
      const expect = typed ? (value.endsWith('/') ? 'directory' : 'file') : null
      let parent = root
      for (const part of key.split('/')) {
        const names = await readdir(parent)
        if (!names.includes(part)) {
          try { await lstat(path.join(parent, part)); reject('成果路径存在别名') }
          catch (error) { if (error.code !== 'ENOENT') throw error }
          // A second read distinguishes actual absence from a concurrent change.
          if ((await readdir(parent)).includes(part)) reject('读取期间成果发生变化')
          return { path: value, state: 'MISSING' }
        }
        const candidate = path.join(parent, part)
        if (candidate === privateState || candidate.startsWith(privateState + path.sep)) reject('宿主私有数据禁止访问')
        const info = await lstat(candidate, { bigint: true })
        if (candidate !== path.join(root, key)) {
          if (info.isFile() && info.nlink === 1n && await realpath(candidate) === candidate
              && stable(info, await lstat(candidate, { bigint: true }))) return { path: value, state: 'MISSING' }
          if (!info.isDirectory() || info.isSymbolicLink() || await realpath(candidate) !== candidate) reject('成果路径包含符号链接或别名')
          parent = candidate
        } else if (typed && ((expect === 'file' && info.isDirectory()) || (expect === 'directory' && info.isFile()))) {
          if ((info.isFile() && info.nlink !== 1n) || await realpath(candidate) !== candidate
              || !stable(info, await lstat(candidate, { bigint: true }))) reject('成果文件身份发生变化')
          return { path: value, state: 'MISSING' }
        }
      }
      const entries = [], checks = []
      await walk(key, expect, entries, checks, typed)
      for (const checked of checks) {
        if (!stable(checked.info, await lstat(checked.target, { bigint: true }))) reject('读取期间成果发生变化')
      }
      entries.sort((a, b) => compare(a.path, b.path))
      const version = entries[0].type === 'file' ? entries[0].contentVersion
        : contentVersionOf(Buffer.from(JSON.stringify(entries.map(entry => ({ ...entry,
          path: entry.path === key ? '' : entry.path.slice(key.length + 1) })))))
      return { path: value, state: 'PRESENT', contentVersion: version }
    }
    await checkRoot()
    if (new Set(paths).size !== paths.length) reject('成果路径重复')
    const result = []
    for (const value of [...paths].sort(compare)) result.push(await inspect(value, true))
    if (capture) {
      for (const value of [...new Set(changedPaths)].sort(compare)) {
        if (files.has(value) || directories.has(value) || links.has(value)) continue
        const fact = await inspect(value, false)
        if (fact.state === 'MISSING') deletedPaths.add(value)
      }
    }
    await checkRoot()
    return capture ? { observations: result, files: [...files.values()], directories: [...directories],
      links: [...links].map(([path, target]) => ({ path, target })), deletedPaths: [...deletedPaths] } : result
  }
  async function checkedCurrent(item, expected) {
    if (typeof expected !== 'string' || !/^sha256:[a-f0-9]{64}:[0-9]+:[0-9]+$/.test(expected)) reject('缺少读取版本')
    const current = await readCurrent(item)
    if (current.version !== expected) reject('文件内容已变化，请重新读取')
    return current
  }
  async function writeTemporary(parent, content, mode) {
    requireSafe(content)
    const temporary = path.join(parent, `.linear-lite-edit-${randomUUID()}.tmp`)
    const handle = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, mode)
    try { await handle.chmod(mode); await handle.writeFile(content, 'utf8'); await handle.sync() }
    catch (error) { await handle.close(); await cleanupTemporary(temporary); throw error }
    await handle.close()
    return temporary
  }
  async function cleanupTemporary(temporary) {
    try { await unlink(temporary) }
    catch (error) {
      if (error.code === 'ENOENT') return
      committed = true
      cleanupPaths.push(path.relative(root, temporary).split(path.sep).join('/'))
      reject('临时文件清理结果待确认')
    }
  }
  async function ensureWritable(item, expected) {
    const handle = await open(item.target, constants.O_WRONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    try {
      const actual = await handle.stat({ bigint: true })
      if (!actual.isFile() || actual.nlink !== 1n || actual.dev !== expected.dev || actual.ino !== expected.ino)
        reject('文件身份发生变化')
    } finally { await handle.close() }
  }
  async function copyWithMetadata(sourceHandle, destinationHandle) {
    await new Promise((resolve, rejectCopy) => {
      const child = spawn('/bin/cp', ['-p', '/dev/fd/3', '/dev/fd/4'], {
        stdio: ['pipe', 'pipe', 'pipe', sourceHandle.fd, destinationHandle.fd], env: {}, detached: false,
      })
      child.stdin.end()
      child.stdout.resume()
      child.stderr.resume()
      let error = false
      child.once('error', () => { error = true })
      child.once('close', code => error || code !== 0 ? rejectCopy(new Rejected('无法保留文件元数据')) : resolve())
    })
  }
  function metadataMatches(left, right) {
    return left.dev === right.dev && left.ino === right.ino && left.uid === right.uid
      && left.gid === right.gid && left.mode === right.mode && left.ctimeNs === right.ctimeNs
  }
  function ownershipMatches(source, copy) {
    return source.uid === copy.uid && source.gid === copy.gid && source.mode === copy.mode
  }
  async function replace(item, version, content) {
    const current = await checkedCurrent(item, version)
    requireSafe(content)
    await ensureWritable(item, current.identity)
    if (current.content === content) return { changedPaths: [], artifactObservations: [], previousContentVersion: null }
    const temporary = path.join(item.parent, `.linear-lite-edit-${randomUUID()}.tmp`)
    const reserved = await open(temporary, constants.O_RDWR | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600)
    let reservedIdentity
    try {
      reservedIdentity = await reserved.stat({ bigint: true })
      const sourceHandle = await open(item.target, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
      try {
        const before = await sourceHandle.stat({ bigint: true })
        if (!before.isFile() || before.nlink !== 1n || !metadataMatches(current.identity,
          { dev: before.dev, ino: before.ino, uid: before.uid, gid: before.gid,
            mode: before.mode & 0o7777n, ctimeNs: before.ctimeNs })) reject('文件身份或元数据发生变化')
        await copyWithMetadata(sourceHandle, reserved)
        // cp preserves ACL and xattrs through pinned descriptors; /dev/fd reports
        // a synthetic mode, so restore the checked source's POSIX bits.
        await reserved.chmod(Number(current.identity.mode))
        const copied = await reserved.stat({ bigint: true })
        if (!copied.isFile() || copied.nlink !== 1n || copied.dev !== reservedIdentity.dev
            || copied.ino !== reservedIdentity.ino || !ownershipMatches(current.identity,
              { uid: copied.uid, gid: copied.gid, mode: copied.mode & 0o7777n })) reject('无法保留文件元数据')
      } finally { await sourceHandle.close(); await reserved.close() }
      const stageKey = path.relative(root, temporary).split(path.sep).join('/')
      const stageItem = await targetFor(stageKey)
      const staged = await readCurrent(stageItem)
      const sourceAfterCopy = await checkedCurrent(await targetFor(item.key), version)
      if (!metadataMatches(current.identity, sourceAfterCopy.identity)) reject('文件身份或元数据发生变化')
      if (staged.identity.dev !== reservedIdentity.dev || staged.identity.ino !== reservedIdentity.ino) reject('文件身份或元数据发生变化')
      if (!ownershipMatches(current.identity, staged.identity)) reject('文件身份或元数据发生变化')
      if (staged.contentVersion !== current.contentVersion) reject('文件身份或元数据发生变化')
      const handle = await open(temporary, constants.O_WRONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
      try {
        const before = await handle.stat({ bigint: true })
        if (!before.isFile() || before.nlink !== 1n || before.dev !== reservedIdentity.dev
            || before.ino !== reservedIdentity.ino || !ownershipMatches(current.identity,
              { uid: before.uid, gid: before.gid, mode: before.mode & 0o7777n })) reject('临时文件身份或元数据发生变化')
        await handle.truncate(0)
        await handle.writeFile(content, 'utf8')
        await handle.sync()
        const after = await handle.stat({ bigint: true })
        if (!after.isFile() || after.nlink !== 1n || after.dev !== before.dev || after.ino !== before.ino
            || !ownershipMatches(current.identity, { uid: after.uid, gid: after.gid, mode: after.mode & 0o7777n }))
          reject('无法保留文件元数据')
      } finally { await handle.close() }
      const sourceBeforeCommit = await checkedCurrent(await targetFor(item.key), version)
      const stageBeforeCommit = await targetFor(stageKey)
      const stageInfo = await lstat(stageBeforeCommit.target, { bigint: true })
      if (!metadataMatches(current.identity, sourceBeforeCommit.identity) || !stageInfo.isFile() || stageInfo.nlink !== 1n
          || stageInfo.dev !== reservedIdentity.dev || stageInfo.ino !== reservedIdentity.ino
          || !ownershipMatches(current.identity, { uid: stageInfo.uid, gid: stageInfo.gid, mode: stageInfo.mode & 0o7777n }))
        reject('文件身份或元数据发生变化')
      await rename(temporary, item.target)
      committed = true
      return { changedPaths: [item.key], artifactObservations: [{ path: item.key, state: 'PRESENT',
        contentVersion: contentVersionOf(Buffer.from(content, 'utf8')) }], previousContentVersion: current.contentVersion }
    } finally { await reserved.close().catch(() => {}); await cleanupTemporary(temporary) }
  }

  if (action === 'observe' || action === 'capture') return observe(payload.paths, action === 'capture', payload.changedPaths || [])
  if (action === 'list') {
    const { path: relative, after } = payload
    if (after !== undefined && (normalized(after) !== after || after.includes('/'))) reject('目录分页位置无效')
    if (after !== undefined) requireSafe(after)
    const directory = relative === '.' ? root : (await targetFor(relative)).target
    await checkRoot()
    const before = await lstat(directory, { bigint: true })
    if (!before.isDirectory() || before.dev.toString() !== rootDevice || await realpath(directory) !== directory)
      reject('目录身份无效')
    const entries = (await readdir(directory, { withFileTypes: true }))
      .filter(entry => (entry.isFile() || entry.isDirectory()) && entry.name.toLowerCase() !== '.git'
        && !entry.name.includes('\\') && path.join(directory, entry.name) !== privateState
        && (after === undefined || entry.name > after))
      .sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
    const page = entries.slice(0, 100).map(entry => {
      const key = relative === '.' ? entry.name : `${normalized(relative)}/${entry.name}`
      requireSafe(key)
      return { path: key, type: entry.isDirectory() ? 'directory' : 'file' }
    })
    const current = await lstat(directory, { bigint: true })
    if (current.dev !== before.dev || current.ino !== before.ino || await realpath(directory) !== directory)
      reject('目录身份发生变化')
    return { entries: page, nextAfter: entries.length > 100 ? entries[99].name : null }
  }
  if (action === 'read') {
    const item = await targetFor(payload.path)
    const value = await readCurrent(item)
    return { path: item.key, content: value.content, contentVersion: value.contentVersion, version: value.version }
  }
  if (action === 'mkdir') {
    const item = await targetFor(payload.path)
    if (item.exists) reject('目标已存在')
    await mkdir(item.target)
    committed = true
    return { changedPaths: [item.key], artifactObservations: [], previousContentVersion: null }
  }
  if (action === 'create') {
    const item = await targetFor(payload.path)
    if (item.exists) reject('目标已存在')
    requireSafe(payload.content)
    const temporary = await writeTemporary(item.parent, payload.content, 0o644)
    try {
      if ((await targetFor(item.key)).exists) reject('目标已存在')
      await link(temporary, item.target)
      committed = true
      return { changedPaths: [item.key], artifactObservations: [{ path: item.key, state: 'PRESENT',
        contentVersion: contentVersionOf(Buffer.from(payload.content, 'utf8')) }], previousContentVersion: null }
    } finally { await cleanupTemporary(temporary) }
  }
  if (action === 'patch') {
    const item = await targetFor(payload.path)
    const current = await checkedCurrent(item, payload.version)
    requireSafe(payload.oldText, payload.newText)
    if (!payload.oldText) reject('补丁上下文不能为空')
    const index = current.content.indexOf(payload.oldText)
    if (index < 0 || index !== current.content.lastIndexOf(payload.oldText)) reject('补丁上下文不存在或有歧义')
    const content = current.content.slice(0, index) + payload.newText + current.content.slice(index + payload.oldText.length)
    return replace(item, payload.version, content)
  }
  if (action === 'replace') {
    const item = await targetFor(payload.path)
    return replace(item, payload.version, payload.content)
  }
  if (action === 'remove') {
    const item = await targetFor(payload.path)
    await checkedCurrent(item, payload.version)
    await checkedCurrent(await targetFor(item.key), payload.version)
    await unlink(item.target)
    committed = true
    return { changedPaths: [item.key], artifactObservations: [], previousContentVersion: null }
  }
  if (action === 'rename') {
    const source = await targetFor(payload.from), destination = await targetFor(payload.to)
    if (source.key === destination.key) reject('重命名目标无效')
    if (destination.exists) reject('目标已存在')
    await checkedCurrent(source, payload.version)
    const current = await checkedCurrent(await targetFor(source.key), payload.version)
    if ((await targetFor(destination.key)).exists) reject('目标已存在')
    await link(source.target, destination.target)
    committed = true
    const handle = await open(destination.target, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    try {
      const before = await handle.stat({ bigint: true })
      if (!before.isFile() || before.dev !== current.identity.dev || before.ino !== current.identity.ino)
        reject('重命名的文件身份发生变化')
      const bytes = await handle.readFile()
      const after = await handle.stat({ bigint: true })
      if (after.dev !== before.dev || after.ino !== before.ino || after.size !== before.size
          || after.mtimeNs !== before.mtimeNs || contentVersionOf(bytes) !== current.contentVersion)
        reject('重命名的文件内容发生变化')
      const sourceInfo = await lstat(source.target, { bigint: true })
      if (sourceInfo.dev !== before.dev || sourceInfo.ino !== before.ino)
        reject('重命名的文件身份发生变化')
    } finally { await handle.close() }
    await unlink(source.target)
    return { changedPaths: [source.key, destination.key], artifactObservations: [{ path: destination.key,
      state: 'PRESENT', contentVersion: current.contentVersion }], previousContentVersion: current.contentVersion,
      previousPath: source.key }
  }
  reject('编辑操作无效')
}

let body = ''
process.stdin.setEncoding('utf8')
for await (const chunk of process.stdin) body += chunk
try {
  const result = await run(JSON.parse(body))
  process.stdout.write(JSON.stringify({ ok: true, result }))
} catch (error) {
  let message = '文件操作失败'
  if (error instanceof Rejected) message = error.message
  else if (error.code === 'EEXIST') message = '目标已存在或发生冲突'
  else if (error.code === 'ENOENT') message = '文件或目录不存在'
  else if (error.code === 'EPERM' || error.code === 'EACCES') message = '项目边界禁止此操作'
  process.stdout.write(JSON.stringify({ ok: false, committed, cleanupPaths, message }))
}
