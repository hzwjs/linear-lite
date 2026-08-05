export interface CommentDto {
  id: number
  authorId: number
  authorName: string
  body: string
  parentId: number | null
  rootId: number | null
  depth: number
  createdAt: string
  deletable: boolean
}

export interface CommentSubmitPayload {
  body: string
  mentionedUserIds: number[]
  parentId: number | null
}
