import { performance } from 'node:perf_hooks'
import { HostError } from './device.mjs'

export function createCollaborationClient({ baseUrl, authToken, fetchImpl = fetch }) {
  const base = new URL(baseUrl)
  if (!['http:', 'https:'].includes(base.protocol) || base.origin !== baseUrl
      || typeof authToken !== 'string' || !authToken)
    throw new HostError(400, 'CONNECTION_INVALID', '协作服务连接参数无效')
  return {
    async call(route, payload, { signal, timeoutMs = 5000 } = {}) {
      if (typeof route !== 'string' || !/^\/?[a-z-]+\/[a-z-]+$/.test(route))
        throw new HostError(400, 'ROUTE_INVALID', '协作服务操作无效')
      const controller = new AbortController()
      const abort = () => controller.abort()
      if (signal?.aborted) controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      const timer = setTimeout(abort, timeoutMs)
      const requestStartedAt = performance.now()
      try {
        const response = await fetchImpl(`${base.origin}/api/collaboration/${route.replace(/^\//, '')}`, {
          method: 'POST', redirect: 'error', cache: 'no-store', signal: controller.signal,
          headers: { Authorization: `Bearer ${authToken}`, 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        })
        if (!response.ok) throw new HostError(response.status, 'SERVER_REJECTED', '协作服务拒绝请求')
        const serverTime = response.headers.get('X-Linear-Lite-Server-Time')
        absoluteTime(serverTime)
        const body = await response.json()
        if (!body || body.code !== 200 || !Object.hasOwn(body, 'data') || body.data === null)
          throw new HostError(502, 'RESPONSE_INVALID', '协作服务响应无效')
        return { result: body.data, serverTime, requestStartedAt }
      } catch (error) {
        if (error instanceof HostError) throw error
        throw new HostError(502, 'SERVICE_UNAVAILABLE', '协作服务连接失败')
      } finally {
        clearTimeout(timer); signal?.removeEventListener('abort', abort)
      }
    },
  }
}

function absoluteTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
      || !Number.isFinite(Date.parse(value)))
    throw new HostError(502, 'LEASE_CLOCK_INVALID', '协作服务时钟信息无效')
  return Date.parse(value)
}

/** 请求发起时刻起算，只扣减链路耗时；旧回执保留原到期时间。 */
export function leaseDeadline({ leaseUntil, serverTime, requestStartedAt, now = performance.now() }) {
  // Java Instant 可含纳秒；JS Date 截断到毫秒，扣除一毫秒精度上界。
  const deadline = requestStartedAt + absoluteTime(leaseUntil) - absoluteTime(serverTime) - 1
  if (!Number.isFinite(requestStartedAt) || !Number.isFinite(now) || now < requestStartedAt || deadline <= now)
    throw new HostError(409, 'LEASE_EXPIRED', '执行租约到期或响应失效')
  return deadline
}
