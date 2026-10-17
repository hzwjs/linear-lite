import { api, unwrap } from './index'
import type { ApiResponse } from './types'

export interface DocumentCollaborationSession {
  documentId: number
  epoch: string
  roomName: string
  websocketUrl: string
  roomToken: string
  user: { id: number; name: string; color: string }
  sessionId: string
  expiresAt: string
  version: number
  commitSeq: number
  contentFormat: 'BLOCKNOTE' | 'HTML'
  stateBase64: string
}

export interface DocumentCollaborationRenewal {
  sessionId: string
  epoch: string
  roomToken: string
  expiresAt: string
}

export interface DocumentTitleUpdate {
  title: string
  version: number
  updatedAt: string
  lastEditorId: number
}

export const documentCollaborationApi = {
  createSession(documentId: number): Promise<DocumentCollaborationSession> {
    return api.post<ApiResponse<DocumentCollaborationSession>>(
      `/project-documents/${documentId}/collaboration-session`
    ).then(unwrap)
  },

  refreshSession(documentId: number, sessionId: string): Promise<DocumentCollaborationRenewal> {
    return api.post<ApiResponse<DocumentCollaborationRenewal>>(
      `/project-documents/${documentId}/collaboration-session/refresh`, { sessionId }
    ).then(unwrap)
  },

  updateTitle(documentId: number, expectedTitle: string, title: string): Promise<DocumentTitleUpdate> {
    return api.patch<ApiResponse<DocumentTitleUpdate>>(
      `/project-documents/${documentId}/title`, { expectedTitle, title }
    ).then(unwrap)
  }
}
