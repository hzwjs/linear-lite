import test from 'node:test'
import assert from 'node:assert/strict'
import { PassThrough } from 'node:stream'
import { EventEmitter } from 'node:events'
import { mkdtemp, realpath, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { createAppServer, resolveCodexProjectId, setCodexThreadName } from '../src/app-server.mjs'
import { HostError } from '../src/device.mjs'
import { HOST_ACTION_TOOL } from '../src/host-actions.mjs'

const features = Object.fromEntries(['apps', 'plugins', 'hooks', 'computer_use', 'browser_use', 'multi_agent',
  'code_mode_host', 'image_generation', 'in_app_browser', 'workspace_dependencies', 'remote_plugin', 'skill_mcp_dependency_install',
  'shell_tool', 'shell_snapshot', 'skill_search', 'view_image'].map(x => [x, false]))
features.code_mode_host = true

test('Codex project lookup matches canonical roots across pages and rejects ambiguous or failed lists', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll-project-list-')))
  const alias = path.join(base, 'alias')
  try {
    await symlink(base, alias)
    const calls = []
    const connection = { async call(method, params) {
      calls.push({ method, params })
      if (params.cursor === null) return { data: [{ id: 'other', roots: [{ path: path.join(base, 'missing') }] }], nextCursor: 'next' }
      return { data: [{ id: 'codex-project', roots: [{ path: alias }] }], nextCursor: null }
    } }
    assert.equal(await resolveCodexProjectId(connection, base), 'codex-project')
    assert.deepEqual(calls, [
      { method: 'project/list', params: { cursor: null, limit: 100 } },
      { method: 'project/list', params: { cursor: 'next', limit: 100 } },
    ])
    const zero = { async call() { return { data: [{ id: 'other', roots: [{ path: path.join(base, 'missing') }] }], nextCursor: null } } }
    assert.equal(await resolveCodexProjectId(zero, base), null)
    const ambiguous = { async call() { return { data: [
      { id: 'first', roots: [{ path: base }] }, { id: 'second', roots: [{ path: alias }] },
    ], nextCursor: null } } }
    await assert.rejects(resolveCodexProjectId(ambiguous, base), /匹配多个项目/)
    const failed = { async call() { throw new Error('项目列表不可用') } }
    await assert.rejects(resolveCodexProjectId(failed, base), /项目列表不可用/)
    const repeated = { async call() { return { data: [], nextCursor: 'same' } } }
    await assert.rejects(resolveCodexProjectId(repeated, base), /项目分页无效/)
  } finally { await rm(base, { recursive: true, force: true }) }
})

test('Codex thread names use the thread id and propagate RPC failures', async () => {
  const calls = []
  const connection = { async call(method, params) { calls.push({ method, params }); return {} } }
  await setCodexThreadName(connection, 'codex-thread', 'Linear Lite 协作 · 任务 · 4')
  assert.deepEqual(calls, [{ method: 'thread/name/set', params: {
    threadId: 'codex-thread', name: 'Linear Lite 协作 · 任务 · 4',
  } }])
  await assert.rejects(setCodexThreadName(connection, 'codex-thread', ' '), /会话名称无效/)
  await assert.rejects(setCodexThreadName({ async call() { throw new Error('命名不可用') } },
    'codex-thread', 'Linear Lite 协作'), /命名不可用/)
})

test('turn completion belongs to the active thread and turn', { skip: process.platform !== 'darwin' }, async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-rpc-')))
  const servers = []
  const calls = []
  const toolResponses = new Map()
  const threadNames = []
  const imageInputs = []
  let imageInputRejected = false
  let localHostEnabled = true
  const states = []
  const groups = { async starting() { states.push('starting') }, async started(pid) { states.push(`running:${pid}`) },
    async stopped() { states.push('idle') } }
  async function stopGroup(pid) { states.push(`stopped:${pid}`) }
  function spawnProcess(_cmd, args, options) {
    assert.equal(_cmd, 'codex')
    assert.equal(options.detached, true)
    assert.ok(args.some((value, index) => value === '--enable' && args[index + 1] === 'code_mode_host'))
    const child = new EventEmitter(); child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough()
    child.pid = 9000 + servers.length
    child.kill = () => { child.emit('exit', 0) }
    child.emitMessage = message => child.stdout.write(JSON.stringify(message) + '\n')
    servers.push(child)
    const isolated = args.includes('mcp_servers.demo.enabled=false')
    readline.createInterface({ input: child.stdin }).on('line', line => {
      const req = JSON.parse(line)
      if (req.id == null) return
      if (!req.method) { toolResponses.set(req.id, req.result); return }
      let result
      if (req.method === 'initialize') result = {}
      else if (req.method === 'config/read') result = { config: { mcp_servers: { demo: { enabled: !isolated } },
        approval_policy: 'never', sandbox_mode: 'read-only', web_search: 'disabled', features: { ...features, code_mode_host: localHostEnabled } } }
      else if (req.method === 'project/list') result = { data: [{ id: 'codex-project', roots: [{ path: root }] }], nextCursor: null }
      else if (req.method === 'thread/start') {
        assert.equal(req.params.projectId, 'codex-project')
        assert.equal(req.params.ephemeral, false)
        assert.deepEqual(req.params.environments, [])
        assert.deepEqual(req.params.dynamicTools, [HOST_ACTION_TOOL])
        result = { thread: { id: 'thread-1', ephemeral: false }, approvalPolicy: 'never', sandbox: { type: 'readOnly', networkAccess: false } }
      }
      else if (req.method === 'mcpServerStatus/list') result = { data: [{ runtimeStatus: 'disabled', tools: {} }], nextCursor: null }
      else if (req.method === 'thread/name/set') { threadNames.push(req.params.name); result = {} }
      else if (req.method === 'turn/start') { assert.deepEqual(req.params.environments, []); result = { turn: { id: 'turn-1' } } }
      else if (req.method === 'turn/steer') {
        imageInputs.push(req.params)
        if (imageInputRejected) {
          child.emitMessage({ id: req.id, error: { code: -32602, message: 'turn changed' } })
          return
        }
        result = { turnId: 'turn-1' }
      }
      else throw new Error('unexpected rpc')
      child.emitMessage({ id: req.id, result })
    })
    return child
  }
  try {
    const app = await createAppServer({ cwd: root, onToolCall: params => {
      calls.push(params)
      if (params.arguments.action === 'checkpoint') throw new HostError(400, 'TOOL_INPUT_INVALID', 'blockedReason 须为字符串或省略')
      if (params.arguments.action === 'read_document') throw new HostError(409, 'ACTION_REJECTED', '文档已变化，请重读')
      if (params.arguments.action === 'list_documents') throw new Error('claim-secret')
      if (params.arguments.action === 'claim') return { accepted: true, task: { title: '修复会话展示', description: 'fixture-description' } }
      return { state: 'READY' }
    }, loadTaskImages: async description => {
      assert.equal(description, 'fixture-description')
      return [{ index: 1, imageUrl: 'data:image/png;base64,cG5n' }, { index: 2, error: '图片下载失败' }]
    }, spawnProcess, groups, stopGroup })
    const run = await app.run('fixture')
    const server = servers.at(-1)
    server.emitMessage({ id: 98, method: 'item/tool/call', params: {
      threadId: 'thread-1', turnId: 'turn-1', tool: 'host_action',
      arguments: { action: 'observe' },
    } })
    server.emitMessage({ id: 99, method: 'item/tool/call', params: {
      threadId: 'thread-1', turnId: 'turn-1', tool: 'host_action',
      arguments: { action: 'observe', payload: {} },
    } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(calls.length, 1)
    assert.equal(toolResponses.get(98).success, false)
    assert.match(toolResponses.get(98).contentItems[0].text, /payload: \{\}/)
    assert.equal(toolResponses.get(99).success, true)
    assert.deepEqual(JSON.parse(toolResponses.get(99).contentItems[0].text), { state: 'READY' })
    for (const [id, action, threadId, payload] of [
      [100, 'checkpoint', 'thread-1', { artifactRefs: [] }], [101, 'unknown', 'thread-1', {}],
      [102, 'observe', 'other-thread', {}], [103, 'read_document', 'thread-1', { documentId: 1 }],
      [104, 'list_documents', 'thread-1', {}],
    ]) {
      server.emitMessage({ id, method: 'item/tool/call', params: {
        threadId, turnId: 'turn-1', tool: 'host_action', arguments: { action, payload },
      } })
    }
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(toolResponses.get(100).contentItems[0].text, 'blockedReason 须为字符串或省略')
    assert.match(toolResponses.get(101).contentItems[0].text, /宿主操作参数无效/)
    assert.equal(toolResponses.get(102).success, false)
    assert.equal(toolResponses.get(103).contentItems[0].text, '文档已变化，请重读')
    assert.equal(toolResponses.get(104).contentItems[0].text, '宿主操作遭拒')
    assert.equal(calls.length, 4)
    server.emitMessage({ id: 105, method: 'item/tool/call', params: {
      threadId: 'thread-1', turnId: 'turn-1', tool: 'host_action', arguments: { action: 'claim', payload: { taskId: 1, inputRevision: 1 } },
    } })
    await new Promise(resolve => setImmediate(resolve))
    assert.deepEqual(threadNames, ['Linear Lite 协作', 'Linear Lite 协作 · 修复会话展示'])
    const { contentItems } = toolResponses.get(105)
    assert.equal(contentItems.length, 1)
    const [claimText] = contentItems
    assert.deepEqual(JSON.parse(claimText.text).descriptionImages, [{ index: 1, included: true }, { index: 2, error: '图片下载失败' }])
    assert.equal(claimText.text.includes('data:image/'), false)
    assert.equal(imageInputs.length, 1)
    assert.equal(imageInputs[0].threadId, 'thread-1')
    assert.equal(imageInputs[0].expectedTurnId, 'turn-1')
    assert.deepEqual(imageInputs[0].input.slice(1), [{ type: 'image', url: 'data:image/png;base64,cG5n' }])
    imageInputRejected = true
    server.emitMessage({ id: 106, method: 'item/tool/call', params: {
      threadId: 'thread-1', turnId: 'turn-1', tool: 'host_action', arguments: { action: 'claim', payload: { taskId: 1, inputRevision: 1 } },
    } })
    await new Promise(resolve => setImmediate(resolve))
    const failedImage = JSON.parse(toolResponses.get(106).contentItems[0].text)
    assert.equal(failedImage.accepted, true)
    assert.deepEqual(failedImage.descriptionImages, [{ index: 1, error: 'Codex 未接收描述图片' }, { index: 2, error: '图片下载失败' }])
    let resolved = false
    run.completion.then(() => { resolved = true })
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'wrong-turn', status: 'completed' } } })
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'wrong-thread', turn: { id: 'turn-1', status: 'completed' } } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(resolved, false)
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } })
    assert.equal((await run.completion).turn.id, 'turn-1')
    await app.close()
    assert.deepEqual(states.slice(0, 8), ['starting', 'running:9000', 'stopped:9000', 'idle',
      'starting', 'running:9001', 'stopped:9001', 'idle'])
    localHostEnabled = false
    await assert.rejects(createAppServer({ cwd: root, onToolCall: () => {}, spawnProcess, groups, stopGroup }), /执行边界校验失败/)
  } finally { await rm(root, { recursive: true, force: true }) }
})
