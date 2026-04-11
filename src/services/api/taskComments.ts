import { api, unwrap } from './index'
import type { ApiResponse } from './types'

export interface TaskCommentDto {
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

function toComment(raw: TaskCommentDto): TaskCommentDto {
  return {
    id: raw.id,
    authorId: raw.authorId,
    authorName: raw.authorName,
    body: raw.body,
    parentId: raw.parentId,
    rootId: raw.rootId,
    depth: raw.depth,
    createdAt: raw.createdAt,
    deletable: raw.deletable
  }
}

export const taskCommentsApi = {
  list(taskKey: string): Promise<TaskCommentDto[]> {
    return api
      .get<ApiResponse<TaskCommentDto[]>>(`/tasks/${encodeURIComponent(taskKey)}/comments`)
      .then((res) => unwrap(res).map(toComment))
  },

  create(
    taskKey: string,
    payload: { body: string; mentionedUserIds: number[]; parentId: number | null }
  ): Promise<TaskCommentDto> {
    return api
      .post<ApiResponse<TaskCommentDto>>(`/tasks/${encodeURIComponent(taskKey)}/comments`, payload)
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
