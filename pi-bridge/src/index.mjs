import { mkdir } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'
import { homedir } from 'node:os'
import { resolve, join } from 'node:path'
import {
  RuntimeDisplayBlockAssembler,
  createSessionSnapshot,
  finalAssistantText,
} from './event-stream.mjs'
import { createConfigServer } from './config-server.mjs'
import { ProjectConfigStore } from './workspace-config.mjs'
import { BridgeSettingsStore } from './bridge-settings.mjs'

const config = {
  piBinary: process.env.PI_BINARY ?? 'pi',
  dataRoot: resolve(process.env.PI_BRIDGE_DATA_ROOT ?? join(homedir(), 'Library', 'Application Support', 'Linear Lite', 'Pi Bridge', 'data')),
  // Pi 的 Resume Session 只扫描自己的 agent sessions 根目录；Bridge 必须写入同一棵目录树。
  agentDir: resolve(process.env.PI_CODING_AGENT_DIR ?? join(homedir(), '.pi', 'agent')),
  sessionRoot: resolve(join(process.env.PI_CODING_AGENT_DIR ?? join(homedir(), '.pi', 'agent'), 'sessions')),
  projectConfigFile: resolve(process.env.PI_BRIDGE_CONFIG_FILE ?? join(homedir(), 'Library', 'Application Support', 'Linear Lite', 'Pi Bridge', 'data', 'projects.json')),
  settingsFile: resolve(process.env.PI_BRIDGE_SETTINGS_FILE ?? join(homedir(), 'Library', 'Application Support', 'Linear Lite', 'Pi Bridge', 'data', 'settings.json')),
  configHost: process.env.PI_BRIDGE_CONFIG_HOST ?? '127.0.0.1',
  configPort: Number(process.env.PI_BRIDGE_CONFIG_PORT ?? 9780),
  pollMs: Number(process.env.PI_BRIDGE_POLL_MS ?? 2000),
  cancelPollMs: Number(process.env.PI_BRIDGE_CANCEL_POLL_MS ?? 2000),
  apiTimeoutMs: Number(process.env.PI_BRIDGE_API_TIMEOUT_MS ?? 10000),
  apiRetryDelayMs: Number(process.env.PI_BRIDGE_API_RETRY_DELAY_MS ?? 1000),
  heartbeatMs: Number(process.env.PI_BRIDGE_HEARTBEAT_MS ?? 30000),
  version: process.env.PI_BRIDGE_VERSION ?? 'dev',
}

export const projectStore = new ProjectConfigStore(config.projectConfigFile)
export const settingsStore = new BridgeSettingsStore(config.settingsFile)
export const attachmentTokenStore = { value: '' }

export function setExecutionAttachmentToken(token) {
  attachmentTokenStore.value = typeof token === 'string' ? token : ''
}

const RETRYABLE_ERROR = Symbol('retryableBridgeError')

const runtime = {
  status: 'starting',
  backendReachable: false,
  startedAt: new Date().toISOString(),
  lastConnectedAt: null,
}

function setRuntimeStatus(status, backendReachable = runtime.backendReachable) {
  runtime.status = status
  runtime.backendReachable = backendReachable
  if (status === 'online') runtime.lastConnectedAt = new Date().toISOString()
}

export function getBridgeHealth() {
  return {
    status: runtime.status,
    configured: runtime.status !== 'config_required',
    backendReachable: runtime.backendReachable,
    version: config.version,
    startedAt: runtime.startedAt,
    lastConnectedAt: runtime.lastConnectedAt,
  }
}

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
  attachmentStore = attachmentTokenStore,
  fetchImpl = globalThis.fetch,
  requestTimeoutMs = config.apiTimeoutMs,
  retryDelayMs = config.apiRetryDelayMs,
  sleepImpl = sleep,
  onOnline = () => {},
  onDegraded = () => {},
  onConfigurationRequired = () => {},
} = {}) {
  return async function api(path, options = {}) {
    let retryAttempt = 0
    while (true) {
      const settings = await store.read()
      if (!attachmentStore.value) {
        throw new Error('等待任务面板建立本机执行连接')
      }
      const controller = new AbortController()
      const externalSignal = options.signal
      const abortFromExternalSignal = () => controller.abort()
      if (externalSignal?.aborted) controller.abort()
      else externalSignal?.addEventListener('abort', abortFromExternalSignal, { once: true })
      const timeoutId = setTimeout(() => controller.abort(), requestTimeoutMs)
      try {
        const response = await fetchImpl(`${settings.apiBaseUrl}${path}`, {
          ...options,
          headers: {
            'Content-Type': 'application/json',
            'X-Execution-Attachment': attachmentStore.value,
            ...(options.headers ?? {}),
          },
          signal: controller.signal,
        })
        const body = await response.json().catch(() => null)
        if (!response.ok || body?.code >= 400) {
          const error = new Error(body?.message ?? `Linear Lite API ${response.status}`)
          error.status = response.status
          if (response.status >= 500) markRetryable(error)
          if (response.status === 401 || response.status === 403) onConfigurationRequired()
          throw error
        }
        onOnline()
        return body?.data
      } catch (error) {
        if (externalSignal?.aborted) throw error
        if (!retryableError(error)) {
          if (error?.name === 'AbortError' || error instanceof TypeError) markRetryable(error)
          else throw error
        }
        onDegraded()
        retryAttempt += 1
        const delay = Math.min(retryDelayMs * (2 ** Math.min(retryAttempt - 1, 5)), 30000)
        await sleepImpl(delay)
      } finally {
        clearTimeout(timeoutId)
        externalSignal?.removeEventListener('abort', abortFromExternalSignal)
      }
    }
  }
}

export const api = createApiClient({
  onOnline: () => setRuntimeStatus('online', true),
  onDegraded: () => setRuntimeStatus('degraded', false),
  onConfigurationRequired: () => {
    // 服务端重启或执行绑定过期后，丢弃旧 token；否则轮询会永久携带失效绑定，页面也无法触发自动重连。
    setExecutionAttachmentToken('')
    setRuntimeStatus('config_required', false)
  },
})

async function attachExecution(attachmentCode) {
  const settings = await settingsStore.read()
  const response = await fetch(`${settings.apiBaseUrl}/api/bridge/executions/attach`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ attachmentCode }),
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || body?.code >= 400 || !body?.data?.attachmentToken) {
    throw new Error(body?.message ?? '本机执行连接建立失败')
  }
  setExecutionAttachmentToken(body.data.attachmentToken)
  setRuntimeStatus('connecting', false)
  return body.data
}

function waitFor(ms, signal) {
  return new Promise((resolvePromise, reject) => {
    if (signal?.aborted) {
      reject(new Error('Bridge stopping'))
      return
    }
    const timer = setTimeout(done, ms)
    const onAbort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', onAbort)
      reject(new Error('Bridge stopping'))
    }
    function done() {
      signal?.removeEventListener('abort', onAbort)
      resolvePromise()
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

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
    '--session-id', safeSegment(job.piSessionId, 'piSessionId'),
  ]
}

export function getPiSessionDirectory(cwd, agentDir = config.agentDir) {
  const resolvedCwd = resolve(cwd)
  const projectDirectory = `--${resolvedCwd.replace(/^[/\\]/, '').replace(/[/\\:]/g, '-')}--`
  return join(resolve(agentDir), 'sessions', projectDirectory)
}

async function startPi(job, cwd) {
  const sessionDir = getPiSessionDirectory(cwd)
  await mkdir(sessionDir, { recursive: true })
  // 会话文件与交互式 Pi 共用项目目录；同一 executionId 通过 --session-id 续接。
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

let rpcRequestSequence = 0

export async function readSessionSnapshot(rpc, executionId, piSessionId) {
  const requestId = `entries-${++rpcRequestSequence}`
  sendRpc(rpc.child, { id: requestId, type: 'get_entries' })
  const response = await rpc.reader.waitFor(
    (message) => message.id === requestId && message.type === 'response',
  )
  if (!response.success || !response.data) {
    throw new Error(response.error ?? 'Pi get_entries 读取失败')
  }
  return createSessionSnapshot({
    executionId,
    piSessionId,
    entries: response.data.entries,
    leafId: response.data.leafId,
  })
}

function rpcRuntime(child) {
  return { child, reader: readRpc(child) }
}

async function verifyPiSession(rpc, piSessionId) {
  const requestId = `state-${++rpcRequestSequence}`
  sendRpc(rpc.child, { id: requestId, type: 'get_state' })
  const response = await rpc.reader.waitFor(
    (message) => message.id === requestId && message.type === 'response',
  )
  if (!response.success || response.data?.sessionId !== piSessionId) {
    throw new Error(`Pi session 读取错位: ${piSessionId}`)
  }
  return response.data
}

async function completeSnapshotRequest(request, snapshot, signal) {
  await api(`/api/bridge/session-snapshot-requests/${encodeURIComponent(request.requestId)}/complete`, {
    method: 'POST',
    body: JSON.stringify(snapshot),
    signal,
  })
}

async function failSnapshotRequest(request, error, signal) {
  await api(`/api/bridge/session-snapshot-requests/${encodeURIComponent(request.requestId)}/fail`, {
    method: 'POST',
    body: JSON.stringify({ executionId: request.executionId, errorMessage: error.message }),
    signal,
  }).catch(() => {})
}

/** 空闲时启动同 session 的只读 RPC；执行中则由调用方传入当前 runtime 复用。 */
export async function processSnapshotRequest(request, { currentRpc = null, signal } = {}) {
  let ownedChild = null
  try {
    let rpc = currentRpc
    if (!rpc) {
      const cwd = await resolveTaskDirectory(request)
      ownedChild = await startPi(request, cwd)
      rpc = rpcRuntime(ownedChild)
      await verifyPiSession(rpc, request.piSessionId)
    }
    const snapshot = await readSessionSnapshot(rpc, request.executionId, request.piSessionId)
    await completeSnapshotRequest(request, snapshot, signal)
    return snapshot
  } catch (error) {
    await failSnapshotRequest(request, error, signal)
    throw error
  } finally {
    if (ownedChild && ownedChild.exitCode == null && ownedChild.signalCode == null) ownedChild.kill('SIGTERM')
  }
}

async function reportJobFailure(job, error, signal) {
  console.error(`[pi-bridge] ${error.message}`)
    await api(`/api/bridge/jobs/${job.jobId}/failed`, {
    method: 'POST',
    body: JSON.stringify({ executionId: job.executionId, errorMessage: error.message }),
    signal,
  }).catch(() => {})
}

export async function execute(job, { signal } = {}) {
  let cwd
  try {
    cwd = await resolveTaskDirectory(job)
  } catch (error) {
    // projectId 未配置或本地目录失效时，必须把确定原因写回当前 Job。
    if (!signal?.aborted) await reportJobFailure(job, error, signal)
    return
  }
  const child = await startPi(job, cwd)
  const piRuntime = rpcRuntime(child)
  const rpc = piRuntime.reader
  let canceledByServer = false
  let heartbeatInFlight = false
  const renewLease = async () => {
    if (heartbeatInFlight || canceledByServer) return
    heartbeatInFlight = true
    try {
      await api(`/api/bridge/jobs/${job.jobId}/heartbeat`, {
        method: 'POST',
        body: JSON.stringify({ executionId: job.executionId }),
        signal,
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
  let runtimeBlockTimer = null
  let pendingRuntimeBlocks = []
  const runtimeBlockQueue = createRecoveringSerialQueue()
  const runtimeAssembler = new RuntimeDisplayBlockAssembler()

  const flushRuntimeBlocks = () => {
    if (runtimeBlockTimer != null) {
      clearTimeout(runtimeBlockTimer)
      runtimeBlockTimer = null
    }
    const batch = pendingRuntimeBlocks
    pendingRuntimeBlocks = []
    if (!batch.length) return Promise.resolve()
    // 临时块批次串行发送，保证同一 blockId 的 revision 按 Pi 产生顺序到达。
    return runtimeBlockQueue.enqueue(() => api(`/api/bridge/jobs/${job.jobId}/runtime-display-blocks`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId, blocks: batch }),
      signal,
    }))
  }

  const queueRuntimeBlock = (block) => {
    if (canceledByServer) return
    pendingRuntimeBlocks.push(block)
    if (pendingRuntimeBlocks.length >= 20) {
      void flushRuntimeBlocks().catch((error) => {
        if (!canceledByServer) console.error(`[pi-bridge] runtime block upload failed: ${error.message}`)
      })
      return
    }
    if (runtimeBlockTimer == null) {
      runtimeBlockTimer = setTimeout(() => {
        void flushRuntimeBlocks().catch((error) => {
          if (!canceledByServer) console.error(`[pi-bridge] runtime block upload failed: ${error.message}`)
        })
      }, 500)
    }
  }

  const unsubscribe = rpc.subscribe((message) => {
    for (const block of runtimeAssembler.accept(message)) queueRuntimeBlock(block)
  })
  let cancelReject
  let cancelCheckInFlight = false
  let shutdownListener
  const cancellation = new Promise((_, reject) => { cancelReject = reject })
  // 取消轮询可能早于 RPC 初始化完成，预先挂载拒绝处理，避免 Node 将其视为未处理异常。
  cancellation.catch(() => {})
  const stopForCancellation = () => {
    if (canceledByServer) return
    canceledByServer = true
    // 取消接口只更新服务端状态，Bridge 负责立即终止本地 Pi 子进程。
    if (child.exitCode == null && child.signalCode == null) child.kill('SIGTERM')
    cancelReject(new Error('Pi 执行已取消'))
  }
  const stopForBridgeShutdown = () => {
    if (child.exitCode == null && child.signalCode == null) child.kill('SIGTERM')
  }
  signal?.addEventListener('abort', stopForBridgeShutdown, { once: true })
  const checkCancellation = async () => {
    if (cancelCheckInFlight || canceledByServer) return
    cancelCheckInFlight = true
    try {
      const status = await api(`/api/bridge/jobs/${job.jobId}/status?executionId=${encodeURIComponent(job.executionId)}`, { signal })
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
  let snapshotPollTimer = null
  let snapshotPollPromise = null
  const pollSnapshotRequest = () => {
    if (snapshotPollPromise || canceledByServer) return
    snapshotPollPromise = (async () => {
      const request = await api('/api/bridge/session-snapshot-requests/claim', {
        method: 'POST', body: '{}', signal,
      })
      if (!request) return
      await processSnapshotRequest(request, { currentRpc: piRuntime, signal })
    })().catch((error) => {
      if (!signal?.aborted && !canceledByServer) {
        console.error(`[pi-bridge] session snapshot request failed: ${error.message}`)
      }
    }).finally(() => { snapshotPollPromise = null })
  }
  try {
    const state = await verifyPiSession(piRuntime, job.piSessionId)
    await api(`/api/bridge/sessions/${encodeURIComponent(job.executionId)}/state`, {
      method: 'POST',
      body: JSON.stringify({ sessionId: state.sessionId }),
      signal,
    })
    snapshotPollTimer = setInterval(pollSnapshotRequest, config.pollMs)
    pollSnapshotRequest()
    sendRpc(child, { id: 'prompt', type: 'prompt', message: job.prompt })
    const done = rpc.waitFor((message) => {
      return message.type === 'agent_end' && message.willRetry !== true
    }, 30 * 60 * 1000)
    const shutdown = new Promise((_, reject) => {
      if (signal?.aborted) reject(new Error('Bridge stopping'))
      else {
        shutdownListener = () => reject(new Error('Bridge stopping'))
        signal?.addEventListener('abort', shutdownListener, { once: true })
      }
    })
    await Promise.race([done, cancellation, shutdown])
    if (snapshotPollTimer != null) clearInterval(snapshotPollTimer)
    snapshotPollTimer = null
    await snapshotPollPromise
    await flushRuntimeBlocks()
    // agent_end 后的 get_entries 是本轮唯一权威终值，发送后前端整体替换基线并清除临时层。
    const snapshot = await readSessionSnapshot(piRuntime, job.executionId, job.piSessionId)
    await api(`/api/bridge/sessions/${encodeURIComponent(job.executionId)}/snapshot`, {
      method: 'POST',
      body: JSON.stringify(snapshot),
      signal,
    })
    const finalText = finalAssistantText(snapshot)
    if (!finalText) throw new Error('Pi 未返回最终 assistant 结果')
    await api(`/api/bridge/jobs/${job.jobId}/succeeded`, {
      method: 'POST',
      body: JSON.stringify({ executionId: job.executionId, result: finalText }),
      signal,
    })
  } catch (error) {
    if (stderr.trim()) console.error(`[pi-bridge] Pi stderr: ${stderr.trim()}`)
    // 失败时只封口 runtime 临时块，不将错误伪造为 session 历史。
    pendingRuntimeBlocks.push(...runtimeAssembler.sealOpenBlocksAsError())
    await flushRuntimeBlocks().catch((uploadError) => {
      if (!signal?.aborted) console.error(`[pi-bridge] terminal block upload failed: ${uploadError.message}`)
    })
    if (!canceledByServer && !signal?.aborted) {
      await api(`/api/bridge/jobs/${job.jobId}/failed`, {
        method: 'POST',
        body: JSON.stringify({ executionId: job.executionId, errorMessage: error.message }),
        signal,
      }).catch(() => {})
    }
  } finally {
    unsubscribe()
    if (runtimeBlockTimer != null) clearTimeout(runtimeBlockTimer)
    if (snapshotPollTimer != null) clearInterval(snapshotPollTimer)
    clearInterval(cancellationTimer)
    clearInterval(heartbeat)
    signal?.removeEventListener('abort', stopForBridgeShutdown)
    if (shutdownListener) signal?.removeEventListener('abort', shutdownListener)
    if (child.exitCode == null && child.signalCode == null) child.kill('SIGTERM')
  }
}

export async function main({ signal } = {}) {
  while (!signal?.aborted) {
    if (!attachmentTokenStore.value) {
      setRuntimeStatus('waiting_for_browser', false)
      await waitFor(config.pollMs, signal).catch(() => {})
      continue
    }
    try {
      if (runtime.status === 'starting' || runtime.status === 'config_required' || runtime.status === 'waiting_for_browser') {
        setRuntimeStatus('connecting', runtime.backendReachable)
      }
      const snapshotRequest = await api('/api/bridge/session-snapshot-requests/claim', {
        method: 'POST', body: '{}', signal,
      })
      if (snapshotRequest) {
        await processSnapshotRequest(snapshotRequest, { signal })
        continue
      }
      const job = await api('/api/bridge/jobs/claim', { method: 'POST', body: '{}', signal })
      if (job) await execute(job, { signal })
      else await waitFor(config.pollMs, signal)
    } catch (error) {
      if (signal?.aborted) break
      console.error(`[pi-bridge] ${error.message}`)
      await waitFor(config.pollMs, signal).catch(() => {})
    }
  }
}

export async function startBridge() {
  await mkdir(config.dataRoot, { recursive: true, mode: 0o700 })
  await mkdir(config.sessionRoot, { recursive: true })
  const stopController = new AbortController()
  let pollingStarted = false
  let pollingPromise
  const startPolling = () => {
    if (pollingStarted) return
    pollingStarted = true
    setRuntimeStatus('connecting', false)
    pollingPromise = main({ signal: stopController.signal })
  }
  const configServer = createConfigServer({
    store: projectStore,
    settingsStore,
    projectProvider: () => api('/api/bridge/projects'),
    onAttach: attachExecution,
    onSettingsSaved: startPolling,
    healthProvider: getBridgeHealth,
    host: config.configHost,
    port: config.configPort,
  })
  const address = await configServer.listen()
  console.log(`[pi-bridge] 本地工作区配置页：http://${config.configHost}:${address.port}/`)
  const settings = await settingsStore.read()
  setRuntimeStatus('waiting_for_browser', false)
  startPolling()
  let stopped = false
  const stop = async () => {
    if (stopped) return
    stopped = true
    setRuntimeStatus('stopping', false)
    stopController.abort()
    await pollingPromise?.catch(() => {})
    await configServer.close()
  }
  process.once('SIGTERM', () => { void stop() })
  process.once('SIGINT', () => { void stop() })
  process.once('SIGHUP', () => { void stop() })
  return { ...configServer, stop }
}

const isMainModule = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMainModule) {
  startBridge().catch((error) => {
    console.error(`[pi-bridge] ${error.message}`)
    process.exitCode = 1
  })
}
