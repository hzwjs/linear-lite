import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createCollaborationClient, leaseDeadline } from '../src/api-client.mjs'

test('client sends credentials in headers, rejects redirects and reads one response path', async () => {
  let captured
  const client = createCollaborationClient({ baseUrl: 'https://linear.example', authToken: 'fixture-auth',
    fetchImpl: async (url, options) => {
      captured = { url, options }
      return new Response(JSON.stringify({ code: 200, data: { bindings: [] } }),
        { headers: { 'X-Linear-Lite-Server-Time': '2026-11-10T00:00:00Z' } })
    } })
  const result = await client.call('bindings/list', { projectId: 6 })
  assert.deepEqual(result.result, { bindings: [] })
  assert.equal(captured.url, 'https://linear.example/api/collaboration/bindings/list')
  assert.equal(captured.options.headers.Authorization, 'Bearer fixture-auth')
  assert.equal(captured.options.redirect, 'error')
  assert.equal(captured.options.cache, 'no-store')
  assert(!captured.options.body.includes('fixture-auth'))
})

test('missing server clock and raw network errors fail without exposing credentials', async () => {
  const base = { baseUrl: 'https://linear.example', authToken: 'fixture-auth' }
  const missing = createCollaborationClient({ ...base, fetchImpl: async () => new Response('{"code":200,"data":{}}') })
  await assert.rejects(() => missing.call('bindings/list', {}), { code: 'LEASE_CLOCK_INVALID' })
  const failed = createCollaborationClient({ ...base, fetchImpl: async () => { throw new Error('fixture-auth') } })
  await assert.rejects(() => failed.call('bindings/list', {}), error => error.message === '协作服务连接失败')
})

test('monotonic lease uses fresh server time and never revives a replayed expiry', () => {
  const value = { leaseUntil: '2026-11-10T00:05:00Z', serverTime: '2026-11-10T00:00:00Z', requestStartedAt: 1000, now: 2000 }
  assert.equal(leaseDeadline(value), 300999)
  assert.throws(() => leaseDeadline({ ...value, serverTime: '2026-11-10T00:06:00Z' }), { code: 'LEASE_EXPIRED' })
  assert.throws(() => leaseDeadline({ ...value, now: 302000 }), { code: 'LEASE_EXPIRED' })
  assert.throws(() => leaseDeadline({ ...value, leaseUntil: '2026-11-10T00:05:00' }), { code: 'LEASE_CLOCK_INVALID' })
})
