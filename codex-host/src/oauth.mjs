import { createHash, randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { hostname } from 'node:os'
import { HOST_API_PREFIX, hostProtocolMismatch } from './protocol.mjs'

const SCOPE = 'collaboration-host'
const LOGIN_TIMEOUT_MS = 5 * 60_000
const REQUEST_TIMEOUT_MS = 10_000
const HEARTBEAT_MS = 30_000
const REFRESH_MARGIN_MS = 60_000
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export function normalizeIssuer(value) {
  let url
  try { url = new URL(value) } catch { throw new Error('协作站点地址无效') }
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw new Error('协作站点须使用 HTTPS 地址')
  return url.origin
}

function key(deviceId) { return `hostoauth:${deviceId}` }

function validateRecord(record, issuer) {
  if (!record || Object.keys(record).sort().join(',') !== 'accessExpiresAt,accessToken,clientId,issuer,refreshToken'
      || record.issuer !== issuer || !UUID.test(record.clientId)
      || typeof record.accessToken !== 'string' || !record.accessToken || record.accessToken.length > 16384
      || typeof record.refreshToken !== 'string' || !record.refreshToken || record.refreshToken.length > 16384
      || /[\x00-\x20\x7f]/.test(record.accessToken) || /[\x00-\x20\x7f]/.test(record.refreshToken)
      || !Number.isSafeInteger(record.accessExpiresAt) || record.accessExpiresAt <= 0)
    throw new Error('宿主授权记录无效')
  return Object.freeze({ ...record })
}

async function readRecord(vault, deviceId, issuer) {
  try {
    const value = await vault.get(key(deviceId))
    return validateRecord(JSON.parse(Buffer.from(value, 'base64url').toString('utf8')), issuer)
  } catch { throw new OAuthRequestError('AUTH_REQUIRED') }
}

async function writeRecord(vault, deviceId, issuer, input) {
  const record = validateRecord(input, issuer)
  await vault.put(key(deviceId), Buffer.from(JSON.stringify(record), 'utf8').toString('base64url'))
}

/** URL 经 stdin 交给系统浏览器，不写入命令参数或日志。 */
export function openSystemBrowser(url) {
  return new Promise((resolve, reject) => {
    const child = spawn('/usr/bin/osascript', ['-'], { stdio: ['pipe', 'ignore', 'ignore'] })
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('无法打开浏览器')) }, REQUEST_TIMEOUT_MS)
    child.once('error', () => { clearTimeout(timer); reject(new Error('无法打开浏览器')) })
    child.once('close', code => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error('无法打开浏览器')) })
    child.stdin.on('error', () => {})
    child.stdin.end(`open location ${JSON.stringify(url)}\n`)
  })
}

class OAuthRequestError extends Error {
  constructor(kind) { super(kind); this.kind = kind }
}

async function requestJson(fetchImpl, url, options = {}, requireHostProtocol = false) {
  const controller = new AbortController()
  const parent = options.signal
  const abort = () => controller.abort()
  if (parent?.aborted) controller.abort()
  parent?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, REQUEST_TIMEOUT_MS)
  try {
    const response = await fetchImpl(url, { ...options, redirect: 'error', cache: 'no-store', signal: controller.signal })
    if (requireHostProtocol && hostProtocolMismatch(response)) throw new OAuthRequestError('UPGRADE_REQUIRED')
    if (!response.ok) throw new OAuthRequestError(response.status === 400 || response.status === 401 || response.status === 403 ? 'AUTH_REQUIRED' : 'OFFLINE')
    const text = await response.text()
    if (text.length > 65536) throw new OAuthRequestError('OFFLINE')
    try { return JSON.parse(text) } catch { throw new OAuthRequestError('OFFLINE') }
  } catch (error) {
    if (error instanceof OAuthRequestError) throw error
    throw new OAuthRequestError('OFFLINE')
  } finally {
    clearTimeout(timer)
    parent?.removeEventListener('abort', abort)
  }
}

function endpoint(value, issuer) {
  let url
  try { url = new URL(value) } catch { throw new Error('协作授权端点无效') }
  if (url.origin !== issuer || url.username || url.password || url.hash || url.search)
    throw new Error('协作授权端点无效')
  return url.href
}

async function discover(fetchImpl, issuer, signal) {
  const metadata = await requestJson(fetchImpl, `${issuer}/.well-known/oauth-authorization-server`, { signal })
  if (metadata.issuer !== issuer) throw new Error('协作授权服务不匹配')
  return {
    authorize: endpoint(metadata.authorization_endpoint, issuer),
    token: endpoint(metadata.token_endpoint, issuer),
    register: endpoint(metadata.registration_endpoint, issuer),
    revoke: endpoint(metadata.revocation_endpoint, issuer),
    resource: `${issuer}/api/collaboration-host`,
  }
}

function validateToken(token, now) {
  if (!token || typeof token.access_token !== 'string' || !token.access_token
      || typeof token.refresh_token !== 'string' || !token.refresh_token
      || typeof token.token_type !== 'string' || token.token_type.toLowerCase() !== 'bearer'
      || !Number.isSafeInteger(token.expires_in) || token.expires_in <= 0
      || typeof token.scope !== 'string' || token.scope.trim() !== SCOPE
      || !Number.isSafeInteger(now + token.expires_in * 1000))
    throw new Error('协作授权响应无效')
  return { access: token.access_token, refresh: token.refresh_token, accessExpiresAt: now + token.expires_in * 1000 }
}

async function tokenRequest(fetchImpl, tokenEndpoint, form, signal) {
  return requestJson(fetchImpl, tokenEndpoint, { method: 'POST', signal,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(form).toString() })
}

/** 本机回调接受一组匹配的 state 和 code；请求数据不进入日志。 */
export async function createOAuthCallback(state, signal, timeoutMs = LOGIN_TIMEOUT_MS) {
  let resolveCode, rejectCode, done = false
  const code = new Promise((resolve, reject) => { resolveCode = resolve; rejectCode = reject })
  code.catch(() => {})
  const server = createServer((request, response) => {
    const deny = () => { response.writeHead(400, { 'Cache-Control': 'no-store' }); response.end('授权请求无效') }
    const address = server.address()
    if (!address || typeof address === 'string' || request.method !== 'GET'
        || request.headers.host !== `127.0.0.1:${address.port}` || !request.url || request.url.length > 8192) return deny()
    let url
    try { url = new URL(request.url, `http://127.0.0.1:${address.port}`) } catch { return deny() }
    const states = url.searchParams.getAll('state'), codes = url.searchParams.getAll('code')
    if (url.origin !== `http://127.0.0.1:${address.port}` || url.pathname !== '/callback'
        || states.length !== 1 || states[0] !== state || done) return deny()
    if (url.searchParams.has('error')) {
      done = true; response.writeHead(400, { 'Cache-Control': 'no-store' }); response.end('授权未完成，可关闭窗口。')
      rejectCode(new Error('授权未完成')); return
    }
    if (codes.length !== 1 || !codes[0]) return deny()
    done = true
    response.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' })
    response.end('授权完成，可关闭窗口。')
    resolveCode(codes[0])
  })
  const timer = setTimeout(() => { if (!done) { done = true; rejectCode(new Error('授权等待超时')) } }, timeoutMs)
  timer.unref()
  const abort = () => { if (!done) { done = true; rejectCode(new Error('授权已中止')) } }
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  } catch (error) { clearTimeout(timer); signal?.removeEventListener('abort', abort); throw error }
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('本机授权回调启动失败')
  return { redirectUri: `http://127.0.0.1:${address.port}/callback`, code,
    close() { clearTimeout(timer); signal?.removeEventListener('abort', abort); server.close(); server.closeAllConnections() } }
}

export class HostOAuth {
  constructor({ issuer, identity, vault, fetchImpl = fetch, openBrowser = openSystemBrowser,
    deviceName = hostname(), clock = Date.now, heartbeatMs = HEARTBEAT_MS, callbackTimeoutMs = LOGIN_TIMEOUT_MS }) {
    this.issuer = normalizeIssuer(issuer)
    if (!identity || !UUID.test(identity.deviceId) || typeof identity.hostInstanceId !== 'string' || !identity.hostInstanceId
        || typeof deviceName !== 'string' || !deviceName.trim() || deviceName !== deviceName.trim()
        || deviceName.length > 128 || /[\x00-\x1f\x7f]/.test(deviceName)) throw new Error('宿主设备身份无效')
    this.identity = identity; this.deviceName = deviceName; this.vault = vault
    this.fetchImpl = fetchImpl; this.openBrowser = openBrowser; this.clock = clock
    this.heartbeatMs = heartbeatMs; this.callbackTimeoutMs = callbackTimeoutMs
    this.record = null; this.pending = null; this.refreshing = null; this.heartbeat = null; this.disconnecting = false; this.closed = false
    this.initialization = null
    this.signal = new AbortController()
    this.current = { connection: 'AUTH_REQUIRED', userId: null, grantId: null, deviceName: null, lastHeartbeatAt: null }
  }

  status() { return { deviceId: this.identity.deviceId, hostInstanceId: this.identity.hostInstanceId, ...this.current } }

  start() {
    if (!this.initialization) {
      this.current.connection = 'CONNECTING'
      this.initialization = this.initialize()
    }
    return this.initialization
  }

  async initialize() {
    try { this.record = await readRecord(this.vault, this.identity.deviceId, this.issuer) }
    catch { this.current.connection = 'AUTH_REQUIRED' }
    if (this.closed) return
    if (this.record) { this.current.connection = 'CONNECTING'; this.tick().catch(() => {}) }
    this.timer = setInterval(() => this.tick().catch(() => {}), this.heartbeatMs)
    this.timer.unref()
  }

  async connect() {
    await this.initialization
    if (this.closed) throw new Error('宿主服务未运行')
    if (this.disconnecting) throw new Error('宿主授权正在撤销')
    if (this.pending) return this.status()
    this.current.connection = 'CONNECTING'
    this.pending = (async () => {
      let connected = false
      try {
        await Promise.allSettled([this.heartbeat, this.refreshing].filter(Boolean))
        if (this.closed) return
        await this.login()
        connected = true
      } catch (error) {
        if (!this.closed) this.current.connection = error instanceof OAuthRequestError && error.kind === 'OFFLINE' ? 'OFFLINE' : 'AUTH_REQUIRED'
      } finally { this.pending = null }
      if ((connected || this.record) && !this.closed) {
        this.current.connection = 'CONNECTING'
        await this.tick()
      }
    })()
    return this.status()
  }

  async login() {
    const metadata = await discover(this.fetchImpl, this.issuer, this.signal.signal)
    const verifier = randomBytes(32).toString('base64url')
    const challenge = createHash('sha256').update(verifier).digest('base64url')
    const state = randomBytes(32).toString('base64url')
    const callback = await createOAuthCallback(state, this.signal.signal, this.callbackTimeoutMs)
    try {
      const registration = await requestJson(this.fetchImpl, metadata.register, { method: 'POST', signal: this.signal.signal,
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ client_name: 'Linear Lite 协作宿主',
          redirect_uris: [callback.redirectUri], grant_types: ['authorization_code', 'refresh_token'],
          response_types: ['code'], token_endpoint_auth_method: 'none', scope: SCOPE,
          device_id: this.identity.deviceId, device_name: this.deviceName }) })
      if (!registration || typeof registration.client_id !== 'string' || !registration.client_id || registration.client_id.length > 200)
        throw new Error('协作客户端注册失败')
      const authorize = new URL(metadata.authorize)
      for (const [key, value] of Object.entries({ response_type: 'code', client_id: registration.client_id,
        redirect_uri: callback.redirectUri, scope: SCOPE, state, code_challenge: challenge,
        code_challenge_method: 'S256', resource: metadata.resource })) authorize.searchParams.set(key, value)
      await this.openBrowser(authorize.href)
      const code = await callback.code
      const raw = await tokenRequest(this.fetchImpl, metadata.token, { grant_type: 'authorization_code', code,
        redirect_uri: callback.redirectUri, client_id: registration.client_id, code_verifier: verifier,
        resource: metadata.resource }, this.signal.signal)
      const token = validateToken(raw, this.clock())
      const record = { issuer: this.issuer, clientId: registration.client_id, accessToken: token.access,
        refreshToken: token.refresh, accessExpiresAt: token.accessExpiresAt }
      await writeRecord(this.vault, this.identity.deviceId, this.issuer, record)
      this.record = Object.freeze(record)
    } finally { callback.close() }
  }

  async accessToken(forceRefresh = false) {
    await this.initialization
    if (this.closed || !this.record || this.pending || this.disconnecting) throw new OAuthRequestError('AUTH_REQUIRED')
    if (this.refreshing) return this.refreshing
    if (!forceRefresh && this.record.accessExpiresAt - this.clock() > REFRESH_MARGIN_MS) {
      return this.record.accessToken
    }
    this.refreshing = this.refresh().finally(() => { this.refreshing = null })
    return this.refreshing
  }

  async refresh() {
    const record = await readRecord(this.vault, this.identity.deviceId, this.issuer)
    if (!this.record) throw new OAuthRequestError('AUTH_REQUIRED')
    const metadata = await discover(this.fetchImpl, this.issuer, this.signal.signal)
    const raw = await tokenRequest(this.fetchImpl, metadata.token, { grant_type: 'refresh_token',
      client_id: record.clientId, refresh_token: record.refreshToken, resource: metadata.resource }, this.signal.signal)
    const token = validateToken(raw, this.clock())
    const updated = { ...record, accessToken: token.access, refreshToken: token.refresh, accessExpiresAt: token.accessExpiresAt }
    await writeRecord(this.vault, this.identity.deviceId, this.issuer, updated)
    this.record = Object.freeze(updated)
    return token.access
  }

  async tick() {
    if (this.closed || !this.record || this.heartbeat || this.pending || this.disconnecting
        || this.current.connection === 'AUTH_REQUIRED') return
    this.heartbeat = this.sendHeartbeat().catch(error => {
      if (!this.closed && !this.pending) this.current.connection = error instanceof OAuthRequestError ? error.kind : 'OFFLINE'
    }).finally(() => { this.heartbeat = null })
    return this.heartbeat
  }

  async sendHeartbeat() {
    let token = await this.accessToken()
    let response
    const call = value => requestJson(this.fetchImpl, `${this.issuer}${HOST_API_PREFIX}/heartbeat`, {
      method: 'POST', signal: this.signal.signal, headers: { Authorization: `Bearer ${value}` } }, true)
    try { response = await call(token) }
    catch (error) {
      if (!(error instanceof OAuthRequestError) || error.kind !== 'AUTH_REQUIRED') throw error
      token = await this.accessToken(true)
      response = await call(token)
    }
    const body = response?.data
    if (response?.code !== 200 || !body || !Number.isSafeInteger(body.id) || !Number.isSafeInteger(body.userId)
        || body.deviceId !== this.identity.deviceId || body.authorized !== true || body.online !== true
        || typeof body.deviceName !== 'string' || typeof body.lastHeartbeatAt !== 'string') throw new Error('宿主心跳响应无效')
    if (this.closed || this.pending || this.disconnecting) return
    this.current = { connection: 'ONLINE', userId: body.userId, grantId: body.id,
      deviceName: body.deviceName, lastHeartbeatAt: body.lastHeartbeatAt }
  }

  async disconnect() {
    await this.initialization
    if (this.closed) throw new Error('宿主服务未运行')
    if (this.pending) throw new Error('授权正在进行')
    if (this.disconnecting) throw new Error('宿主授权正在撤销')
    if (!this.record) return this.status()
    this.disconnecting = true
    try {
      await Promise.allSettled([this.heartbeat, this.refreshing].filter(Boolean))
      const record = await readRecord(this.vault, this.identity.deviceId, this.issuer)
      const metadata = await discover(this.fetchImpl, this.issuer, this.signal.signal)
      const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
      try {
        const response = await this.fetchImpl(metadata.revoke, { method: 'POST', redirect: 'error', cache: 'no-store',
          signal: controller.signal, headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ client_id: record.clientId, token: record.refreshToken }).toString() })
        if (!response.ok) throw new Error('撤销宿主授权失败')
      } catch { throw new Error('撤销宿主授权失败') } finally { clearTimeout(timeout) }
      await this.vault.remove(key(this.identity.deviceId))
      this.record = null
      this.current = { connection: 'AUTH_REQUIRED', userId: null, grantId: null, deviceName: null, lastHeartbeatAt: null }
      return this.status()
    } finally { this.disconnecting = false }
  }

  async close() {
    this.closed = true
    clearInterval(this.timer)
    this.signal.abort()
    await this.initialization
    await Promise.allSettled([this.pending, this.refreshing, this.heartbeat].filter(Boolean))
  }
}
