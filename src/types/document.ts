export interface DocumentImageAsset {
  /** 不可变图片资源 ID，等于 project_document_attachments.id。 */
  assetId: number
  contentHash: string
  width: number | null
  height: number | null
  /** 缩略图地址；无法生成缩略图（如 WebP）时为 null，渲染回退原图。 */
  thumbnailUrl: string | null
  originalUrl: string
}

export interface ProjectDocumentAttachment {
  id: number
  projectId: number
  documentId: number
  sourceId: string | null
  fileName: string
  fileSize: number
  contentType: string | null
  sha256: string
  width: number | null
  height: number | null
  thumbnailUrl: string | null
  url: string
  createdAt: string
}

export interface ProjectDocumentTreeNode {
  id: number
  projectId: number
  parentDocumentId: number | null
  title: string
  sortOrder: number
  version: number
  favorited: boolean
  updatedAt: string
}

export interface ProjectDocument extends ProjectDocumentTreeNode {
  content: string
  imageAssets: DocumentImageAsset[]
  creatorId: number
  lastEditorId: number
  archivedAt: string | null
  createdAt: string
}

export interface ProjectDocumentRevisionSummary {
  revisionId: number
  sourceVersion: number
  title: string
  editorId: number
  editorName: string | null
  createdAt: string
}

export interface ProjectDocumentRevision {
  documentId: number
  revisionId: number
  sourceVersion: number
  title: string
  content: string
  imageAssets: DocumentImageAsset[]
  editorId: number
  editorName: string | null
  createdAt: string
}

export type DocumentSaveState =
  | 'idle'
  | 'dirty'
  | 'saving'
  | 'saved'
  | 'conflict'
  | 'invalid'
  | 'failed'
