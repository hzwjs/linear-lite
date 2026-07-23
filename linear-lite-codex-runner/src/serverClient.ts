export class ServerClient {
  constructor(private readonly baseUrl: string, private readonly token: string) {}
  private async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const res = await fetch(`${this.baseUrl.replace(/\/$/, '')}${path}`, { method, headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    const payload = await res.json() as { code: number; message?: string; data: T }
    if (!res.ok || payload.code !== 200) throw new Error(payload.message ?? `服务端请求失败: ${res.status}`)
    return payload.data
  }
  heartbeat(repositories: unknown[]) { return this.request<void>('/api/codex-runner/heartbeat', 'PUT', { version: '1.0.0', repositories }) }
  claim() { return this.request<Run | null>('/api/codex-runner/runs/claim', 'POST') }
  lease(id: string) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/lease`, 'PUT') }
  thread(id: string, codexThreadId: string) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/thread`, 'PUT', { codexThreadId }) }
  event(id: string, sequenceNo: number, eventType: string, eventPayload: string) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/events`, 'POST', { sequenceNo, eventType, eventPayload }) }
  needsInput(id: string, question: string) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/needs-input`, 'POST', { content: question }) }
  claimMessage(id: string) { return this.request<{ id: number; content: string } | null>(`/api/codex-runner/runs/${encodeURIComponent(id)}/messages/claim`, 'POST') }
  consumed(id: string, messageId: number) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/messages/${messageId}/consumed`, 'PUT') }
  complete(id: string, body: unknown) { return this.request<void>(`/api/codex-runner/runs/${encodeURIComponent(id)}/complete`, 'POST', body) }
}
export interface Run { id: string; taskKey: string; taskSnapshot: string; dispatchInstruction: string; repositoryId: number; repositoryKey: string; baseBranch: string; branchName: string; codexThreadId?: string | null; status: string; cancelRequestedAt?: string | null }
