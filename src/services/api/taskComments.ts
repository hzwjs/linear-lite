import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { CommentDto } from '../../types/comment'

function toComment(raw: CommentDto): CommentDto {
  const parentId = typeof raw.parentId === 'number' && Number.isFinite(raw.parentId) ? raw.parentId : null
  const rootId = typeof raw.rootId === 'number' && Number.isFinite(raw.rootId) ? raw.rootId : null
  const depth = typeof raw.depth === 'number' && Number.isFinite(raw.depth) ? raw.depth : 0
  return {
    id: raw.id,
    authorId: raw.authorId,
    authorName: raw.authorName,
    body: raw.body,
    parentId,
    rootId,
    depth,
    createdAt: raw.createdAt,
    deletable: raw.deletable
  }
}

function extractComments(
  payload: CommentDto[] | { taskComments?: CommentDto[] } | null | undefined
): CommentDto[] {
  if (Array.isArray(payload)) return payload
  if (payload && Array.isArray(payload.taskComments)) return payload.taskComments
  return []
}

export const taskCommentsApi = {
  list(taskKey: string): Promise<CommentDto[]> {
    return api
      .get<ApiResponse<CommentDto[] | { taskComments?: CommentDto[] }>>(
        `/tasks/${encodeURIComponent(taskKey)}/comments`
      )
      .then((res) => extractComments(unwrap(res)).map(toComment))
  },

  create(
    taskKey: string,
    payload: { body: string; mentionedUserIds: number[]; parentId: number | null }
  ): Promise<CommentDto> {
    return api
      .post<ApiResponse<CommentDto>>(`/tasks/${encodeURIComponent(taskKey)}/comments`, payload)
      .then((res) => toComment(unwrap(res)))
  },

  delete(taskKey: string, commentId: number): Promise<void> {
    return api
      .delete<ApiResponse<null>>(`/tasks/${encodeURIComponent(taskKey)}/comments/${commentId}`)
      .then((res) => {
        unwrap(res)
      })
  }
}
