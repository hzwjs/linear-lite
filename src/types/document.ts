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
  creatorId: number
  lastEditorId: number
  archivedAt: string | null
  createdAt: string
}

export interface ProjectDocumentRevisionSummary {
  version: number
  title: string
  editorId: number
  createdAt: string
}

export interface ProjectDocumentRevision {
  documentId: number
  version: number
  title: string
  content: string
  editorId: number
  createdAt: string
}

export type DocumentSaveState =
  | 'idle'
  | 'dirty'
  | 'saving'
  | 'saved'
  | 'conflict'
  | 'failed'
