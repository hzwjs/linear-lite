import test from 'node:test'
import assert from 'node:assert/strict'
import { PassThrough } from 'node:stream'
import { EventEmitter } from 'node:events'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { createAppServer } from '../src/app-server.mjs'

const features = Object.fromEntries(['apps', 'plugins', 'hooks', 'computer_use', 'browser_use', 'multi_agent',
  'code_mode_host', 'image_generation', 'in_app_browser', 'workspace_dependencies', 'remote_plugin', 'skill_mcp_dependency_install'].map(x => [x, false]))

test('turn completion belongs to the active thread and turn', { skip: process.platform !== 'darwin' }, async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-rpc-')))
  const servers = []
  function spawnProcess(_cmd, args) {
    const child = new EventEmitter(); child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough()
    child.kill = () => { child.emit('exit', 0) }
    child.emitMessage = message => child.stdout.write(JSON.stringify(message) + '\n')
    servers.push(child)
    const isolated = args.includes('mcp_servers.demo.enabled=false')
    readline.createInterface({ input: child.stdin }).on('line', line => {
      const req = JSON.parse(line)
      if (req.id == null) return
      let result
      if (req.method === 'initialize') result = {}
      else if (req.method === 'config/read') result = { config: { mcp_servers: { demo: { enabled: !isolated } },
        approval_policy: 'never', sandbox_mode: 'read-only', features } }
      else if (req.method === 'thread/start') result = { thread: { id: 'thread-1', ephemeral: true }, approvalPolicy: 'never', sandbox: { type: 'readOnly', networkAccess: false } }
      else if (req.method === 'mcpServerStatus/list') result = { data: [{ runtimeStatus: 'disabled', tools: {} }], nextCursor: null }
      else if (req.method === 'turn/start') result = { turn: { id: 'turn-1' } }
      else throw new Error('unexpected rpc')
      child.emitMessage({ id: req.id, result })
    })
    return child
  }
  try {
    const app = await createAppServer({ cwd: root, onToolCall: () => {}, spawnProcess })
    const run = await app.run('fixture')
    const server = servers.at(-1)
    let resolved = false
    run.completion.then(() => { resolved = true })
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'wrong-turn', status: 'completed' } } })
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'wrong-thread', turn: { id: 'turn-1', status: 'completed' } } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(resolved, false)
    server.emitMessage({ method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status: 'completed' } } })
    assert.equal((await run.completion).turn.id, 'turn-1')
    app.close()
  } finally { await rm(root, { recursive: true, force: true }) }
})
