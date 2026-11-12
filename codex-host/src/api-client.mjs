import { performance } from 'node:perf_hooks'
import { HostError } from './device.mjs'
import { HOST_API_PREFIX, HOST_PROTOCOL_MISMATCH, hostProtocolMismatch } from './protocol.mjs'

const EXECUTION_ROUTES = new Set(['batches/pending', 'batches/context', 'batches/prepare-accept', 'batches/accept', 'batches/renew',
  'environment/observe', 'tasks/claim', 'attempts/renew', 'documents/list', 'documents/read',
  'attempts/adopt-context', 'attempts/dependencies/change', 'attempts/split', 'attempts/prerequisites/review',
  'attempts/checkpoint', 'attempts/complete'])
const RECOVERY_ROUTES = new Set(['read', 'request-stop', 'confirm-stop', 'recover-claim', 'finish', 'finish-accept'])

export function createCollaborationClient({ baseUrl, getAccessToken, fetchImpl = fetch }) {
  const base = new URL(baseUrl)
  if (base.protocol !== 'https:' || base.origin !== baseUrl || typeof getAccessToken !== 'function')
    throw new HostError(400, 'CONNECTION_INVALID', '协作服务连接参数无效')
  const usedCredentials = new Set()
  async function request(route, payload, { signal, timeoutMs = 5000 } = {}, recovery = false) {
      if (!(recovery ? RECOVERY_ROUTES : EXECUTION_ROUTES).has(route))
        throw new HostError(400, 'ROUTE_INVALID', '协作服务操作无效')
      const controller = new AbortController()
      const abort = () => controller.abort()
      if (signal?.aborted) controller.abort()
      signal?.addEventListener('abort', abort, { once: true })
      const timer = setTimeout(abort, timeoutMs)
      const requestStartedAt = performance.now()
      try {
        const headers = { 'Content-Type': 'application/json' }
        if (!recovery) {
          let token
          try { token = await getAccessToken() }
          catch (error) {
            if (error?.kind === 'AUTH_REQUIRED') throw new HostError(401, 'AUTH_REQUIRED', '宿主需要授权')
            throw error
          }
          if (typeof token !== 'string' || !token) throw new HostError(401, 'AUTH_REQUIRED', '宿主需要授权')
          usedCredentials.add(token)
          headers.Authorization = `Bearer ${token}`
        }
        const endpoint = `${base.origin}${HOST_API_PREFIX}/${recovery ? 'recovery/' : ''}${route}`
        const response = await fetchImpl(endpoint, {
          method: 'POST', redirect: 'error', cache: 'no-store', signal: controller.signal,
          headers,
          body: JSON.stringify(payload),
        })
        if (hostProtocolMismatch(response)) throw new HostError(426, 'HOST_PROTOCOL_MISMATCH', HOST_PROTOCOL_MISMATCH)
        if (!response.ok) throw new HostError(response.status, 'SERVER_REJECTED', '协作服务拒绝请求')
        const serverTime = response.headers.get('X-Linear-Lite-Server-Time')
        absoluteTime(serverTime)
        const body = await response.json()
        if (!body || body.code !== 200 || !Object.hasOwn(body, 'data') || body.data === null)
          throw new HostError(502, 'RESPONSE_INVALID', '协作服务响应无效')
        return { result: body.data, serverTime, requestStartedAt, responseReceivedAt: performance.now() }
      } catch (error) {
        if (error instanceof HostError) throw error
        throw new HostError(502, 'SERVICE_UNAVAILABLE', '协作服务连接失败')
      } finally {
        clearTimeout(timer); signal?.removeEventListener('abort', abort)
      }
  }
  return {
    call: (route, payload, options) => request(route, payload, options),
    // 原批次签名能力是唯一恢复身份；当前 OAuth 不进入该通道。
    recover: (route, payload, options) => request(route, payload, options, true),
    knownCredentials: () => [...usedCredentials],
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

/** 预算结束采用响应接收时刻上界；写入租约继续使用提前失权的下界。 */
export function budgetDeadline({ deadlineAt, serverTime, requestStartedAt, responseReceivedAt, now = performance.now() }) {
  const remaining = absoluteTime(deadlineAt) - absoluteTime(serverTime)
  if (!Number.isFinite(requestStartedAt) || !Number.isFinite(responseReceivedAt) || !Number.isFinite(now)
      || responseReceivedAt < requestStartedAt || now < responseReceivedAt)
    throw new HostError(502, 'LEASE_CLOCK_INVALID', '协作服务时钟信息无效')
  return responseReceivedAt + remaining + 1
}
