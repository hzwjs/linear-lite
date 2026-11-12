import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { mkdtemp, rm, realpath } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { once } from 'node:events'
import { createHostServer } from '../src/server.mjs'

test('local metadata API checks Host, Origin, preflight, device and directory', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'll-host-http-'))
  const origin = 'https://linear.example'
  let connects = 0, disconnects = 0
  const auth = { status: () => ({ connection: 'AUTH_REQUIRED', userId: null, grantId: null,
    deviceName: null, lastHeartbeatAt: null, accessToken: 'fixture-secret' }),
  async connect() { connects++ }, async disconnect() { disconnects++ } }
  const server = createHostServer({ identity: { deviceId: 'device', hostInstanceId: 'host' }, allowedOrigin: origin, auth, port: 0 })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  const port = server.address().port
  function request(url, { method = 'GET', body, headers = {} } = {}) {
    return new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port, path: url, method,
        headers: { Host: `127.0.0.1:${port}`, Origin: origin, 'Content-Type': 'application/json', ...headers } }, res => {
        const data = []; res.on('data', item => data.push(item)); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(data).toString('utf8') }))
      }); req.on('error', reject); req.end(body)
    })
  }
  try {
    assert.deepEqual(JSON.parse((await request('/identity')).text), { deviceId: 'device', hostInstanceId: 'host', protocolVersion: 3 })
    assert.equal((await request('/identity', { headers: { Origin: 'https://attacker.example' } })).status, 403)
    assert.equal((await request('/identity', { headers: { Host: `localhost:${port}` } })).status, 403)
    assert.equal((await request('/identity', { headers: { Origin: 'null' } })).status, 403)
    const preflight = await request('/validate-directory', { method: 'OPTIONS', headers: { 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type', 'Access-Control-Request-Private-Network': 'true' } })
    assert.equal(preflight.status, 204); assert.equal(preflight.headers['access-control-allow-private-network'], 'true')
    const valid = await request('/validate-directory', { method: 'POST', body: JSON.stringify({ deviceId: 'device', projectDirectory: directory }) })
    assert.equal(valid.status, 200); assert.equal(JSON.parse(valid.text).projectDirectory, await realpath(directory))
    assert.equal(JSON.parse(valid.text).protocolVersion, 3)
    assert.equal((await request('/validate-directory', { method: 'POST', body: JSON.stringify({ deviceId: 'other', projectDirectory: directory }) })).status, 409)
    const invalid = await request('/validate-directory', { method: 'POST', body: '{"fixture-secret":' })
    assert.equal(invalid.status, 400); assert(!invalid.text.includes('fixture-secret'))
    assert.equal((await request('/validate-directory', { method: 'POST', body: '{}', headers: { 'Content-Type': 'text/plain' } })).status, 415)
    const status = await request('/status')
    assert.equal(JSON.parse(status.text).connection, 'AUTH_REQUIRED'); assert.equal(JSON.parse(status.text).protocolVersion, 3)
    assert(!status.text.includes('fixture-secret'))
    assert.equal((await request('/status', { headers: { Authorization: 'Bearer fixture-secret' } })).status, 403)
    assert.equal((await request('/connect', { method: 'POST', body: JSON.stringify({ token: 'fixture-secret' }) })).status, 400)
    assert.equal(connects, 0)
    assert.equal((await request('/connect', { method: 'POST', body: '{}' })).status, 202)
    assert.equal(connects, 1)
    assert.equal((await request('/disconnect', { method: 'POST', body: '{}' })).status, 200)
    assert.equal(disconnects, 1)
    assert.equal((await request('/run', { method: 'POST', body: '{}' })).status, 404)
  } finally { await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true }) }
})
