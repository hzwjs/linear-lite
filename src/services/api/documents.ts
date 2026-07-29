import { isAxiosError } from 'axios'
import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type {
  ProjectDocument,
  ProjectDocumentRevision,
  ProjectDocumentRevisionSummary,
  ProjectDocumentTreeNode
} from '../../types/document'

const UTF8_FILENAME_PATTERN = /(?:^|;)\s*filename\*=UTF-8''([^;]+)(?:;|$)/i

export function parseDocumentAttachmentFileName(contentDisposition: string | undefined): string {
  const encodedFileName = contentDisposition?.match(UTF8_FILENAME_PATTERN)?.[1]
  if (encodedFileName == null) {
    throw new Error('Document attachment response is missing a UTF-8 filename')
  }

  let fileName: string
  try {
    fileName = decodeURIComponent(encodedFileName)
  } catch {
    throw new Error('Document attachment response contains an invalid UTF-8 filename')
  }
  if (fileName.trim().length === 0) {
    throw new Error('Document attachment response contains an empty filename')
  }
  return fileName
}

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

  async downloadAttachment(documentId: number, attachmentId: number): Promise<void> {
    const response = await api.get<Blob>(
      `/project-documents/${documentId}/attachments/${attachmentId}/download`,
      { responseType: 'blob' }
    )
    // 服务端以 filename* 输出 UTF-8 文件名；缺失时必须中止，避免静默生成错误文件名。
    const fileName = parseDocumentAttachmentFileName(response.headers['content-disposition'])
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const objectUrl = URL.createObjectURL(blob)
    const link = window.document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    window.document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
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
