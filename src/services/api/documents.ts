import { isAxiosError } from 'axios'
import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type {
  ProjectDocument,
  ProjectDocumentRevision,
  ProjectDocumentRevisionSummary,
  ProjectDocumentTreeNode
} from '../../types/document'

export interface DocumentConflict {
  currentVersion: number
}
export function getDocumentConflict(error: unknown): DocumentConflict | null {
  if (!isAxiosError(error) || error.response?.status !== 409) return null
  const body = error.response.data as { data?: { currentVersion?: unknown } } | undefined
  const currentVersion = body?.data?.currentVersion
  return typeof currentVersion === 'number' ? { currentVersion } : null
}

export const documentApi = {
  listTree(projectId: number): Promise<ProjectDocumentTreeNode[]> {
    return api
      .get<ApiResponse<ProjectDocumentTreeNode[]>>(`/projects/${projectId}/documents/tree`)
      .then(unwrap)
  },

  listArchive(projectId: number): Promise<ProjectDocumentTreeNode[]> {
    return api
      .get<ApiResponse<ProjectDocumentTreeNode[]>>(`/projects/${projectId}/documents/archive`)
      .then(unwrap)
  },

  create(projectId: number, body: { parentDocumentId: number | null; title: string }): Promise<ProjectDocument> {
    return api
      .post<ApiResponse<ProjectDocument>>(`/projects/${projectId}/documents`, body)
      .then(unwrap)
  },

  get(documentId: number): Promise<ProjectDocument> {
    return api.get<ApiResponse<ProjectDocument>>(`/project-documents/${documentId}`).then(unwrap)
  },

  update(
    documentId: number,
    body: { expectedVersion: number; title: string; content: string }
  ): Promise<ProjectDocument> {
    return api
      .put<ApiResponse<ProjectDocument>>(`/project-documents/${documentId}`, body)
      .then(unwrap)
  },

  move(
    documentId: number,
    body: { parentDocumentId: number | null; previousSiblingId: number | null }
  ): Promise<void> {
    return api
      .put<ApiResponse<null>>(`/project-documents/${documentId}/position`, body)
      .then((res) => { unwrap(res) })
  },

  archive(documentId: number): Promise<void> {
    return api
      .post<ApiResponse<null>>(`/project-documents/${documentId}/archive`)
      .then((res) => { unwrap(res) })
  },

  restore(documentId: number): Promise<void> {
    return api
      .post<ApiResponse<null>>(`/project-documents/${documentId}/restore`)
      .then((res) => { unwrap(res) })
  },

  listRevisions(documentId: number): Promise<ProjectDocumentRevisionSummary[]> {
    return api
      .get<ApiResponse<ProjectDocumentRevisionSummary[]>>(`/project-documents/${documentId}/revisions`)
      .then(unwrap)
  },

  getRevision(documentId: number, version: number): Promise<ProjectDocumentRevision> {
    return api
      .get<ApiResponse<ProjectDocumentRevision>>(`/project-documents/${documentId}/revisions/${version}`)
      .then(unwrap)
  },

  restoreRevision(documentId: number, version: number, expectedVersion: number): Promise<ProjectDocument> {
    return api
      .post<ApiResponse<ProjectDocument>>(
        `/project-documents/${documentId}/revisions/${version}/restore`,
        { expectedVersion }
      )
      .then(unwrap)
  }
}
