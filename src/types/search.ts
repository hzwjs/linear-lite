import type { DocumentContentFormat } from './document'

export type ProjectContentType = 'task' | 'document'

export interface ProjectContentSearchResult {
  contentFormat: DocumentContentFormat | null
  contentType: ProjectContentType
  resourceId: string
  projectId: number
  projectIdentifier: string
  projectName: string
  title: string
  excerpt: string
}
