import assert from 'node:assert/strict'
import test from 'node:test'
import { processSettingsRequest } from '../src/index.mjs'

function createRpcFixture({ models, levels, state }) {
  const commands = []
  const listeners = new Set()
  const child = {
    exitCode: null,
    signalCode: null,
    kill: () => {},
    stdin: {
      write(line) {
        const command = JSON.parse(line)
        commands.push(command)
        let data
        if (command.type === 'get_available_models') data = { models }
        else if (command.type === 'get_available_thinking_levels') data = { levels }
        else if (command.type === 'get_state') data = state
        else if (command.type === 'set_model') {
          state.model = models.find((model) => model.provider === command.provider && model.id === command.modelId)
        } else if (command.type === 'set_thinking_level') state.thinkingLevel = command.level
        queueMicrotask(() => {
          const message = { id: command.id, type: 'response', success: true, data }
          for (const listener of [...listeners]) listener(message)
        })
      },
    },
  }
  return {
    commands,
    child,
    rpc: {
      child,
      reader: {
        waitFor(predicate) {
          return new Promise((resolve) => {
            const listener = (message) => {
              if (!predicate(message)) return
              listeners.delete(listener)
              resolve(message)
            }
            listeners.add(listener)
          })
        },
      },
    },
  }
}

function request(action, extra = {}) {
  return { requestId: 'settings-1', executionId: 'exec-1', piSessionId: 'pi-1', projectId: 7, action, ...extra }
}

async function run(requestPayload, fixture) {
  const calls = []
  const result = await processSettingsRequest(requestPayload, {
    resolveTaskDirectoryFn: async () => '/workspace',
    startPiFn: async () => fixture.child,
    rpcRuntimeFn: () => fixture.rpc,
    apiClient: async (path, options) => { calls.push({ path, body: JSON.parse(options.body) }) },
  })
  return { result, calls }
}

const models = [
  { provider: 'anthropic', id: 'sonnet', name: 'Claude Sonnet' },
  { provider: 'openai', id: 'sonnet', name: 'OpenAI Sonnet' },
]

test('settings read returns Pi directories and current state without a model mutation', async () => {
  const fixture = createRpcFixture({ models, levels: ['low', 'high'], state: { sessionId: 'pi-1', model: models[0], thinkingLevel: 'high' } })
  const { result, calls } = await run(request('read'), fixture)
  assert.deepEqual(result, {
    executionId: 'exec-1',
    current: { model: { provider: 'anthropic', modelId: 'sonnet', label: 'Claude Sonnet' }, thinkingLevel: 'high' },
    models: [
      { provider: 'anthropic', modelId: 'sonnet', label: 'Claude Sonnet' },
      { provider: 'openai', modelId: 'sonnet', label: 'OpenAI Sonnet' },
    ],
    thinkingLevels: ['low', 'high'],
  })
  assert.equal(fixture.commands.some((command) => command.type === 'set_model'), false)
  assert.deepEqual(calls.map((call) => call.path), ['/api/bridge/settings-requests/settings-1/complete'])
})

test('set_model uses the exact provider and modelId selected from Pi directory', async () => {
  const fixture = createRpcFixture({ models, levels: ['low', 'high'], state: { sessionId: 'pi-1', model: models[0], thinkingLevel: 'low' } })
  const { result } = await run(request('set_model', { provider: 'openai', modelId: 'sonnet' }), fixture)
  assert.deepEqual(fixture.commands.find((command) => command.type === 'set_model'), {
    id: fixture.commands.find((command) => command.type === 'set_model').id,
    type: 'set_model', provider: 'openai', modelId: 'sonnet',
  })
  assert.equal(result.current.model.provider, 'openai')
})

test('unknown model fails explicitly without sending set_model', async () => {
  const fixture = createRpcFixture({ models, levels: ['low'], state: { sessionId: 'pi-1', model: models[0], thinkingLevel: 'low' } })
  const calls = []
  await assert.rejects(
    processSettingsRequest(request('set_model', { provider: 'unknown', modelId: 'missing' }), {
      resolveTaskDirectoryFn: async () => '/workspace', startPiFn: async () => fixture.child, rpcRuntimeFn: () => fixture.rpc,
      apiClient: async (path, options) => { calls.push({ path, body: JSON.parse(options.body) }) },
    }),
    /Pi 中不存在模型: unknown\/missing/
  )
  assert.equal(fixture.commands.some((command) => command.type === 'set_model'), false)
  assert.equal(calls[0].path, '/api/bridge/settings-requests/settings-1/fail')
})

test('set_thinking_level only accepts a value from Pi directory', async () => {
  const fixture = createRpcFixture({ models, levels: ['low', 'high'], state: { sessionId: 'pi-1', model: models[0], thinkingLevel: 'low' } })
  await run(request('set_thinking_level', { level: 'high' }), fixture)
  assert.equal(fixture.commands.some((command) => command.type === 'set_thinking_level'), true)

  const invalidFixture = createRpcFixture({ models, levels: ['low'], state: { sessionId: 'pi-1', model: models[0], thinkingLevel: 'low' } })
  await assert.rejects(
    run(request('set_thinking_level', { level: 'high' }), invalidFixture),
    /Pi 不支持推理强度: high/
  )
  assert.equal(invalidFixture.commands.some((command) => command.type === 'set_thinking_level'), false)
})
