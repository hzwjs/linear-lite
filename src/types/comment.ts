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
  images: CommentImageDto[]
}

export interface CommentImageDto {
  id: number
  ref: string
  fileName: string
  contentType: string
  fileSize: number
  sourceAttachmentId: number | null
}

export interface CommentSubmitPayload {
  body: string
  mentionedUserIds: number[]
  parentId: number | null
}
