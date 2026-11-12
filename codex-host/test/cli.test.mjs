import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { startHost } from '../src/daemon.mjs'
import { writeHostConfig } from '../src/host-config.mjs'
import { loadHostIdentity } from '../src/device.mjs'

const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url))
test('daemon serves configured origin and leaves root and recovery state on restart', async () => {
  const state = await mkdtemp(path.join(tmpdir(), 'll122-cli-'))
  const vault = { async get() { throw new Error('missing') }, async put() {}, async remove() {} }
  let host
  try {
    await writeHostConfig(state, 'https://example.test')
    await mkdir(path.join(state, 'roots', 'fixture'), { recursive: true })
    await mkdir(path.join(state, 'attempts'), { recursive: true })
    await writeFile(path.join(state, 'roots', 'fixture', 'owner.json'), 'root-fixture')
    await writeFile(path.join(state, 'attempts', 'fixture.json'), 'attempt-fixture')
    host = await startHost({ stateDirectory: state, port: 0, vault, deviceName: '测试 Mac' })
    await host.authorizationReady
    const port = host.server.address().port
    const response = await fetch(`http://127.0.0.1:${port}/identity`, { headers: { Origin: 'https://example.test' } })
    const first = await response.json()
    assert.equal(response.status, 200); assert.match(first.deviceId, /^[a-f0-9-]{36}$/)
    const denied = await fetch(`http://127.0.0.1:${port}/identity`, { headers: { Origin: 'https://other.test' } })
    assert.equal(denied.status, 403)
    await host.close(); host = await startHost({ stateDirectory: state, port: 0, vault, deviceName: '测试 Mac' })
    await host.authorizationReady
    assert.equal(host.identity.deviceId, first.deviceId)
    assert.notEqual(host.identity.hostInstanceId, first.hostInstanceId)
    assert.equal(await readFile(path.join(state, 'roots', 'fixture', 'owner.json'), 'utf8'), 'root-fixture')
    assert.equal(await readFile(path.join(state, 'attempts', 'fixture.json'), 'utf8'), 'attempt-fixture')
  } finally {
    if (host) await host.close()
    await rm(state, { recursive: true, force: true })
  }
})

test('daemon uses Keychain authorization to poll the batch service without a browser', async () => {
  const state = await mkdtemp(path.join(tmpdir(), 'll134-daemon-'))
  let host
  try {
    const origin = 'https://example.test'
    await writeHostConfig(state, origin)
    const identity = await loadHostIdentity(state)
    const bundle = Buffer.from(JSON.stringify({ issuer: origin, clientId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      accessToken: 'fixture.access', refreshToken: 'fixture.refresh', accessExpiresAt: Date.now() + 3600000 })).toString('base64url')
    const vault = { async get(key) { assert.equal(key, `hostoauth:${identity.deviceId}`); return bundle } }
    let gotPending
    const pending = new Promise(resolve => { gotPending = resolve })
    const routes = []
    const fetchImpl = async (url, options) => {
      routes.push(url)
      assert.equal(options.headers.Authorization, 'Bearer fixture.access')
      assert.ok(url.startsWith(`${origin}/api/collaboration-host/v3/`))
      const headers = { 'Content-Type': 'application/json', 'X-Linear-Lite-Host-Protocol': '3', 'X-Linear-Lite-Server-Time': new Date().toISOString() }
      if (url.endsWith('/heartbeat')) return new Response(JSON.stringify({ code: 200, data: { id: 1, userId: 2,
        deviceId: identity.deviceId, deviceName: '测试 Mac', authorized: true, online: true,
        lastHeartbeatAt: new Date().toISOString() } }), { status: 200, headers })
      assert.ok(url.endsWith('/batches/pending')); gotPending()
      return new Response(JSON.stringify({ code: 200, data: { items: [] } }), { status: 200, headers })
    }
    host = await startHost({ stateDirectory: state, port: 0, vault, fetchImpl, deviceName: '测试 Mac' })
    await pending
    assert.ok(routes.some(url => url.endsWith('/batches/pending')))
    assert.equal(host.batches.active.size, 0)
  } finally { if (host) await host.close(); await rm(state, { recursive: true, force: true }) }
})

test('legacy run command no longer accepts a user token', async () => {
  const child = spawn(process.execPath, [cli, 'run'], { stdio: ['pipe', 'pipe', 'pipe'] })
  child.stdin.end('fixture-user-token')
  let out = ''; child.stdout.on('data', value => { out += value }); child.stderr.on('data', value => { out += value })
  const exit = await new Promise(resolve => child.once('exit', resolve))
  assert.notEqual(exit, 0); assert(!out.includes('fixture-user-token'))
})

test('plan prints a disabled Asia Shanghai schedule', async () => {
  const child = spawn(process.execPath, [cli, 'plan'], { stdio: ['ignore', 'pipe', 'pipe'] })
  let out = ''; child.stdout.on('data', part => { out += part })
  const exit = await new Promise(resolve => child.once('exit', resolve))
  assert.equal(exit, 0)
  assert.deepEqual(JSON.parse(out), { cron: '0 1 * * *', timeZone: 'Asia/Shanghai', enabled: false })
})
