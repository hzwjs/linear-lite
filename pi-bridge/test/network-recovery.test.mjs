import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient, createRecoveringSerialQueue } from '../src/index.mjs'

function settingsStore() {
  return {
    async read() {
      return { apiBaseUrl: 'http://linear-lite.test', agentToken: 'agent-token' }
    },
  }
}

test('API request retries a transient network failure and preserves the response', async () => {
  let calls = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    fetchImpl: async (_url, options) => {
      calls += 1
      assert.ok(options.signal)
      if (calls === 1) throw new TypeError('network is offline')
      return new Response(JSON.stringify({ code: 200, data: { jobId: 42 } }), { status: 200 })
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  assert.deepEqual(await api('/api/agent/jobs/claim', { method: 'POST' }), { jobId: 42 })
  assert.equal(calls, 2)
})

test('API request aborts a hanging network request and retries after recovery', async () => {
  let calls = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    fetchImpl: (_url, options) => new Promise((resolve, reject) => {
      calls += 1
      if (calls === 1) {
        options.signal.addEventListener('abort', () => reject(new DOMException('timed out', 'AbortError')), { once: true })
        return
      }
      resolve(new Response(JSON.stringify({ code: 200, data: 'recovered' }), { status: 200 }))
    }),
    requestTimeoutMs: 1,
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  assert.equal(await api('/api/agent/jobs/heartbeat', { method: 'POST' }), 'recovered')
  assert.equal(calls, 2)
})

test('recovering serial queue continues with later batches after an upload failure', async () => {
  const queue = createRecoveringSerialQueue()
  const calls = []
  const first = queue.enqueue(async () => {
    calls.push('first')
    throw new Error('temporary upload failure')
  })
  const second = queue.enqueue(async () => {
    calls.push('second')
    return 'ok'
  })

  await assert.rejects(first, /temporary upload failure/)
  assert.equal(await second, 'ok')
  assert.deepEqual(calls, ['first', 'second'])
})
