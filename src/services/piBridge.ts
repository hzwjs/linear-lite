const PI_BRIDGE_BASE_URL = 'http://127.0.0.1:9780'
const PI_BRIDGE_HEALTH_TIMEOUT_MS = 2000
const PI_BRIDGE_REQUEST_TIMEOUT_MS = 5000

export type PiBridgePlatform = 'darwin' | 'win32' | 'linux'

export type PiBridgeHealth = {
  status?: 'idle' | 'connecting' | 'online' | 'degraded' | 'stopping'
  configured?: boolean
  backendReachable?: boolean
  version?: string
  platform?: PiBridgePlatform
}

export type PiBridgeProject = {
  projectId: number
  projectName: string
  directoryPath: string
  valid: boolean
  error?: string
}

function apiBaseUrl(
  configuredApiBaseUrl: string | undefined = import.meta.env.VITE_API_BASE_URL,
  pageUrl: string = window.location.href,
): string {
  const configuredUrl = new URL(configuredApiBaseUrl ?? '/api', pageUrl)
  const path = configuredUrl.pathname.replace(/\/api\/?$/, '')
  return `${configuredUrl.origin}${path}`.replace(/\/$/, '')
}

async function localBridgeRequest<T>(path: string, options: RequestInit = {}, timeoutMs = PI_BRIDGE_REQUEST_TIMEOUT_MS): Promise<T> {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${PI_BRIDGE_BASE_URL}${path}`, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
      signal: controller.signal,
    })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.message ?? '本机 Bridge 请求失败')
    return body as T
  } catch (reason) {
    if (reason instanceof Error && !['TypeError', 'AbortError'].includes(reason.name)) throw reason
    throw new Error('未检测到本机 Bridge，请先在项目设置的“本地执行”中安装并配置')
  } finally {
    window.clearTimeout(timeoutId)
  }
}

export async function getPiBridgeHealth(): Promise<PiBridgeHealth | null> {
  try {
    return await localBridgeRequest<PiBridgeHealth>('/healthz', { method: 'GET', cache: 'no-store' }, PI_BRIDGE_HEALTH_TIMEOUT_MS)
  } catch {
    return null
  }
}

export async function isPiBridgeAvailable(): Promise<boolean> {
  const body = await getPiBridgeHealth()
  return body?.configured === true && ['idle', 'connecting', 'online', 'degraded'].includes(body.status ?? '')
}

export async function configurePiBridgeInstance(): Promise<void> {
  await localBridgeRequest('/api/settings', {
    method: 'PUT',
    body: JSON.stringify({ apiBaseUrl: apiBaseUrl() }),
  })
}

export async function listPiBridgeProjects(): Promise<PiBridgeProject[]> {
  const body = await localBridgeRequest<{ projects: PiBridgeProject[] }>('/api/projects', { method: 'GET' })
  return body.projects
}

export async function savePiBridgeProject(projectId: number, projectName: string, directoryPath: string): Promise<PiBridgeProject> {
  return localBridgeRequest<PiBridgeProject>(`/api/projects/${encodeURIComponent(projectId)}`, {
    method: 'PUT',
    body: JSON.stringify({ projectName, directoryPath }),
  })
}

export async function connectPiExecution(executionId: string, executionCredential: string): Promise<void> {
  await localBridgeRequest('/api/executions', {
    method: 'POST',
    body: JSON.stringify({ apiBaseUrl: apiBaseUrl(), executionId, executionCredential }),
  })
}

export function detectPiBridgePlatform(userAgent: string = navigator.userAgent): PiBridgePlatform | null {
  if (/Macintosh|Mac OS X/i.test(userAgent)) return 'darwin'
  if (/Windows/i.test(userAgent)) return 'win32'
  if (/Linux/i.test(userAgent)) return 'linux'
  return null
}

export function piBridgeInstallerUrl(platform: PiBridgePlatform): string {
  return `${apiBaseUrl()}/api/bridge/installers/${platform}`
}

/** 在用户点击 Pi 入口时下载当前系统的 Bridge 安装包。 */
export function downloadPiBridgeInstaller(platform: PiBridgePlatform | null = detectPiBridgePlatform()): boolean {
  if (platform !== 'darwin' && platform !== 'win32') return false

  const installer = document.createElement('a')
  installer.href = piBridgeInstallerUrl(platform)
  installer.download = ''
  installer.hidden = true
  document.body.appendChild(installer)
  installer.click()
  installer.remove()
  return true
}
