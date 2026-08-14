import { mkdir } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'
import { resolve, join } from 'node:path'
import { extractFinalAssistantText, toProgressEvent } from './event-stream.mjs'
import { createConfigServer } from './config-server.mjs'
import { ProjectConfigStore } from './workspace-config.mjs'
import { BridgeSettingsStore } from './bridge-settings.mjs'

const config = {
  piBinary: process.env.PI_BINARY ?? 'pi',
  sessionRoot: resolve(process.env.PI_BRIDGE_SESSION_ROOT ?? './.pi-sessions'),
  projectConfigFile: resolve(process.env.PI_BRIDGE_CONFIG_FILE ?? './.pi-bridge/projects.json'),
  settingsFile: resolve(process.env.PI_BRIDGE_SETTINGS_FILE ?? './.pi-bridge/settings.json'),
  configHost: process.env.PI_BRIDGE_CONFIG_HOST ?? '127.0.0.1',
  configPort: Number(process.env.PI_BRIDGE_CONFIG_PORT ?? 9780),
  pollMs: Number(process.env.PI_BRIDGE_POLL_MS ?? 2000),
  cancelPollMs: Number(process.env.PI_BRIDGE_CANCEL_POLL_MS ?? 2000),
  apiTimeoutMs: Number(process.env.PI_BRIDGE_API_TIMEOUT_MS ?? 10000),
  apiRetryDelayMs: Number(process.env.PI_BRIDGE_API_RETRY_DELAY_MS ?? 1000),
  heartbeatMs: Number(process.env.PI_BRIDGE_HEARTBEAT_MS ?? 30000),
}

export const projectStore = new ProjectConfigStore(config.projectConfigFile)
export const settingsStore = new BridgeSettingsStore(config.settingsFile)

const RETRYABLE_ERROR = Symbol('retryableBridgeError')

function sleep(ms) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, ms))
}

function retryableError(error) {
  return Boolean(error?.[RETRYABLE_ERROR])
}

function markRetryable(error) {
  error[RETRYABLE_ERROR] = true
  return error
}

/**
 * 所有出站请求都必须有超时；网络错误和 5xx 只重试当前请求，4xx 直接暴露配置或权限问题。
 * 这样断网时 Bridge 保持执行上下文，网络恢复后原请求会自动继续，而不是把 Job 标记为失败。
 */
export function createApiClient({
  settingsStore: store = settingsStore,
  fetchImpl = globalThis.fetch,
  requestTimeoutMs = config.apiTimeoutMs,
  retryDelayMs = config.apiRetryDelayMs,
  sleepImpl = sleep,
} = {}) {
  return async function api(path, options = {}) {
    const settings = await store.read()
    if (!settings.agentToken) throw new Error('Bridge 尚未配置 Linear Lite 连接')
    let retryAttempt = 0
    while (true) {
      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), requestTimeoutMs)
      try {
        const response = await fetchImpl(`${settings.apiBaseUrl}${path}`, {
          ...options,
          headers: {
            'Content-Type': 'application/json',
            'X-Agent-Token': settings.agentToken,
            ...(options.headers ?? {}),
          },
          signal: controller.signal,
        })
        const body = await response.json().catch(() => null)
        if (!response.ok || body?.code >= 400) {
          const error = new Error(body?.message ?? `Linear Lite API ${response.status}`)
          if (response.status >= 500) markRetryable(error)
          throw error
        }
        return body?.data
      } catch (error) {
        if (!retryableError(error)) {
          if (error?.name === 'AbortError' || error instanceof TypeError) markRetryable(error)
          else throw error
        }
        retryAttempt += 1
        const delay = Math.min(retryDelayMs * (2 ** Math.min(retryAttempt - 1, 5)), 30000)
        await sleepImpl(delay)
      } finally {
        clearTimeout(timeoutId)
      }
    }
  }
}

export const api = createApiClient()

/** 串行上传队列在单批次失败后必须继续消费后续批次，避免一个断网请求永久毒化整条队列。 */
export function createRecoveringSerialQueue() {
  let tail = Promise.resolve()
  return {
    enqueue(operation) {
      const result = tail.catch(() => {}).then(operation)
      tail = result
      return result
    },
  }
}

function safeSegment(value, label) {
  if (!value || !/^[A-Za-z0-9._-]+$/.test(value)) {
    throw new Error(`${label} contains unsafe path characters`)
  }
  return value
}

export async function resolveTaskDirectory(job, { configStore = projectStore } = {}) {
  // projectId 是任务到本地目录的唯一稳定键，Pi 直接在绑定目录中执行。
  return configStore.resolveDirectory(job.projectId)
}

export function buildPiArgs(job, sessionDir) {
  return [
    '--mode', 'rpc',
    '--session-dir', sessionDir,
    '--session-id', safeSegment(job.executionId, 'executionId'),
  ]
}

async function startPi(job, cwd) {
  const sessionDir = join(config.sessionRoot, safeSegment(job.executionId, 'executionId'))
  await mkdir(sessionDir, { recursive: true })
  // 会话文件是 Bridge 的本地状态；Linear Lite 只传递不透明的 executionId。
  // 同一 executionId 的评论续接复用同一个 session-dir，不同任务使用不同 executionId。
  // 不禁用用户的 skills、extensions 与上下文文件，确保 Bridge 与用户终端启动的是同一种 Pi。
  const args = buildPiArgs(job, sessionDir)
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
        const timer = setTimeout(() => {
          listeners.delete(listener)
          child.removeListener('close', onClose)
          reject(new Error('Pi RPC response timeout'))
        }, timeoutMs)
        const onClose = () => {
          clearTimeout(timer)
          listeners.delete(listener)
          reject(new Error('Pi 子进程已退出'))
        }
        const listener = (message) => {
          if (predicate(message)) {
            clearTimeout(timer)
            listeners.delete(listener)
            child.removeListener('close', onClose)
            resolvePromise(message)
          }
        }
        child.once('close', onClose)
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
    cwd = await resolveTaskDirectory(job)
  } catch (error) {
    // projectId 未配置或本地目录失效时，必须把确定原因写回当前 Job。
    await reportJobFailure(job, error)
    return
  }
  const child = await startPi(job, cwd)
  const rpc = readRpc(child)
  let canceledByServer = false
  let heartbeatInFlight = false
  const renewLease = async () => {
    if (heartbeatInFlight || canceledByServer) return
    heartbeatInFlight = true
    try {
      await api(`/api/agent/jobs/${job.jobId}/heartbeat`, {
        method: 'POST',
        body: JSON.stringify({ executionId: job.executionId }),
      })
    } catch (error) {
      console.error(`[pi-bridge] heartbeat failed: ${error.message}`)
    } finally {
      heartbeatInFlight = false
    }
  }
  const heartbeat = setInterval(() => {
    void renewLease()
  }, config.heartbeatMs)
  // 领取成功后立即续租，给初始化 Pi 和断网恢复留出完整租约窗口。
  void renewLease()
  let stderr = ''
  let progressTimer = null
  let progressSequence = 1
  let pendingProgress = []
  const progressQueue = createRecoveringSerialQueue()

  const flushProgress = () => {
    if (progressTimer != null) {
      clearTimeout(progressTimer)
      progressTimer = null
    }
    const batch = pendingProgress
    pendingProgress = []
    if (!batch.length) return Promise.resolve()
    // 进度批次串行发送，保证服务端收到的 sequence_no 与 Pi 事件顺序一致。
    return progressQueue.enqueue(() => api(`/api/agent/jobs/${job.jobId}/events`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId, events: batch }),
    }))
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
