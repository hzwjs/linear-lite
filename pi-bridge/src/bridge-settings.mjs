import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { dirname, resolve } from 'node:path'

const DEFAULT_API_BASE_URL = 'http://127.0.0.1:9080'

export function validateApiBaseUrl(value) {
  if (typeof value !== 'string' || !value.trim()) throw new Error('Linear Lite 地址不能为空')
  let url
  try { url = new URL(value.trim()) } catch { throw new Error('Linear Lite 地址不是有效 URL') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Linear Lite 地址必须使用 http 或 https，且不能包含账号密码')
  }
  return url.toString().replace(/\/$/, '')
}

function parseSettings(text, filePath) {
  let parsed
  try { parsed = JSON.parse(text) } catch { throw new Error(`Bridge 连接配置不是有效 JSON：${filePath}`) }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`Bridge 连接配置格式无效：${filePath}`)
  }
  return {
    apiBaseUrl: validateApiBaseUrl(parsed.apiBaseUrl),
  }
}

export class BridgeSettingsStore {
  constructor(filePath) {
    this.filePath = resolve(filePath)
    this.writeQueue = Promise.resolve()
  }

  async read() {
    try {
      return parseSettings(await readFile(this.filePath, 'utf8'), this.filePath)
    } catch (error) {
      if (error?.code === 'ENOENT') return { apiBaseUrl: DEFAULT_API_BASE_URL }
      throw error
    }
  }

  async publicSettings() {
    const settings = await this.read()
    return { apiBaseUrl: settings.apiBaseUrl, configured: true }
  }

  async save(apiBaseUrl) {
    const settings = {
      apiBaseUrl: validateApiBaseUrl(apiBaseUrl),
    }
    return this.enqueueWrite(async () => {
      await mkdir(dirname(this.filePath), { recursive: true })
      const tempPath = `${this.filePath}.${randomUUID()}.tmp`
      await writeFile(tempPath, `${JSON.stringify(settings, null, 2)}\n`, { mode: 0o600 })
      await rename(tempPath, this.filePath)
      return settings
    })
  }

  enqueueWrite(operation) {
    const result = this.writeQueue.then(operation)
    this.writeQueue = result.catch(() => {})
    return result
  }
}
