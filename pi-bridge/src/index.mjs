import { mkdir, access } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'
import { resolve, join, relative, isAbsolute } from 'node:path'
import { extractFinalAssistantText, toProgressEvent } from './event-stream.mjs'
import { createConfigServer } from './config-server.mjs'
import { ProjectConfigStore, validateProjectId } from './workspace-config.mjs'
import { BridgeSettingsStore } from './bridge-settings.mjs'

const config = {
  piBinary: process.env.PI_BINARY ?? 'pi',
  workspaceRoot: resolve(process.env.PI_BRIDGE_WORKSPACE_ROOT ?? './.pi-workspaces'),
  sessionRoot: resolve(process.env.PI_BRIDGE_SESSION_ROOT ?? './.pi-sessions'),
  projectConfigFile: resolve(process.env.PI_BRIDGE_CONFIG_FILE ?? './.pi-bridge/projects.json'),
  settingsFile: resolve(process.env.PI_BRIDGE_SETTINGS_FILE ?? './.pi-bridge/settings.json'),
  configHost: process.env.PI_BRIDGE_CONFIG_HOST ?? '127.0.0.1',
  configPort: Number(process.env.PI_BRIDGE_CONFIG_PORT ?? 9780),
  pollMs: Number(process.env.PI_BRIDGE_POLL_MS ?? 2000),
  cancelPollMs: Number(process.env.PI_BRIDGE_CANCEL_POLL_MS ?? 2000),
}

export const projectStore = new ProjectConfigStore(config.projectConfigFile)
export const settingsStore = new BridgeSettingsStore(config.settingsFile)

async function api(path, options = {}) {
  const settings = await settingsStore.read()
  if (!settings.agentToken) throw new Error('Bridge 尚未配置 Linear Lite 连接')
  const response = await fetch(`${settings.apiBaseUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Agent-Token': settings.agentToken,
      ...(options.headers ?? {}),
    },
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || body?.code >= 400) {
    throw new Error(body?.message ?? `Linear Lite API ${response.status}`)
  }
  return body?.data
}

function safeSegment(value, label) {
  if (!value || !/^[A-Za-z0-9._-]+$/.test(value)) {
    throw new Error(`${label} contains unsafe path characters`)
  }
  return value
}

export async function ensureWorktree(job, { configStore = projectStore, workspaceRoot = config.workspaceRoot } = {}) {
  // projectId 是任务到本地仓库的唯一稳定键，项目名称不参与路径或仓库解析。
  const projectId = validateProjectId(job.projectId)
  const repository = await configStore.resolveRepository(projectId)
  const projectSegment = safeSegment(String(projectId), 'projectId')
  const path = join(workspaceRoot, projectSegment, safeSegment(job.taskKey, 'taskKey'))
  const rootRelative = relative(workspaceRoot, path)
  if (rootRelative.startsWith('..') || isAbsolute(rootRelative)) throw new Error('worktree path escapes workspace root')
  await mkdir(join(workspaceRoot, projectSegment), { recursive: true })
  try {
    await access(path)
  } catch {
    await runCommand('git', ['-C', repository, 'worktree', 'add', '--detach', path, 'HEAD'])
    return path
  }
  const commonGitDir = await runCommand('git', [
    '-C', path,
    'rev-parse', '--path-format=absolute', '--git-common-dir',
  ])
  if (commonGitDir !== join(repository, '.git')) {
    throw new Error(`任务 worktree 与 projectId 对应仓库不一致：${path}`)
  }
  return path
}

function runCommand(command, args) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
    let stdout = ''
    child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
    child.on('error', reject)
    child.on('close', (code) => code === 0
      ? resolvePromise(stdout.trim())
      : reject(new Error(stderr || `${command} exited ${code}`)))
  })
}

async function startPi(job, cwd) {
  const sessionDir = join(config.sessionRoot, safeSegment(job.executionId, 'executionId'))
  await mkdir(sessionDir, { recursive: true })
  // 会话文件是 Bridge 的本地状态；Linear Lite 只传递不透明的 executionId。
  // 同一 executionId 的评论续接复用同一个 session-dir，不同任务使用不同 executionId。
  const args = [
    '--mode', 'rpc',
    '--session-dir', sessionDir,
    '--session-id', safeSegment(job.executionId, 'executionId'),
    '--no-extensions', '--no-skills', '--no-context-files',
  ]
  return spawn(config.piBinary, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] })
}

function sendRpc(child, command) {
  // Pi RPC requires one JSON object per LF-delimited record; do not use escaped "\\n".
  child.stdin.write(`${JSON.stringify(command)}\n`)
}

function readRpc(child) {
  let buffer = ''
  const listeners = new Set()
  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString()
    let lf = buffer.indexOf('\n')
    while (lf >= 0) {
      const line = buffer.slice(0, lf).replace(/\r$/, '')
      buffer = buffer.slice(lf + 1)
      if (line.trim()) {
        try { listeners.forEach((listener) => listener(JSON.parse(line))) } catch { /* Pi may print non-JSON diagnostics on stderr only. */ }
      }
      lf = buffer.indexOf('\n')
    }
  })
  return {
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    waitFor(predicate, timeoutMs = 30000) {
      return new Promise((resolvePromise, reject) => {
        const timer = setTimeout(() => { listeners.delete(listener); reject(new Error('Pi RPC response timeout')) }, timeoutMs)
        const listener = (message) => {
          if (predicate(message)) { clearTimeout(timer); listeners.delete(listener); resolvePromise(message) }
        }
        listeners.add(listener)
      })
    },
  }
}

async function reportJobFailure(job, error) {
  console.error(`[pi-bridge] ${error.message}`)
  await api(`/api/agent/jobs/${job.jobId}/failed`, {
    method: 'POST',
    body: JSON.stringify({ executionId: job.executionId, errorMessage: error.message }),
  }).catch(() => {})
}

export async function execute(job) {
  let cwd
  try {
    cwd = await ensureWorktree(job)
  } catch (error) {
    // projectId 未配置或本地仓库失效时，必须把确定原因写回当前 Job。
    await reportJobFailure(job, error)
    return
  }
  const child = await startPi(job, cwd)
  const rpc = readRpc(child)
  const heartbeat = setInterval(() => {
    api(`/api/agent/jobs/${job.jobId}/heartbeat`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId }),
    }).catch(() => {})
  }, 60_000)
  let stderr = ''
  let progressTimer = null
  let progressSequence = 1
  let pendingProgress = []
  let progressFlush = Promise.resolve()

  const flushProgress = () => {
    if (progressTimer != null) {
      clearTimeout(progressTimer)
      progressTimer = null
    }
    const batch = pendingProgress
    pendingProgress = []
    if (!batch.length) return progressFlush
    // 进度批次串行发送，保证服务端收到的 sequence_no 与 Pi 事件顺序一致。
    progressFlush = progressFlush.then(() => api(`/api/agent/jobs/${job.jobId}/events`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId, events: batch }),
    }))
    return progressFlush
  }

  const queueProgress = (event) => {
    if (canceledByServer) return
    pendingProgress.push({ sequenceNo: progressSequence++, ...event })
    if (pendingProgress.length >= 20) {
      void flushProgress().catch((error) => {
        if (!canceledByServer) console.error(`[pi-bridge] event upload failed: ${error.message}`)
      })
      return
    }
    if (progressTimer == null) {
      progressTimer = setTimeout(() => {
        void flushProgress().catch((error) => {
          if (!canceledByServer) console.error(`[pi-bridge] event upload failed: ${error.message}`)
        })
      }, 500)
    }
  }

  const unsubscribe = rpc.subscribe((message) => {
    const event = toProgressEvent(message)
    if (event) queueProgress(event)
  })
  let canceledByServer = false
  let cancelReject
  let cancelCheckInFlight = false
  const cancellation = new Promise((_, reject) => { cancelReject = reject })
  const stopForCancellation = () => {
    if (canceledByServer) return
    canceledByServer = true
    // 取消接口只更新服务端状态，Bridge 负责立即终止本地 Pi 子进程。
    if (child.exitCode == null && child.signalCode == null) child.kill('SIGTERM')
    cancelReject(new Error('Pi 执行已取消'))
  }
  const checkCancellation = async () => {
    if (cancelCheckInFlight || canceledByServer) return
    cancelCheckInFlight = true
    try {
      const status = await api(`/api/agent/jobs/${job.jobId}/status?executionId=${encodeURIComponent(job.executionId)}`)
      if (status?.executionId === job.executionId && status.jobStatus === 'canceled') {
        stopForCancellation()
      }
    } catch (error) {
      // 状态检查的瞬时失败不能中断正在执行的任务，下一轮继续检查。
      console.error(`[pi-bridge] cancellation check failed: ${error.message}`)
    } finally {
      cancelCheckInFlight = false
    }
  }
  const cancellationTimer = setInterval(() => { void checkCancellation() }, config.cancelPollMs)
  void checkCancellation()
  child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
  try {
    sendRpc(child, { id: 'state', type: 'get_state' })
    const state = await rpc.waitFor((message) => message.id === 'state' && message.type === 'response')
    if (!state.success || !state.data?.sessionId) {
      throw new Error('Pi get_state did not return a sessionId')
    }
    await api(`/api/agent/sessions/${encodeURIComponent(job.executionId)}/state`, {
      method: 'POST',
      body: JSON.stringify({ sessionId: state.data.sessionId }),
    })
    sendRpc(child, { id: 'prompt', type: 'prompt', message: job.prompt })
    const done = rpc.waitFor((message) => {
      return message.type === 'agent_end' && message.willRetry !== true
    }, 30 * 60 * 1000)
    const finalEvent = await Promise.race([done, cancellation])
    await flushProgress()
    const finalText = extractFinalAssistantText(finalEvent)
    if (!finalText) throw new Error('Pi 未返回最终 assistant 结果')
    await api(`/api/agent/jobs/${job.jobId}/succeeded`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId, result: finalText }),
    })
  } catch (error) {
    if (stderr.trim()) console.error(`[pi-bridge] Pi stderr: ${stderr.trim()}`)
    if (!canceledByServer) {
      await api(`/api/agent/jobs/${job.jobId}/failed`, {
        method: 'POST',
        body: JSON.stringify({ executionId: job.executionId, errorMessage: error.message }),
      }).catch(() => {})
    }
  } finally {
    unsubscribe()
    if (progressTimer != null) clearTimeout(progressTimer)
    clearInterval(cancellationTimer)
    clearInterval(heartbeat)
    if (child.exitCode == null && child.signalCode == null) child.kill('SIGTERM')
  }
}

export async function main() {
  while (true) {
    try {
      const job = await api('/api/agent/jobs/claim', { method: 'POST', body: '{}' })
      if (job) await execute(job)
      else await new Promise((resolvePromise) => setTimeout(resolvePromise, config.pollMs))
    } catch (error) {
      console.error(`[pi-bridge] ${error.message}`)
      await new Promise((resolvePromise) => setTimeout(resolvePromise, config.pollMs))
    }
  }
}

export async function startBridge() {
  await mkdir(config.workspaceRoot, { recursive: true })
  await mkdir(config.sessionRoot, { recursive: true })
  let pollingStarted = false
  const startPolling = () => {
    if (pollingStarted) return
    pollingStarted = true
    void main()
  }
  const configServer = createConfigServer({
    store: projectStore,
    settingsStore,
    projectProvider: () => api('/api/agent/projects'),
    onSettingsSaved: startPolling,
    host: config.configHost,
    port: config.configPort,
  })
  const address = await configServer.listen()
  console.log(`[pi-bridge] 本地工作区配置页：http://${config.configHost}:${address.port}/`)
  const settings = await settingsStore.read()
  if (!settings.agentToken) {
    console.error('[pi-bridge] 未配置 Linear Lite 连接，任务执行轮询未启动')
    return configServer
  }
  startPolling()
  return configServer
}

const isMainModule = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMainModule) {
  startBridge().catch((error) => {
    console.error(`[pi-bridge] ${error.message}`)
    process.exitCode = 1
  })
}
