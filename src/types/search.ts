export type ProjectContentType = 'task' | 'document'

export interface ProjectContentSearchResult {
  contentType: ProjectContentType
  resourceId: string
  projectId: number
  projectIdentifier: string
  projectName: string
  title: string
  excerpt: string
}
