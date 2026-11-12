const CODEX_HOST_BASE_URL = 'http://127.0.0.1:9781'
const REQUEST_TIMEOUT_MS = 5000
const HOST_PROTOCOL_VERSION = 3
const HOST_UPGRADE_REQUIRED = '本机协作宿主协议需要升级，请下载新版宿主'

export interface CodexHostIdentity {
  deviceId: string
  hostInstanceId: string
}

export interface CodexHostStatus extends CodexHostIdentity {
  protocolVersion: typeof HOST_PROTOCOL_VERSION
  processId: number
  connection: 'AUTH_REQUIRED' | 'CONNECTING' | 'ONLINE' | 'OFFLINE' | 'UPGRADE_REQUIRED'
  userId: number | null
  grantId: number | null
  deviceName: string | null
  lastHeartbeatAt: string | null
}

export interface ValidatedProjectDirectory {
  deviceId: string
  projectDirectory: string
  docsDirectory: string
}

async function hostRequest(path: string, init: RequestInit): Promise<unknown> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(`${CODEX_HOST_BASE_URL}${path}`, { ...init, credentials: 'omit', signal: controller.signal })
    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new Error('本机宿主响应无效')
    }
    if (!response.ok) {
      if (response.status === 404 && typeof body === 'object' && body !== null && 'code' in body && body.code === 'ROUTE_NOT_FOUND') {
        throw new Error(HOST_UPGRADE_REQUIRED)
      }
      if (typeof body === 'object' && body !== null && 'message' in body && typeof body.message === 'string') {
        throw new Error(body.message)
      }
      throw new Error('本机宿主请求失败')
    }
    if (typeof body === 'object' && body !== null && 'deviceId' in body && validText(body.deviceId)
        && (!('protocolVersion' in body) || body.protocolVersion !== HOST_PROTOCOL_VERSION)) {
      throw new Error(HOST_UPGRADE_REQUIRED)
    }
    return body
  } catch (error) {
    if (error instanceof TypeError || (error instanceof Error && error.name === 'AbortError')) {
      throw new Error('无法连接本机协作宿主')
    }
    throw error
  } finally {
    window.clearTimeout(timeout)
  }
}

function hostStatus(value: unknown): CodexHostStatus {
  if (typeof value !== 'object' || value === null) throw new Error('本机宿主状态无效')
  const data = value as Record<string, unknown>
  const nullableId = (id: unknown) => id === null || (typeof id === 'number' && Number.isSafeInteger(id) && id > 0)
  const nullableText = (text: unknown) => text === null || typeof text === 'string'
  if (!validText(data.deviceId) || !validText(data.hostInstanceId) || typeof data.processId !== 'number'
      || !Number.isSafeInteger(data.processId) || data.processId <= 0
      || !['AUTH_REQUIRED', 'CONNECTING', 'ONLINE', 'OFFLINE', 'UPGRADE_REQUIRED'].includes(data.connection as string)
      || !nullableId(data.userId) || !nullableId(data.grantId)
      || !nullableText(data.deviceName) || !nullableText(data.lastHeartbeatAt)) throw new Error('本机宿主状态无效')
  return data as unknown as CodexHostStatus
}

export async function getCodexHostStatus(): Promise<CodexHostStatus> {
  return hostStatus(await hostRequest('/status', { method: 'GET', cache: 'no-store' }))
}

export async function connectCodexHost(): Promise<CodexHostStatus> {
  return hostStatus(await hostRequest('/connect', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }))
}

function validText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

export async function getCodexHostIdentity(): Promise<CodexHostIdentity> {
  const value = await hostRequest('/identity', { method: 'GET', cache: 'no-store' })
  if (typeof value !== 'object' || value === null || !('deviceId' in value) || !validText(value.deviceId)
      || !('hostInstanceId' in value) || !validText(value.hostInstanceId)) {
    throw new Error('本机宿主设备信息无效')
  }
  return { deviceId: value.deviceId, hostInstanceId: value.hostInstanceId }
}

export async function validateCodexProjectDirectory(deviceId: string, projectDirectory: string): Promise<ValidatedProjectDirectory> {
  const value = await hostRequest('/validate-directory', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, projectDirectory }),
  })
  if (typeof value !== 'object' || value === null || !('deviceId' in value) || !validText(value.deviceId)
      || !('projectDirectory' in value) || !validText(value.projectDirectory)
      || !('docsDirectory' in value) || !validText(value.docsDirectory)) {
    throw new Error('本机宿主目录信息无效')
  }
  return { deviceId: value.deviceId, projectDirectory: value.projectDirectory, docsDirectory: value.docsDirectory }
}
