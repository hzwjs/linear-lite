import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createCollaborationClient, leaseDeadline, budgetDeadline } from '../src/api-client.mjs'

test('client sends credentials in headers, rejects redirects and reads one response path', async () => {
  let captured
  const client = createCollaborationClient({ baseUrl: 'https://linear.example', getAccessToken: async () => 'fixture-auth',
    fetchImpl: async (url, options) => {
      captured = { url, options }
      return new Response(JSON.stringify({ code: 200, data: { bindings: [] } }),
        { headers: { 'X-Linear-Lite-Host-Protocol': '3', 'X-Linear-Lite-Server-Time': '2026-11-10T00:00:00Z' } })
    } })
  const result = await client.call('batches/context', { projectId: 6, batchId: 'batch-1' })
  assert.deepEqual(result.result, { bindings: [] })
  assert.equal(captured.url, 'https://linear.example/api/collaboration-host/v3/batches/context')
  assert.equal(captured.options.headers.Authorization, 'Bearer fixture-auth')
  assert.equal(captured.options.redirect, 'error')
  assert.equal(captured.options.cache, 'no-store')
  assert(!captured.options.body.includes('fixture-auth'))
})

test('missing server clock and raw network errors fail without exposing credentials', async () => {
  const base = { baseUrl: 'https://linear.example', getAccessToken: async () => 'fixture-auth' }
  const missing = createCollaborationClient({ ...base, fetchImpl: async () => new Response('{"code":200,"data":{}}', { headers: { 'X-Linear-Lite-Host-Protocol': '3' } }) })
  await assert.rejects(() => missing.call('batches/context', {}), { code: 'LEASE_CLOCK_INVALID' })
  const failed = createCollaborationClient({ ...base, fetchImpl: async () => { throw new Error('fixture-auth') } })
  await assert.rejects(() => failed.call('batches/context', {}), error => error.message === '协作服务连接失败')
})

test('recovery uses only the batch capability and execution credentials rotate through one provider', async () => {
  const calls = []; let issued = 0
  const client = createCollaborationClient({ baseUrl: 'https://linear.example',
    getAccessToken: async () => `fixture-access-${++issued}`,
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return new Response('{"code":200,"data":{}}', { headers: { 'X-Linear-Lite-Host-Protocol': '3', 'X-Linear-Lite-Server-Time': new Date().toISOString() } })
    } })
  await client.call('batches/context', {})
  await client.call('batches/renew', {})
  await client.recover('confirm-stop', { batchToken: 'fixture-batch' })
  assert.equal(calls[1].options.headers.Authorization, 'Bearer fixture-access-2')
  assert.equal(calls[2].url, 'https://linear.example/api/collaboration-host/v3/recovery/confirm-stop')
  assert.equal(Object.hasOwn(calls[2].options.headers, 'Authorization'), false)
  assert.equal(issued, 2)
  assert.deepEqual(client.knownCredentials(), ['fixture-access-1', 'fixture-access-2'])
  await assert.rejects(client.call('batches/issue', {}), { code: 'ROUTE_INVALID' })
  await assert.rejects(client.recover('tasks/claim', {}), { code: 'ROUTE_INVALID' })
})

test('a server without the current protocol never enables work or falls back to an old route', async () => {
  const urls = []
  const client = createCollaborationClient({ baseUrl: 'https://linear.example',
    getAccessToken: async () => 'fixture-auth',
    fetchImpl: async url => {
      urls.push(url)
      return url.includes('/recovery/') ? new Response(null, { status: 404 })
        : new Response('{"code":200,"data":{"items":[]}}')
    } })
  await assert.rejects(client.call('batches/pending', {}), { status: 426, code: 'HOST_PROTOCOL_MISMATCH' })
  await assert.rejects(client.recover('finish', { batchToken: 'fixture-batch' }), { code: 'HOST_PROTOCOL_MISMATCH' })
  assert.deepEqual(urls, [
    'https://linear.example/api/collaboration-host/v3/batches/pending',
    'https://linear.example/api/collaboration-host/v3/recovery/finish',
  ])
})

test('monotonic lease uses fresh server time and never revives a replayed expiry', () => {
  const value = { leaseUntil: '2026-11-10T00:05:00Z', serverTime: '2026-11-10T00:00:00Z', requestStartedAt: 1000, now: 2000 }
  assert.equal(leaseDeadline(value), 300999)
  assert.throws(() => leaseDeadline({ ...value, serverTime: '2026-11-10T00:06:00Z' }), { code: 'LEASE_EXPIRED' })
  assert.throws(() => leaseDeadline({ ...value, now: 302000 }), { code: 'LEASE_EXPIRED' })
  assert.throws(() => leaseDeadline({ ...value, leaseUntil: '2026-11-10T00:05:00' }), { code: 'LEASE_CLOCK_INVALID' })
})

test('budget uses the original response receipt and accepts an elapsed deadline for stop-only finalization', () => {
  const receipt = { deadlineAt: '2026-11-10T00:05:00Z', serverTime: '2026-11-10T00:00:00Z',
    requestStartedAt: 1000, responseReceivedAt: 2000, now: 9000 }
  assert.equal(budgetDeadline(receipt), 302001)
  assert.equal(budgetDeadline({ ...receipt, serverTime: '2026-11-10T00:06:00Z' }), -57999)
  assert.throws(() => budgetDeadline({ ...receipt, responseReceivedAt: 500 }), { code: 'LEASE_CLOCK_INVALID' })
})
