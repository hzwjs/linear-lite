import type { CollaborationConfig, ContentFormat, DocumentRoomContext, StoredRoomState } from './types.js'

export class JavaCollaborationClient {
  constructor(private readonly config: CollaborationConfig) {}

  async authorize(roomToken: string, roomName: string): Promise<DocumentRoomContext> {
    return this.post('/internal/collaboration/authorize', { roomToken, roomName })
  }

  async validateUpdate(input: {
    documentId: number
    epoch: string
    userId: number
    imageAssetIds: number[]
    contentFormat: ContentFormat
    contentBytes: number
  }): Promise<void> {
    await this.post('/internal/collaboration/validate-update', input)
  }

  async loadState(documentId: number, epoch: string, transitionId?: string): Promise<StoredRoomState> {
    const query = new URLSearchParams({ epoch })
    if (transitionId) query.set('transitionId', transitionId)
    return this.request(`/internal/collaboration/documents/${documentId}/state?${query}`)
  }

  async storeState(input: {
    documentId: number
    epoch: string
    contentFormat: ContentFormat
    expectedCommitSeq: number
    stateBase64: string
    content: string
    editorId: number
    counters: Record<string, number>
    transitionId?: string
  }): Promise<{ commitSeq: number; version: number }> {
    return this.put(`/internal/collaboration/documents/${input.documentId}/state?epoch=${encodeURIComponent(input.epoch)}`, input)
  }

  async getTransition(transitionId: string): Promise<{
    transitionId: string
    status: 'ACTIVE' | 'FINALIZED' | 'ABORTED'
    documents: Array<{ documentId: number; epoch: string; commitSeq: number; version: number; archived: boolean }>
  }> {
    return this.request(`/internal/collaboration/transitions/${encodeURIComponent(transitionId)}`)
  }

  async restoreEpoch(documentId: number, input: {
    expectedEpoch: string
    expectedCommitSeq: number
    contentFormat: ContentFormat
    stateBase64: string
    content: string
    editorId: number
    operationId: string
    title?: string
  }): Promise<{ epoch: string; commitSeq: number; version: number }> {
    return this.post(`/internal/collaboration/documents/${documentId}/restore-epoch`, input)
  }

  async releaseTransition(input: {
    transitionId: string
    status: 'FINALIZED' | 'ABORTED'
    documents: Array<{ documentId: number; epoch: string }>
  }): Promise<void> {
    await this.post('/internal/collaboration/transitions/release', input)
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    return this.request(path, { method: 'POST', body: JSON.stringify(body) })
  }

  private async put<T>(path: string, body: unknown): Promise<T> {
    return this.request(path, { method: 'PUT', body: JSON.stringify(body) })
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const abort = new AbortController()
    const timeout = setTimeout(() => abort.abort(), this.config.requestTimeoutMs)
    try {
      const response = await fetch(new URL(path, this.config.javaBaseUrl), {
        ...init,
        signal: abort.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-Collaboration-Internal-Key': this.config.internalKey,
          ...init.headers,
        },
      })
      const text = await response.text()
      if (!response.ok) {
        const error = new Error(`Java collaboration request failed (${response.status})`)
        Object.assign(error, { status: response.status, responseBody: text })
        throw error
      }
      return (text ? JSON.parse(text) : undefined) as T
    } finally {
      clearTimeout(timeout)
    }
  }
}
