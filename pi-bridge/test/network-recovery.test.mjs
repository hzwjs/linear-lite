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

function attachmentStore() {
  return { value: 'execution-attachment' }
}

test('API request retries a transient network failure and preserves the response', async () => {
  let calls = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    attachmentStore: attachmentStore(),
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
    attachmentStore: attachmentStore(),
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
    attachmentStore: attachmentStore(),
    fetchImpl: async (url, options) => {
      calls += 1
      if (calls === 1) {
        settings.apiBaseUrl = 'http://new-linear-lite.test'
        throw new TypeError('network is offline')
      }
      assert.equal(url, 'http://new-linear-lite.test/api/bridge/jobs/claim')
      assert.equal(options.headers['X-Execution-Attachment'], 'execution-attachment')
      return new Response(JSON.stringify({ code: 200, data: 'recovered-with-new-settings' }), { status: 200 })
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  assert.equal(await api('/api/bridge/jobs/claim', { method: 'POST' }), 'recovered-with-new-settings')
  assert.equal(calls, 2)
})

test('invalid execution attachment is discarded so the next panel attach can recover polling', async () => {
  const attachment = attachmentStore()
  let configurationRequired = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    attachmentStore: attachment,
    fetchImpl: async () => new Response(JSON.stringify({ code: 401, message: 'attachment expired' }), { status: 401 }),
    onConfigurationRequired: () => {
      configurationRequired += 1
      attachment.value = ''
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  await assert.rejects(api('/api/bridge/jobs/claim', { method: 'POST' }), /attachment expired/)
  assert.equal(configurationRequired, 1)
  assert.equal(attachment.value, '')
})

test('late failure from an old attachment does not discard a newer attachment', async () => {
  const attachment = attachmentStore()
  let configurationRequired = 0
  const api = createApiClient({
    settingsStore: settingsStore(),
    attachmentStore: attachment,
    fetchImpl: async (_url, options) => {
      attachment.value = 'new-execution-attachment'
      return new Response(JSON.stringify({ code: 401, message: 'old attachment expired' }), { status: 401 })
    },
    onConfigurationRequired: (failedToken) => {
      configurationRequired += 1
      if (failedToken === attachment.value) attachment.value = ''
    },
    retryDelayMs: 0,
    sleepImpl: async () => {},
  })

  await assert.rejects(api('/api/bridge/jobs/claim', { method: 'POST' }), /old attachment expired/)
  assert.equal(configurationRequired, 1)
  assert.equal(attachment.value, 'new-execution-attachment')
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
