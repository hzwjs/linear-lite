import { spawn } from 'node:child_process'
import { realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import readline from 'node:readline'
import { HostError } from './device.mjs'
import { HOST_ACTION_TOOL, validateHostAction } from './host-actions.mjs'
import { stopProcessGroup } from './process-groups.mjs'
import { taskDescriptionImages } from './task-description-images.mjs'

const DISABLED_FEATURES = Object.freeze([
  'apps', 'plugins', 'hooks', 'computer_use', 'browser_use', 'multi_agent',
  'image_generation', 'in_app_browser', 'workspace_dependencies', 'remote_plugin', 'skill_mcp_dependency_install',
  'shell_tool', 'shell_snapshot', 'skill_search', 'view_image',
])
const BASE_ARGS = Object.freeze([
  'app-server', '--listen', 'stdio://', '-c', 'approval_policy="never"', '-c', 'sandbox_mode="read-only"',
  '-c', 'web_search="disabled"',
  // 原生工具派发依赖本地 Code Mode host；工具来源和文件写入仍由下述边界约束。
  '--enable', 'code_mode_host',
  ...DISABLED_FEATURES.flatMap(value => ['--disable', value]),
])

/** App Server 的标准输入输出为唯一控制通道；不写入协议日志。 */
class RpcConnection {
  constructor(child, onRequest, groups, stopGroup) {
    this.child = child
    this.onRequest = onRequest
    this.groups = groups
    this.stopGroup = stopGroup
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
        const images = message.params.arguments.action === 'claim' && result?.accepted === true
          ? await this.loadTaskImages(result.task?.description) : []
        const payload = images.length ? { ...result, descriptionImages: await this.attachTaskImages(message.params, images) } : result
        this.send({ id: message.id, result: { success: true, contentItems: [{ type: 'inputText', text: JSON.stringify(payload) }] } })
      } catch (error) {
        const text = error instanceof HostError && ['TOOL_INPUT_INVALID', 'ACTION_REJECTED'].includes(error.code)
          ? error.message : '宿主操作遭拒'
        try { this.send({ id: message.id, result: { success: false, contentItems: [{ type: 'inputText', text }] } }) } catch {}
      }
    }
  }
  async attachTaskImages({ threadId, turnId }, images) {
    const downloaded = images.filter(image => image.imageUrl)
    let attached = false
    if (downloaded.length) {
      // Code Mode 将工具回执包装成字符串；图像须经当前回合的原生输入通道。
      try {
        const result = await this.call('turn/steer', {
          threadId, expectedTurnId: turnId,
          input: [
            { type: 'text', text: `Linear Lite 宿主提供的任务描述图片，序号 ${downloaded.map(({ index }) => index).join('、')}。`, text_elements: [] },
            ...downloaded.map(({ imageUrl }) => ({ type: 'image', url: imageUrl })),
          ],
        })
        attached = result?.turnId === turnId
      } catch { /* 领取已完成，图像输入失败由回执报告。 */ }
    }
    return images.map(({ index, error }) => {
      if (error) return { index, error }
      return attached ? { index, included: true } : { index, error: 'Codex 未接收描述图片' }
    })
  }
  failAll() {
    if (this.closed) return
    this.closed = true
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(new Error('Codex 控制通道已结束')) }
    this.pending.clear()
    if (this.turnWaiter) { const waiter = this.turnWaiter; this.turnWaiter = null; waiter.resolve(null) }
  }
  close() {
    if (!this.closing) this.closing = (async () => {
      this.child.stdin.end(); this.failAll()
      await this.stopGroup(this.child.pid)
      await this.groups.stopped()
    })()
    return this.closing
  }
}

async function createConnection(cwd, extra, onRequest, spawnProcess, groups, stopGroup) {
  await groups.starting()
  const child = spawnProcess('codex', [...BASE_ARGS, ...extra],
    { cwd, stdio: ['pipe', 'pipe', 'pipe'], detached: true })
  child.on('error', () => {})
  if (!Number.isSafeInteger(child.pid) || child.pid <= 1) throw new Error('Codex 进程组标识无效')
  try { await groups.started(child.pid) }
  catch (error) { await stopGroup(child.pid).catch(() => {}); throw error }
  return new RpcConnection(child, onRequest, groups, stopGroup)
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

/** 匹配 Codex 项目根目录，避免使用 Linear Lite 的项目标识。 */
export async function resolveCodexProjectId(connection, cwd) {
  const projectDirectory = await realpath(cwd)
  const matches = new Set()
  const cursors = new Set()
  let cursor = null
  do {
    const response = await connection.call('project/list', { cursor, limit: 100 })
    if (!Array.isArray(response?.data)) throw new Error('Codex 项目列表无效')
    for (const project of response.data) {
      if (typeof project?.id !== 'string' || !project.id || !Array.isArray(project.roots))
        throw new Error('Codex 项目列表无效')
      for (const root of project.roots) {
        if (typeof root?.path !== 'string' || !path.isAbsolute(root.path))
          throw new Error('Codex 项目目录无效')
        let resolved
        try { resolved = await realpath(root.path) }
        catch (error) {
          if (error?.code === 'ENOENT' || error?.code === 'ENOTDIR') continue
          throw error
        }
        if (resolved === projectDirectory) matches.add(project.id)
      }
    }
    cursor = response.nextCursor ?? null
    if (cursor !== null && (typeof cursor !== 'string' || !cursor || cursors.has(cursor)))
      throw new Error('Codex 项目分页无效')
    if (cursor !== null) cursors.add(cursor)
  } while (cursor !== null)
  if (matches.size > 1) throw new Error('Codex 项目目录匹配多个项目')
  return matches.size ? [...matches][0] : null
}

/** 仅调用 Codex 线程命名接口，不改变执行回合。 */
export async function setCodexThreadName(connection, threadId, name) {
  if (typeof threadId !== 'string' || !threadId || typeof name !== 'string' || !name.trim())
    throw new Error('Codex 会话名称无效')
  await connection.call('thread/name/set', { threadId, name })
}

/** 读取并逐项覆盖 MCP 配置；结果不满足约束时不启动回合。 */
export async function createAppServer({ cwd, onToolCall, groups, name = 'Linear Lite 协作', loadTaskImages = taskDescriptionImages,
  spawnProcess = spawn, stopGroup = stopProcessGroup }) {
  if (process.platform !== 'darwin') throw new Error('当前宿主平台缺少经过核验的执行边界')
  if (typeof cwd !== 'string' || !path.isAbsolute(cwd) || await realpath(cwd) !== cwd
      || !(await stat(cwd)).isDirectory()) throw new Error('Codex 项目根目录无效')
  if (!groups || typeof groups.starting !== 'function' || typeof groups.started !== 'function'
      || typeof groups.stopped !== 'function') throw new Error('缺少执行进程组记录')
  let discovery
  let server
  let activeThreadId = null
  let activeTurnId = null
  try {
    discovery = await createConnection(cwd, [], null, spawnProcess, groups, stopGroup)
    await initialize(discovery)
    const baseline = await discovery.call('config/read', { cwd, includeLayers: false })
    if (!baseline.config?.mcp_servers || typeof baseline.config.mcp_servers !== 'object')
      throw new Error('Codex MCP 配置无法核验')
    const names = Object.keys(baseline.config.mcp_servers)
    if (names.some(name => !/^[A-Za-z0-9_-]+$/.test(name))) throw new Error('Codex MCP 配置无法隔离')
    await discovery.close(); discovery = null
    const overrides = names.flatMap(name => ['-c', `mcp_servers.${name}.enabled=false`])
    server = await createConnection(cwd, overrides, async params => {
      if (!activeThreadId || !activeTurnId || params?.threadId !== activeThreadId
          || params?.turnId !== activeTurnId || params?.tool !== 'host_action') throw new Error('工具归属无效')
      validateHostAction(params.arguments)
      const result = await onToolCall(params)
      if (params.arguments.action === 'claim' && result?.accepted === true && typeof result.task?.title === 'string') {
        const title = result.task.title.replace(/\s+/g, ' ').trim().slice(0, 100)
        if (title) void setCodexThreadName(server, activeThreadId, `${name} · ${title}`).catch(() => {})
      }
      return result
    }, spawnProcess, groups, stopGroup)
    server.loadTaskImages = loadTaskImages
    await initialize(server)
    const effective = await server.call('config/read', { cwd, includeLayers: false })
    const config = effective.config
    if (!config || config.approval_policy !== 'never' || config.sandbox_mode !== 'read-only' || config.web_search !== 'disabled'
        || config.features?.code_mode_host !== true
        || !config.mcp_servers || typeof config.mcp_servers !== 'object'
        || DISABLED_FEATURES.some(name => config.features?.[name] !== false)
        || Object.values(config.mcp_servers).some(value => value?.enabled !== false)) {
      throw new Error('Codex 执行边界校验失败')
    }
    const projectId = await resolveCodexProjectId(server, cwd)
    // 协作回合的项目访问由 host_action 受管执行器承载。
    const started = await server.call('thread/start', {
      cwd, projectId, environments: [], ephemeral: false, approvalPolicy: 'never', sandbox: 'read-only', dynamicTools: [HOST_ACTION_TOOL],
    })
    const threadId = started.thread?.id
    if (!threadId || started.thread?.ephemeral !== false || started.approvalPolicy !== 'never'
        || started.sandbox?.type !== 'readOnly' || started.sandbox?.networkAccess !== false)
      throw new Error('Codex 线程执行边界校验失败')
    activeThreadId = threadId
    await checkInventory(server, threadId)
    await setCodexThreadName(server, threadId, name)
    return {
      threadId,
      async run(prompt) {
        if (server.turnWaiter) throw new Error('Codex 回合正在执行')
        const completion = new Promise(resolve => { server.turnWaiter = { resolve, threadId, turnId: null, events: [] } })
        let startedTurn
        try {
          startedTurn = await server.call('turn/start', {
            threadId, environments: [], input: [{ type: 'text', text: prompt }], approvalPolicy: 'never',
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
      close() { return server.close() },
    }
  } catch (error) {
    await discovery?.close().catch(() => {})
    await server?.close().catch(() => {})
    throw error
  }
}
