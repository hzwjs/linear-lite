import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import { JWT_STORAGE_KEY } from './constants'

export interface AgentTaskStatus {
  executionId: string | null
  jobId: number | null
  sessionStatus: string | null
  jobStatus: string | null
  sourceType: string | null
  errorMessage: string | null
  updatedAt: string | null
}

export interface LocalPiPrepareResponse {
  status: AgentTaskStatus
  attachmentCode: string
}

export interface AgentDisplayContent {
  text: string
  thinking: string
}

export type AgentToolContent =
  | { type: 'text'; text: string }
  | { type: 'image'; data: string; mimeType: string }

export interface AgentDisplayTool {
  name: string
  arguments: Record<string, unknown>
  content: AgentToolContent[]
  isError: boolean
  truncation: unknown | null
}

export interface SessionDisplayBlock {
  blockId: string
  entryId: string
  runtimeBlockId: string | null
  order: number
  kind: 'user' | 'assistant' | 'tool'
  phase: 'final' | 'error'
  content: AgentDisplayContent | null
  tool: AgentDisplayTool | null
  createdAt: string
}

export interface SessionSnapshot {
  executionId: string
  piSessionId: string
  leafId: string | null
  blocks: SessionDisplayBlock[]
}

export interface RuntimeDisplayBlock {
  executionId: string
  jobId: number
  blockId: string
  revision: number
  kind: 'assistant' | 'tool'
  phase: 'streaming' | 'error'
  content: AgentDisplayContent | null
  tool: AgentDisplayTool | null
  createdAt: string
}

export type ConversationDisplayBlock = SessionDisplayBlock | RuntimeDisplayBlock

function sessionStreamUrl(taskKey: string, executionId: string): string {
  const token = localStorage.getItem(JWT_STORAGE_KEY)
  if (!token) throw new Error('缺少登录凭证')
  const base = api.defaults.baseURL ?? '/api'
  const url = new URL(
    `${base}/tasks/${encodeURIComponent(taskKey)}/local-pi/sessions/${encodeURIComponent(executionId)}/stream`,
    window.location.origin
  )
  url.searchParams.set('access_token', token)
  return url.toString()
}

export const agentApi = {
  prepareLocalPi(taskKey: string): Promise<LocalPiPrepareResponse> {
    return api.post<ApiResponse<LocalPiPrepareResponse>>(`/tasks/${encodeURIComponent(taskKey)}/local-pi/prepare`)
      .then(unwrap)
  },

  getTaskStatus(taskKey: string): Promise<AgentTaskStatus> {
    return api.get<ApiResponse<AgentTaskStatus>>(`/tasks/${encodeURIComponent(taskKey)}/local-pi/status`)
      .then(unwrap)
  },

  submitTurn(taskKey: string, executionId: string, prompt: string): Promise<AgentTaskStatus> {
    return api.post<ApiResponse<AgentTaskStatus>>(`/tasks/${encodeURIComponent(taskKey)}/local-pi/turns`, {
      executionId,
      prompt
    }).then(unwrap)
  },

  cancelTask(taskKey: string, executionId: string): Promise<void> {
    return api.post<ApiResponse<void>>(`/tasks/${encodeURIComponent(taskKey)}/local-pi/cancel`, {
      executionId
    }).then(unwrap)
  },

  requestSessionSnapshot(taskKey: string, executionId: string): Promise<string> {
    return api.post<ApiResponse<string>>(
      `/tasks/${encodeURIComponent(taskKey)}/local-pi/sessions/${encodeURIComponent(executionId)}/snapshot-requests`
    ).then(unwrap)
  },

  openSessionStream(
    taskKey: string,
    executionId: string,
    onSnapshot: (snapshot: SessionSnapshot) => void,
    onRuntimeBlock: (block: RuntimeDisplayBlock) => void,
    onSessionReadError: (message: string) => void,
    onOpen?: () => void,
    onError?: () => void
  ): EventSource {
    const source = new EventSource(sessionStreamUrl(taskKey, executionId))
    source.addEventListener('session-snapshot', (event) => {
      try {
        onSnapshot(JSON.parse((event as MessageEvent).data) as SessionSnapshot)
      } catch {
        // SessionSnapshot 是历史唯一入口，损坏数据不得覆盖当前基线。
      }
    })
    source.addEventListener('runtime-display-block', (event) => {
      try {
        onRuntimeBlock(JSON.parse((event as MessageEvent).data) as RuntimeDisplayBlock)
      } catch {
        // 损坏的临时包直接丢弃，等待下一 revision 或最终 session 快照。
      }
    })
    source.addEventListener('session-read-error', (event) => {
      try {
        const payload = JSON.parse((event as MessageEvent).data) as { executionId: string; message: string }
        onSessionReadError(payload.message)
      } catch {
        onSessionReadError('Pi session 读取失败')
      }
    })
    if (onOpen) source.addEventListener('open', onOpen)
    if (onError) source.addEventListener('error', onError)
    return source
  }
}
