import { api, unwrap } from './index'
import type { ApiResponse } from './types'

export interface CodexRun { id: string; taskKey: string; status: string; branchName: string; resultSummary?: string | null; errorMessage?: string | null; taskUpdatedAt: string }
export interface CodexRunner { id: number; name: string; status: 'active' | 'revoked'; lastSeenAt?: string | null }
export interface CodexRepository { id: number; repositoryKey: string; displayName: string; remoteIdentity: string; defaultBranch: string }
export interface ProjectCodexBinding {
  runnerId: number
  repositoryId: number
  baseBranch: string
  /** GitLab 项目 path_with_namespace，首次 Webhook 事件后由服务端回填 */
  webhookPath?: string | null
  /** 仅保存/重置绑定返回一次，用于展示给用户在 GitLab 侧配置 */
  webhookToken?: string | null
}

/** 供用户在 GitLab 项目 Webhooks 中粘贴的完整回调地址（与前端 API 同源同前缀） */
export function gitlabWebhookUrl(): string {
  const base = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '')
  const url = `${base}/webhooks/gitlab`
  return new URL(url, window.location.origin).href
}
export const codexApi = {
  runners() { return api.get<ApiResponse<CodexRunner[]>>('/codex-runners').then(unwrap) },
  repositories(runnerId: number) { return api.get<ApiResponse<CodexRepository[]>>(`/codex-runners/${runnerId}/repositories`).then(unwrap) },
  createEnrollmentCode() { return api.post<ApiResponse<{ code: string; expiresAt: string }>>('/codex-runners/enrollment-codes').then(unwrap) },
  revokeRunner(runnerId: number) { return api.delete<ApiResponse<null>>(`/codex-runners/${runnerId}`).then(unwrap) },
  binding(projectId: number) { return api.get<ApiResponse<ProjectCodexBinding | null>>(`/projects/${projectId}/codex-binding`).then(unwrap) },
  saveBinding(projectId: number, body: { runnerId: number; repositoryId: number; baseBranch: string }) { return api.put<ApiResponse<ProjectCodexBinding>>(`/projects/${projectId}/codex-binding`, body).then(unwrap) },
  resetWebhookToken(projectId: number) { return api.post<ApiResponse<ProjectCodexBinding>>(`/projects/${projectId}/codex-binding/webhook-token/reset`).then(unwrap) },
  dispatch(taskKey: string, body: { clientRequestId: string; instruction: string }) { return api.post<ApiResponse<CodexRun>>(`/tasks/${encodeURIComponent(taskKey)}/codex-runs`, body).then(unwrap) },
  list(taskKey: string) { return api.get<ApiResponse<CodexRun[]>>(`/tasks/${encodeURIComponent(taskKey)}/codex-runs`).then(unwrap) },
  cancel(runId: string) { return api.post<ApiResponse<null>>(`/codex-runs/${encodeURIComponent(runId)}/cancel`).then(unwrap) },
  message(runId: string, content: string) { return api.post<ApiResponse<null>>(`/codex-runs/${encodeURIComponent(runId)}/messages`, { content }).then(unwrap) }
}
