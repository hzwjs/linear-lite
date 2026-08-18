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

export interface AgentTaskEvent {
  id: number
  executionId: string
  jobId: number
  sequenceNo: number
  eventType: 'started' | 'progress' | 'tool_call' | 'tool_result' | 'completed' | 'failed'
  summary: string
  payload: string
  createdAt: string
}

function eventStreamUrl(taskKey: string, executionId: string, jobId: number): string {
  const token = localStorage.getItem(JWT_STORAGE_KEY)
  if (!token) throw new Error('缺少登录凭证')
  const base = api.defaults.baseURL ?? '/api'
  const url = new URL(
    `${base}/tasks/${encodeURIComponent(taskKey)}/local-pi/events/stream`,
    window.location.origin
  )
  url.searchParams.set('executionId', executionId)
  url.searchParams.set('jobId', String(jobId))
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

  openEventStream(
    taskKey: string,
    executionId: string,
    jobId: number,
    onEvent: (event: AgentTaskEvent) => void,
    onOpen?: () => void,
    onError?: () => void,
    onReplayComplete?: () => void
  ): EventSource {
    const source = new EventSource(eventStreamUrl(taskKey, executionId, jobId))
    source.addEventListener('agent-event', (event) => {
      try {
        onEvent(JSON.parse((event as MessageEvent).data) as AgentTaskEvent)
      } catch {
        // SSE 的单条事件格式由后端 DTO 固定，格式错误时丢弃该条，不污染进度列表。
      }
    })
    if (onOpen) source.addEventListener('open', onOpen)
    if (onError) source.addEventListener('error', onError)
    if (onReplayComplete) source.addEventListener('replay-complete', onReplayComplete)
    return source
  }
}
