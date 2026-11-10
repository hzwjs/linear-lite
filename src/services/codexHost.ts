const CODEX_HOST_BASE_URL = 'http://127.0.0.1:9781'
const REQUEST_TIMEOUT_MS = 5000

export interface CodexHostIdentity {
  deviceId: string
  hostInstanceId: string
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
    const response = await fetch(`${CODEX_HOST_BASE_URL}${path}`, { ...init, signal: controller.signal })
    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new Error('本机宿主响应无效')
    }
    if (!response.ok) {
      if (typeof body === 'object' && body !== null && 'message' in body && typeof body.message === 'string') {
        throw new Error(body.message)
      }
      throw new Error('本机宿主请求失败')
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
