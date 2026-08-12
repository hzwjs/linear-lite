const PI_BRIDGE_HEALTH_URL = 'http://127.0.0.1:9780/healthz'
const PI_BRIDGE_HEALTH_TIMEOUT_MS = 800

/**
 * Pi 入口只依赖 Bridge 的健康检查结果；检查失败即视为本地 Bridge 不可用。
 */
export async function isPiBridgeAvailable(): Promise<boolean> {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), PI_BRIDGE_HEALTH_TIMEOUT_MS)

  try {
    const response = await fetch(PI_BRIDGE_HEALTH_URL, {
      method: 'GET',
      cache: 'no-store',
      signal: controller.signal,
    })
    if (!response.ok) return false
    const body = await response.json() as { status?: string }
    return body.status === 'ok'
  } catch {
    return false
  } finally {
    window.clearTimeout(timeoutId)
  }
}
