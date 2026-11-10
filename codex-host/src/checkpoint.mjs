import { createHash, randomUUID } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import { lstat, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'

function safePath(value, knownCredentials) {
  if (typeof value !== 'string' || !value || value.startsWith('/') || value.includes('\\') || value.includes('\0')
      || /^[A-Za-z]:/.test(value) || value.endsWith('/') || value.split('/').some(x => !x || x === '.' || x === '..' || x.toLowerCase() === '.git')
      || knownCredentials.some(secret => value.includes(secret))) throw new Error('成果路径无效')
  return value
}

async function sourceFile(root, relative) {
  const target = path.join(root, relative)
  if (await realpath(target) !== target || !(await lstat(target)).isFile()) throw new Error('成果文件不存在或包含路径别名')
  return target
}

async function digestFile(filename) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(filename)) hash.update(chunk)
  return hash.digest('hex')
}

/** 复制产物内容以形成可恢复检查点；内容含本次凭据时拒绝写入。 */
export async function createCheckpoint({ stateDirectory, projectDirectory, artifactRefs, changedPaths, knownCredentials }) {
  if (!Array.isArray(artifactRefs) || artifactRefs.length === 0 || !Array.isArray(changedPaths) || !Array.isArray(knownCredentials)
      || knownCredentials.some(s => typeof s !== 'string' || !s)) throw new Error('缺少成果文件')
  const refs = [...new Set(artifactRefs.map(value => safePath(value, knownCredentials)))]
  const id = randomUUID()
  const directory = path.join(stateDirectory, 'checkpoints', id)
  await mkdir(directory, { recursive: true, mode: 0o700 })
  try {
    const files = []
    const directories = []
    const deletedPaths = []
    for (const changed of changedPaths) {
      const key = safePath(changed, knownCredentials)
      const target = path.join(projectDirectory, key)
      try { await lstat(target) } catch (error) {
        if (error.code !== 'ENOENT' || await realpath(path.dirname(target)) !== path.dirname(target)) throw error
        deletedPaths.push(key)
        continue
      }
      if (await realpath(target) !== target) throw new Error('修改位置包含路径别名')
      const info = await lstat(target)
      if (info.isDirectory()) directories.push(key)
      else if (info.isFile() && !refs.includes(key)) refs.push(key)
      else if (!info.isFile()) throw new Error('修改位置无效')
    }
    for (let i = 0; i < refs.length; i++) {
      const source = await sourceFile(projectDirectory, refs[i])
      const blob = `${i}.blob`
      const hash = createHash('sha256')
      const needles = knownCredentials.map(value => Buffer.from(value))
      const carrySize = Math.max(0, ...needles.map(value => value.length - 1))
      let carry = Buffer.alloc(0)
      const filter = new Transform({
        transform(chunk, _encoding, callback) {
          const contents = Buffer.concat([carry, chunk])
          if (needles.some(value => contents.includes(value))) return callback(new Error('成果包含执行凭据'))
          hash.update(chunk)
          carry = carrySize ? contents.subarray(Math.max(0, contents.length - carrySize)) : Buffer.alloc(0)
          callback(null, chunk)
        },
      })
      await pipeline(createReadStream(source), filter, createWriteStream(path.join(directory, blob), { flags: 'wx', mode: 0o600 }))
      const sha256 = hash.digest('hex')
      if (await digestFile(source) !== sha256) throw new Error('成果在生成检查点时发生变化')
      files.push({ path: refs[i], blob, sha256 })
    }
    const manifest = { id, files, directories, deletedPaths }
    await writeFile(path.join(directory, 'manifest.json'), JSON.stringify(manifest), { flag: 'wx', mode: 0o600 })
    return `linear-lite-snapshot:${id}`
  } catch (error) { await rm(directory, { recursive: true, force: true }); throw error }
}

/** 下游声明可用前核验快照与当前共享目录的文件内容。 */
export async function checkpointAvailable({ stateDirectory, projectDirectory, projectCheckpoint, artifactRefs }) {
  const match = typeof projectCheckpoint === 'string' && /^linear-lite-snapshot:([a-f0-9-]{36})$/.exec(projectCheckpoint)
  if (!match || !Array.isArray(artifactRefs) || artifactRefs.length === 0) return false
  try {
    const directory = path.join(stateDirectory, 'checkpoints', match[1])
    const manifest = JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'))
    if (manifest.id !== match[1] || !Array.isArray(manifest.files) || !Array.isArray(manifest.directories)
        || !Array.isArray(manifest.deletedPaths)) return false
    if (artifactRefs.some(ref => !manifest.files.some(file => file.path === safePath(ref, [])))) return false
    for (const entry of manifest.directories) {
      const target = path.join(projectDirectory, safePath(entry, []))
      if (await realpath(target) !== target || !(await lstat(target)).isDirectory()) return false
    }
    for (const entry of manifest.deletedPaths) {
      const target = path.join(projectDirectory, safePath(entry, []))
      if (await realpath(path.dirname(target)) !== path.dirname(target)) return false
      try { await lstat(target); return false } catch (error) { if (error.code !== 'ENOENT') return false }
    }
    for (const entry of manifest.files) {
      const key = safePath(entry.path, [])
      if (!/^[0-9]+\.blob$/.test(entry.blob) || !/^[a-f0-9]{64}$/.test(entry.sha256)) return false
      if (await digestFile(path.join(directory, entry.blob)) !== entry.sha256) return false
      if (await digestFile(await sourceFile(projectDirectory, key)) !== entry.sha256) return false
    }
    return true
  } catch { return false }
}
