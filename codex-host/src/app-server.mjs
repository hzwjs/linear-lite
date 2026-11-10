import { spawn } from 'node:child_process'
import { realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import readline from 'node:readline'

const DISABLED_FEATURES = Object.freeze([
  'apps', 'plugins', 'hooks', 'computer_use', 'browser_use', 'multi_agent', 'code_mode_host',
  'image_generation', 'in_app_browser', 'workspace_dependencies', 'remote_plugin', 'skill_mcp_dependency_install',
])
const BASE_ARGS = Object.freeze([
  'app-server', '--listen', 'stdio://', '-c', 'approval_policy="never"', '-c', 'sandbox_mode="read-only"',
  ...DISABLED_FEATURES.flatMap(value => ['--disable', value]),
])
const TOOL = Object.freeze({
  type: 'function', name: 'host_action', description: '读取协作环境，领取任务，通过宿主修改授权文件、留痕并提交成果。',
  inputSchema: {
    type: 'object', properties: { action: { type: 'string' }, payload: { type: 'object' } },
    required: ['action', 'payload'], additionalProperties: false,
  },
})

/** App Server 的标准输入输出为唯一控制通道；不写入协议日志。 */
class RpcConnection {
  constructor(child, onRequest) {
    this.child = child
    this.onRequest = onRequest
    this.nextId = 0
    this.pending = new Map()
    this.turnWaiter = null
    this.closed = false
    child.stdin.on('error', () => this.failAll())
    child.stderr.resume()
    readline.createInterface({ input: child.stdout }).on('line', line => this.receive(line))
    child.on('error', () => this.failAll())
    child.on('exit', () => this.failAll())
  }
  send(message) {
    if (this.closed || !this.child.stdin.writable) throw new Error('Codex 控制通道不可用')
    this.child.stdin.write(JSON.stringify(message) + '\n')
  }
  call(method, params, timeoutMs = 15000) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('Codex 请求超时')) }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      try { this.send({ id, method, params }) }
      catch { clearTimeout(timer); this.pending.delete(id); reject(new Error('Codex 控制通道不可用')) }
    })
  }
  notify(method, params) { this.send({ method, params }) }
  async receive(line) {
    let message
    try { message = JSON.parse(line) } catch { this.failAll(); return }
    if (message.id != null && !message.method && this.pending.has(message.id)) {
      const entry = this.pending.get(message.id); this.pending.delete(message.id); clearTimeout(entry.timer)
      if (message.error) entry.reject(new Error('Codex 请求遭拒'))
      else entry.resolve(message.result)
      return
    }
    if (message.method === 'turn/completed' && this.turnWaiter) {
      const waiter = this.turnWaiter
      if (message.params?.threadId !== waiter.threadId) return
      if (!waiter.turnId) { waiter.events.push(message.params); return }
      if (message.params?.turn?.id !== waiter.turnId) return
      this.turnWaiter = null; waiter.resolve(message.params); return
    }
    if (message.method && message.id != null) {
      try {
        if (message.method !== 'item/tool/call' || !this.onRequest) throw new Error('unsupported')
        const result = await this.onRequest(message.params)
        this.send({ id: message.id, result: { success: true, contentItems: [{ type: 'inputText', text: JSON.stringify(result) }] } })
      } catch {
        try { this.send({ id: message.id, result: { success: false, contentItems: [{ type: 'inputText', text: '宿主操作遭拒' }] } }) } catch {}
      }
    }
  }
  failAll() {
    if (this.closed) return
    this.closed = true
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(new Error('Codex 控制通道已结束')) }
    this.pending.clear()
    if (this.turnWaiter) { const waiter = this.turnWaiter; this.turnWaiter = null; waiter.resolve(null) }
  }
  close() { this.child.stdin.end(); this.child.kill('SIGTERM'); this.failAll() }
}

function createConnection(cwd, extra, onRequest, spawnProcess) {
  // 外层进程对项目根目录没有写权限；宿主动态工具在此封套之外执行。
  const quoted = cwd.replaceAll('\\', '\\\\').replaceAll('"', '\\"')
  const profile = `(version 1)(allow default)(deny file-write* (subpath "${quoted}"))`
  const child = spawnProcess('/usr/bin/sandbox-exec', ['-p', profile, 'codex', ...BASE_ARGS, ...extra],
    { cwd, stdio: ['pipe', 'pipe', 'pipe'] })
  return new RpcConnection(child, onRequest)
}

async function initialize(connection) {
  await connection.call('initialize', { clientInfo: { name: 'linear-lite-codex-host', version: '1' }, capabilities: { experimentalApi: true } })
  connection.notify('initialized', {})
}

async function checkInventory(connection, threadId) {
  let cursor = null
  do {
    const response = await connection.call('mcpServerStatus/list', { threadId, cursor, limit: 100, detail: 'toolsAndAuthOnly' })
    for (const server of response.data) {
      if (server.runtimeStatus !== 'disabled' || !server.tools || typeof server.tools !== 'object'
          || Object.keys(server.tools).length !== 0)
        throw new Error('Codex MCP 隔离校验失败')
    }
    cursor = response.nextCursor ?? null
  } while (cursor)
}

/** 读取并逐项覆盖 MCP 配置；结果不满足约束时不启动回合。 */
export async function createAppServer({ cwd, onToolCall, spawnProcess = spawn }) {
  if (process.platform !== 'darwin') throw new Error('当前宿主平台缺少经过核验的执行边界')
  if (typeof cwd !== 'string' || !path.isAbsolute(cwd) || await realpath(cwd) !== cwd
      || !(await stat(cwd)).isDirectory()) throw new Error('Codex 项目根目录无效')
  let discovery = createConnection(cwd, [], null, spawnProcess)
  let server
  let activeThreadId = null
  let activeTurnId = null
  try {
    await initialize(discovery)
    const baseline = await discovery.call('config/read', { cwd, includeLayers: false })
    if (!baseline.config?.mcp_servers || typeof baseline.config.mcp_servers !== 'object')
      throw new Error('Codex MCP 配置无法核验')
    const names = Object.keys(baseline.config.mcp_servers)
    if (names.some(name => !/^[A-Za-z0-9_-]+$/.test(name))) throw new Error('Codex MCP 配置无法隔离')
    discovery.close(); discovery = null
    const overrides = names.flatMap(name => ['-c', `mcp_servers.${name}.enabled=false`])
    server = createConnection(cwd, overrides, async params => {
      if (!activeThreadId || !activeTurnId || params?.threadId !== activeThreadId
          || params?.turnId !== activeTurnId || params?.tool !== 'host_action') throw new Error('工具归属无效')
      return onToolCall(params)
    }, spawnProcess)
    await initialize(server)
    const effective = await server.call('config/read', { cwd, includeLayers: false })
    const config = effective.config
    if (!config || config.approval_policy !== 'never' || config.sandbox_mode !== 'read-only'
        || !config.mcp_servers || typeof config.mcp_servers !== 'object'
        || DISABLED_FEATURES.some(name => config.features?.[name] !== false)
        || Object.values(config.mcp_servers).some(value => value?.enabled !== false)) {
      throw new Error('Codex 执行边界校验失败')
    }
    const started = await server.call('thread/start', {
      cwd, ephemeral: true, approvalPolicy: 'never', sandbox: 'read-only', dynamicTools: [TOOL],
    })
    const threadId = started.thread?.id
    if (!threadId || started.thread?.ephemeral !== true || started.approvalPolicy !== 'never'
        || started.sandbox?.type !== 'readOnly' || started.sandbox?.networkAccess !== false)
      throw new Error('Codex 线程执行边界校验失败')
    activeThreadId = threadId
    await checkInventory(server, threadId)
    return {
      threadId,
      async run(prompt) {
        if (server.turnWaiter) throw new Error('Codex 回合正在执行')
        const completion = new Promise(resolve => { server.turnWaiter = { resolve, threadId, turnId: null, events: [] } })
        let startedTurn
        try {
          startedTurn = await server.call('turn/start', {
            threadId, input: [{ type: 'text', text: prompt }], approvalPolicy: 'never',
            sandboxPolicy: { type: 'readOnly', networkAccess: false },
          })
        } catch (error) { server.turnWaiter = null; throw error }
        const turnId = startedTurn.turn?.id
        if (!turnId) { server.turnWaiter = null; throw new Error('Codex 未返回回合标识') }
        activeTurnId = turnId
        if (server.turnWaiter) {
          const waiter = server.turnWaiter
          waiter.turnId = turnId
          const completed = waiter.events.find(event => event.turn?.id === turnId)
          if (completed) { server.turnWaiter = null; waiter.resolve(completed) }
          else waiter.events = []
        }
        return { turnId, completion }
      },
      async interrupt(turnId) { await server.call('turn/interrupt', { threadId, turnId }) },
      close() { server.close() },
    }
  } catch (error) {
    discovery?.close(); server?.close(); throw error
  }
}
