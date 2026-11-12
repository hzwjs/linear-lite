import { randomUUID } from 'node:crypto'
import { createReadStream, createWriteStream } from 'node:fs'
import { mkdir, rm, unlink, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { pipeline } from 'node:stream/promises'
import { createArtifactObserver } from './artifact-observation.mjs'

/** 历史快照只记录内容，不回写项目目录。 */
export async function createCheckpoint({ stateDirectory, projectDirectory, artifactRefs, changedPaths, knownCredentials, projectRootIdentity }) {
  if (!Array.isArray(artifactRefs) || artifactRefs.length === 0 || !Array.isArray(changedPaths))
    throw new Error('缺少成果文件')
  const id = randomUUID()
  const directory = path.join(stateDirectory, 'checkpoints', id)
  await mkdir(directory, { recursive: true, mode: 0o700 })
  try {
    const observer = await createArtifactObserver({ stateDirectory, projectDirectory, knownCredentials, projectRootIdentity })
    const spool = path.join(directory, 'capture.tmp')
    const capture = await observer.captureArtifacts({ paths: [...new Set(artifactRefs)], changedPaths },
      createWriteStream(spool, { flags: 'wx', mode: 0o600 }))
    if (capture.observations.some(fact => fact.state !== 'PRESENT')) throw new Error('成果不存在')
    const files = []
    for (const [index, entry] of capture.files.entries()) {
      const blob = `${index}.blob`
      const target = path.join(directory, blob)
      if (entry.size === 0) await writeFile(target, Buffer.alloc(0), { flag: 'wx', mode: 0o600 })
      else await pipeline(createReadStream(spool, { start: entry.offset, end: entry.offset + entry.size - 1 }),
        createWriteStream(target, { flags: 'wx', mode: 0o600 }))
      files.push({ path: entry.path, blob, sha256: entry.sha256 })
    }
    await unlink(spool)
    const manifest = { id, files, directories: capture.directories, links: capture.links, deletedPaths: capture.deletedPaths }
    await writeFile(path.join(directory, 'manifest.json'), JSON.stringify(manifest), { flag: 'wx', mode: 0o600 })
    return `linear-lite-snapshot:${id}`
  } catch (error) { await rm(directory, { recursive: true, force: true }); throw error }
}
