import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient, createRecoveringSerialQueue } from '../src/index.mjs'

function settingsStore() {
  return {
    async read() {
      return { apiBaseUrl: 'http://linear-lite.test' }
    },
  }
}

function credentialStore() {
  return { value: 'execution-credential' }
}

test('API request retries a transient network failure and preserves the response', async () => {
  let calls = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    credentialStore: credentialStore(),
    fetchImpl: async (_url, options) => {
      calls += 1
      assert.ok(options.signal)
      if (calls === 1) throw new TypeError('network is offline')
      return new Response(JSON.stringify({ code: 200, data: { jobId: 42 } }), { status: 200 })
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  assert.deepEqual(await api('/api/bridge/jobs/claim', { method: 'POST' }), { jobId: 42 })
  assert.equal(calls, 2)
})

test('API request aborts a hanging network request and retries after recovery', async () => {
  let calls = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    credentialStore: credentialStore(),
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

  assert.equal(await api('/api/bridge/jobs/heartbeat', { method: 'POST' }), 'recovered')
  assert.equal(calls, 2)
})

test('API request reloads the latest connection settings after a transient failure', async () => {
  let calls = 0
  const settings = { apiBaseUrl: 'http://old-linear-lite.test' }
  const api = createApiClient({
    settingsStore: { read: async () => ({ ...settings }) },
    credentialStore: credentialStore(),
    fetchImpl: async (url, options) => {
      calls += 1
      if (calls === 1) {
        settings.apiBaseUrl = 'http://new-linear-lite.test'
        throw new TypeError('network is offline')
      }
      assert.equal(url, 'http://new-linear-lite.test/api/bridge/jobs/claim')
      assert.equal(options.headers['X-Execution-Credential'], 'execution-credential')
      return new Response(JSON.stringify({ code: 200, data: 'recovered-with-new-settings' }), { status: 200 })
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  assert.equal(await api('/api/bridge/jobs/claim', { method: 'POST' }), 'recovered-with-new-settings')
  assert.equal(calls, 2)
})

test('invalid execution credential is discarded so the next execution can connect', async () => {
  const credential = credentialStore()
  let configurationRequired = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    credentialStore: credential,
    fetchImpl: async () => new Response(JSON.stringify({ code: 401, message: 'credential expired' }), { status: 401 }),
    onCredentialRejected: () => {
      configurationRequired += 1
      credential.value = ''
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  await assert.rejects(api('/api/bridge/jobs/claim', { method: 'POST' }), /credential expired/)
  assert.equal(configurationRequired, 1)
  assert.equal(credential.value, '')
})

test('late failure from an old credential does not discard a newer credential', async () => {
  const credential = credentialStore()
  let configurationRequired = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    credentialStore: credential,
    fetchImpl: async (_url, options) => {
      credential.value = 'new-execution-credential'
      return new Response(JSON.stringify({ code: 401, message: 'old credential expired' }), { status: 401 })
    },
    onCredentialRejected: (failedToken) => {
      configurationRequired += 1
      if (failedToken === credential.value) credential.value = ''
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  await assert.rejects(api('/api/bridge/jobs/claim', { method: 'POST' }), /old credential expired/)
  assert.equal(configurationRequired, 1)
  assert.equal(credential.value, 'new-execution-credential')
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
