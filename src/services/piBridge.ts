const PI_BRIDGE_HEALTH_URL = 'http://127.0.0.1:9780/healthz'
const PI_BRIDGE_ATTACH_URL = 'http://127.0.0.1:9780/api/attach'
const PI_BRIDGE_HEALTH_TIMEOUT_MS = 2000

type PiBridgeHealth = {
  status?: 'config_required' | 'connecting' | 'online' | 'degraded' | 'waiting_for_browser' | 'stopping'
  configured?: boolean
}

export async function getPiBridgeHealth(): Promise<PiBridgeHealth | null> {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), PI_BRIDGE_HEALTH_TIMEOUT_MS)

  try {
    const response = await fetch(PI_BRIDGE_HEALTH_URL, {
      method: 'GET',
      cache: 'no-store',
      signal: controller.signal,
    })
    if (!response.ok) return null
    return await response.json() as PiBridgeHealth
  } catch {
    return null
  } finally {
    window.clearTimeout(timeoutId)
  }
}

/**
 * Bridge 进程存活但后端重连时仍保留 Pi 入口，避免短暂网络波动被误判为本地服务消失。
 */
export async function isPiBridgeAvailable(): Promise<boolean> {
  const body = await getPiBridgeHealth()
  return body?.configured === true && ['connecting', 'online', 'degraded', 'waiting_for_browser'].includes(body.status ?? '')
}

export async function attachPiBridge(attachmentCode: string): Promise<void> {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), PI_BRIDGE_HEALTH_TIMEOUT_MS)
  try {
    const response = await fetch(PI_BRIDGE_ATTACH_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ attachmentCode }),
      signal: controller.signal,
    })
    const body = await response.json().catch(() => null)
    if (!response.ok || body?.code >= 400) throw new Error(body?.message ?? '本地 Bridge 连接失败')
  } finally {
    window.clearTimeout(timeoutId)
  }
}
