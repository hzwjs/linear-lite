import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { createHash } from 'node:crypto'
import { HostOAuth, createOAuthCallback, normalizeIssuer } from '../src/oauth.mjs'

const issuer = 'https://linear.example'
const identity = { deviceId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', hostInstanceId: 'host-1' }
const clientId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const key = `hostoauth:${identity.deviceId}`

class FakeVault {
  values = new Map()
  writes = 0
  async get(account) { if (!this.values.has(account)) throw new Error('missing'); return this.values.get(account) }
  async put(account, value) { this.writes++; this.values.set(account, value) }
  async remove(account) { this.values.delete(account) }
}

function json(value, status = 200, headers = {}) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

function fixture() {
  const calls = []
  let challenge, expectedRefresh = 'fixture-refresh-1', refreshCount = 0, revoked = false, network = true, protocolReady = true
  const fetchImpl = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    if (!network) throw new Error('fixture network failure')
    if (url === `${issuer}/.well-known/oauth-authorization-server`) return json({ issuer,
      authorization_endpoint: `${issuer}/oauth2/authorize`, token_endpoint: `${issuer}/oauth2/token`,
      registration_endpoint: `${issuer}/oauth2/register`, revocation_endpoint: `${issuer}/oauth2/revoke` })
    if (url === `${issuer}/oauth2/register`) {
      const registration = JSON.parse(options.body)
      assert.equal(registration.device_id, identity.deviceId)
      assert.equal(registration.device_name, '测试 Mac')
      assert.equal(registration.scope, 'collaboration-host')
      assert.deepEqual(registration.grant_types, ['authorization_code', 'refresh_token'])
      return json({ client_id: clientId })
    }
    if (url === `${issuer}/oauth2/token`) {
      const form = new URLSearchParams(options.body)
      assert.equal(form.get('client_id'), clientId)
      assert.equal(form.get('resource'), `${issuer}/api/collaboration-host`)
      if (form.get('grant_type') === 'authorization_code') {
        assert.equal(form.get('code'), 'fixture-code')
        assert.equal(createHash('sha256').update(form.get('code_verifier')).digest('base64url'), challenge)
        return json({ access_token: 'fixture.access.1', refresh_token: expectedRefresh,
          token_type: 'Bearer', expires_in: 3600, scope: 'collaboration-host' })
      }
      assert.equal(form.get('grant_type'), 'refresh_token')
      if (revoked || form.get('refresh_token') !== expectedRefresh) return json({ error: 'invalid_grant' }, 400)
      refreshCount++
      expectedRefresh = `fixture-refresh-${refreshCount + 1}`
      return json({ access_token: `fixture.access.${refreshCount + 1}`, refresh_token: expectedRefresh,
        token_type: 'Bearer', expires_in: 3600, scope: 'collaboration-host' })
    }
    if (url === `${issuer}/api/collaboration-host/v3/heartbeat`) {
      if (!protocolReady) return json({ code: 404 }, 404)
      if (revoked) return json({ code: 401 }, 401)
      assert.match(options.headers.Authorization, /^Bearer fixture\.access\./)
      return json({ code: 200, data: { id: 44, userId: 9, deviceId: identity.deviceId,
        deviceName: '测试 Mac', authorized: true, online: true, lastHeartbeatAt: '2026-11-10T00:00:00Z' } }, 200, { 'X-Linear-Lite-Host-Protocol': '3' })
    }
    if (url === `${issuer}/oauth2/revoke`) {
      const form = new URLSearchParams(options.body)
      assert.equal(form.get('client_id'), clientId)
      assert.equal(form.get('token'), expectedRefresh)
      revoked = true
      return new Response(null, { status: 200 })
    }
    throw new Error('unexpected fixture endpoint')
  }
  async function openBrowser(raw) {
    const url = new URL(raw)
    assert.equal(url.origin, issuer)
    assert.equal(url.searchParams.get('code_challenge_method'), 'S256')
    assert.equal(url.searchParams.get('scope'), 'collaboration-host')
    challenge = url.searchParams.get('code_challenge')
    const callback = new URL(url.searchParams.get('redirect_uri'))
    callback.searchParams.set('state', 'wrong-state'); callback.searchParams.set('code', 'fixture-code')
    assert.equal((await fetch(callback)).status, 400)
    callback.searchParams.set('state', url.searchParams.get('state'))
    assert.equal((await fetch(callback)).status, 200)
  }
  return { calls, fetchImpl, openBrowser, get refreshCount() { return refreshCount },
    revoke() { revoked = true }, setNetwork(value) { network = value }, setProtocol(value) { protocolReady = value } }
}

async function loggedIn() {
  const server = fixture(), vault = new FakeVault()
  const auth = new HostOAuth({ issuer, identity, vault, fetchImpl: server.fetchImpl, openBrowser: server.openBrowser,
    deviceName: '测试 Mac', heartbeatMs: 3600000 })
  await auth.start()
  assert.equal(auth.status().connection, 'AUTH_REQUIRED')
  await auth.connect()
  if (auth.pending) await auth.pending
  assert.equal(auth.status().connection, 'ONLINE')
  return { auth, server, vault }
}

test('OAuth login validates state and S256 and stores one Keychain bundle', async () => {
  const { auth, server, vault } = await loggedIn()
  try {
    assert.equal(vault.values.size, 1); assert.equal(vault.writes, 1)
    const bundle = JSON.parse(Buffer.from(await vault.get(key), 'base64url').toString('utf8'))
    assert.equal(bundle.issuer, issuer); assert.equal(bundle.clientId, clientId)
    assert.equal(bundle.accessToken, 'fixture.access.1'); assert.equal(bundle.refreshToken, 'fixture-refresh-1')
    assert(!JSON.stringify(auth.status()).includes('fixture.access'))
    assert(!JSON.stringify(auth.status()).includes('fixture-refresh'))
    const login = server.calls.filter(call => call.url.endsWith('/oauth2/token'))
    assert.equal(login.length, 1)
  } finally { await auth.close() }
})

test('heartbeat exposes a protocol mismatch without dropping the OAuth record', async () => {
  const { auth, server, vault } = await loggedIn()
  try {
    const before = await vault.get(key)
    server.setProtocol(false)
    await auth.tick()
    assert.equal(auth.status().connection, 'UPGRADE_REQUIRED')
    assert.equal(await vault.get(key), before)
    assert.equal(server.refreshCount, 0)
    server.setProtocol(true)
    await auth.tick()
    assert.equal(auth.status().connection, 'ONLINE')
  } finally { await auth.close() }
})

test('concurrent refresh rotates one bundle and disconnect revokes it', async () => {
  const { auth, server, vault } = await loggedIn()
  try {
    const tokens = await Promise.all([auth.accessToken(true), auth.accessToken(), auth.accessToken(true)])
    assert.deepEqual(tokens, Array(3).fill('fixture.access.2'))
    assert.equal(server.refreshCount, 1); assert.equal(vault.writes, 2)
    const bundle = JSON.parse(Buffer.from(await vault.get(key), 'base64url').toString('utf8'))
    assert.equal(bundle.accessToken, 'fixture.access.2'); assert.equal(bundle.refreshToken, 'fixture-refresh-2')
    await auth.disconnect()
    assert.equal(vault.values.size, 0); assert.equal(auth.status().connection, 'AUTH_REQUIRED')
  } finally { await auth.close() }
})

test('reauthorization waits for an in-flight refresh to persist its rotated token', async () => {
  const server = fixture(), vault = new FakeVault()
  let releaseRefresh, markRefresh, gateEnabled = false
  const blocked = new Promise(resolve => { markRefresh = resolve })
  const gate = new Promise(resolve => { releaseRefresh = resolve })
  const fetchImpl = async (url, options) => {
    if (gateEnabled && url.endsWith('/oauth2/token') && new URLSearchParams(options.body).get('grant_type') === 'refresh_token') {
      markRefresh(); await gate
    }
    return server.fetchImpl(url, options)
  }
  const auth = new HostOAuth({ issuer, identity, vault, fetchImpl, openBrowser: server.openBrowser,
    deviceName: '测试 Mac', heartbeatMs: 3600000 })
  try {
    await auth.start(); await auth.connect(); if (auth.pending) await auth.pending
    gateEnabled = true
    const refresh = auth.accessToken(true); await blocked
    await auth.connect(); const pending = auth.pending
    releaseRefresh()
    assert.equal(await refresh, 'fixture.access.2')
    await pending
    assert.equal(auth.status().connection, 'ONLINE')
    assert.equal(server.refreshCount, 1)
    assert.equal(vault.writes, 3)
  } finally { releaseRefresh(); await auth.close() }
})

test('disconnect waits for an in-flight refresh and revokes the rotated token', async () => {
  const server = fixture(), vault = new FakeVault()
  let releaseRefresh, markRefresh, gateEnabled = false
  const blocked = new Promise(resolve => { markRefresh = resolve })
  const gate = new Promise(resolve => { releaseRefresh = resolve })
  const fetchImpl = async (url, options) => {
    if (gateEnabled && url.endsWith('/oauth2/token') && new URLSearchParams(options.body).get('grant_type') === 'refresh_token') {
      markRefresh(); await gate
    }
    return server.fetchImpl(url, options)
  }
  const auth = new HostOAuth({ issuer, identity, vault, fetchImpl, openBrowser: server.openBrowser,
    deviceName: '测试 Mac', heartbeatMs: 3600000 })
  try {
    await auth.start(); await auth.connect(); if (auth.pending) await auth.pending
    gateEnabled = true
    const refresh = auth.accessToken(true); await blocked
    const disconnected = auth.disconnect()
    releaseRefresh()
    assert.equal(await refresh, 'fixture.access.2')
    await disconnected
    assert.equal(auth.status().connection, 'AUTH_REQUIRED')
    assert.equal(vault.values.size, 0)
  } finally { releaseRefresh(); await auth.close() }
})

test('cancelled reauthorization checks the existing grant without opening another browser', async () => {
  const server = fixture(), vault = new FakeVault()
  let first = true, opens = 0
  const openBrowser = async raw => {
    opens++
    if (first) { first = false; await server.openBrowser(raw); return }
    const authorize = new URL(raw), callback = new URL(authorize.searchParams.get('redirect_uri'))
    callback.searchParams.set('state', authorize.searchParams.get('state'))
    callback.searchParams.set('error', 'access_denied')
    assert.equal((await fetch(callback)).status, 400)
  }
  const auth = new HostOAuth({ issuer, identity, vault, fetchImpl: server.fetchImpl, openBrowser,
    deviceName: '测试 Mac', heartbeatMs: 3600000 })
  try {
    await auth.start(); await auth.connect(); if (auth.pending) await auth.pending
    assert.equal(auth.status().connection, 'ONLINE')
    await auth.connect(); if (auth.pending) await auth.pending
    assert.equal(auth.status().connection, 'ONLINE')
    assert.equal(vault.writes, 1); assert.equal(opens, 2)
  } finally { await auth.close() }
})

test('offline heartbeat recovers and revoked authorization waits for user action', async () => {
  const { auth, server } = await loggedIn()
  try {
    server.setNetwork(false); await auth.tick(); assert.equal(auth.status().connection, 'OFFLINE')
    server.setNetwork(true); await auth.tick(); assert.equal(auth.status().connection, 'ONLINE')
    server.revoke(); await auth.tick(); assert.equal(auth.status().connection, 'AUTH_REQUIRED')
    const count = server.calls.length
    await auth.tick(); assert.equal(server.calls.length, count)
  } finally { await auth.close() }
})

test('restart reads the Keychain bundle and refuses a different issuer', async () => {
  const { auth, server, vault } = await loggedIn()
  await auth.close()
  const restarted = new HostOAuth({ issuer, identity, vault, fetchImpl: server.fetchImpl, openBrowser: server.openBrowser,
    deviceName: '测试 Mac', heartbeatMs: 3600000 })
  try {
    await restarted.start(); if (restarted.heartbeat) await restarted.heartbeat
    assert.equal(restarted.status().connection, 'ONLINE')
  } finally { await restarted.close() }
  const other = new HostOAuth({ issuer: 'https://other.example', identity, vault, fetchImpl: server.fetchImpl,
    openBrowser: server.openBrowser, deviceName: '测试 Mac', heartbeatMs: 3600000 })
  try { await other.start(); assert.equal(other.status().connection, 'AUTH_REQUIRED') }
  finally { await other.close() }
})

async function delayedRestart() {
  const { auth: original, server, vault } = await loggedIn()
  await original.close()
  const originalGet = vault.get.bind(vault)
  let release, entered
  const gate = new Promise(resolve => { release = resolve })
  const reading = new Promise(resolve => { entered = resolve })
  let first = true
  vault.get = async account => {
    const value = await originalGet(account)
    if (first) { first = false; entered(); await gate }
    return value
  }
  let browserOpens = 0
  const auth = new HostOAuth({ issuer, identity, vault, fetchImpl: server.fetchImpl,
    openBrowser: async raw => { browserOpens++; await server.openBrowser(raw) }, deviceName: '测试 Mac', heartbeatMs: 3600000 })
  const started = auth.start()
  await reading
  return { auth, server, vault, started, release, get browserOpens() { return browserOpens } }
}

test('disconnect during startup waits for the stored grant and revokes it', async () => {
  const restart = await delayedRestart()
  try {
    let settled = false
    const stopped = restart.auth.disconnect().then(result => { settled = true; return result })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(settled, false)
    restart.release()
    await restart.started; await stopped
    assert.equal(restart.vault.values.size, 0)
    assert.equal(restart.auth.status().connection, 'AUTH_REQUIRED')
    await restart.auth.tick()
    assert.equal(restart.auth.status().connection, 'AUTH_REQUIRED')
  } finally { restart.release(); await restart.auth.close() }
})

test('connect and token access wait for the startup Keychain read', async () => {
  const restart = await delayedRestart()
  try {
    const token = restart.auth.accessToken()
    const connected = restart.auth.connect()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(restart.browserOpens, 0)
    restart.release()
    await restart.started
    assert.equal(await token, 'fixture.access.1')
    await connected
    if (restart.auth.pending) await restart.auth.pending
    assert.equal(restart.browserOpens, 1)
    assert.equal(restart.auth.status().connection, 'ONLINE')
    assert.equal(restart.vault.writes, 2)
  } finally { restart.release(); await restart.auth.close() }
})

test('closing during startup prevents waiting controls from running', async () => {
  const restart = await delayedRestart()
  try {
    const connected = restart.auth.connect()
    const closed = restart.auth.close()
    restart.release()
    await restart.started; await closed
    await assert.rejects(connected, /宿主服务未运行/)
    assert.equal(restart.browserOpens, 0)
  } finally { restart.release(); await restart.auth.close() }
})

test('callback rejects wrong state, duplicate parameters and Host alias', async () => {
  const callback = await createOAuthCallback('expected-state', undefined, 5000)
  try {
    const url = new URL(callback.redirectUri)
    async function get(query, host) {
      return new Promise((resolve, reject) => {
        const request = http.request({ host: '127.0.0.1', port: Number(url.port), path: `/callback?${query}`,
          headers: { Host: host } }, response => { response.resume(); response.on('end', () => resolve(response.statusCode)) })
        request.on('error', reject); request.end()
      })
    }
    assert.equal(await get('state=wrong&code=fixture', `127.0.0.1:${url.port}`), 400)
    assert.equal(await get('state=expected-state&state=expected-state&code=fixture', `127.0.0.1:${url.port}`), 400)
    assert.equal(await get('state=expected-state&code=fixture', `localhost:${url.port}`), 400)
    assert.equal(await get('state=expected-state&code=fixture', `127.0.0.1:${url.port}`), 200)
    assert.equal(await callback.code, 'fixture')
  } finally { callback.close() }
})

test('issuer validation rejects insecure, path and credential-bearing addresses', () => {
  assert.equal(normalizeIssuer('https://linear.example/'), issuer)
  for (const value of ['http://linear.example', 'https://linear.example/path', 'https://user:pass@linear.example',
    'https://linear.example/?query=1']) assert.throws(() => normalizeIssuer(value))
})
