import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { normalizeIssuer } from './oauth.mjs'

export async function readHostConfig(stateDirectory) {
  let config
  try { config = JSON.parse(await readFile(path.join(stateDirectory, 'host-config.json'), 'utf8')) }
  catch { throw new Error('宿主配置无法读取') }
  if (!config || Object.keys(config).join(',') !== 'origin' || normalizeIssuer(config.origin) !== config.origin)
    throw new Error('宿主配置无效')
  return Object.freeze({ origin: config.origin })
}

/** 站点来源是宿主的唯一配置，不保存 OAuth 凭据。 */
export async function writeHostConfig(stateDirectory, origin) {
  const config = { origin: normalizeIssuer(origin) }
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 })
  const temporary = path.join(stateDirectory, `.host-config-${randomUUID()}.tmp`)
  try {
    await writeFile(temporary, JSON.stringify(config), { flag: 'wx', mode: 0o600 })
    await rename(temporary, path.join(stateDirectory, 'host-config.json'))
  } finally { await unlink(temporary).catch(() => {}) }
  return Object.freeze(config)
}
