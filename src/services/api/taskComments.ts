import { api, unwrap } from './index'
import type { ApiResponse } from './types'

export interface TaskCommentDto {
  id: number
  authorId: number
  authorName: string
  body: string
  createdAt: string
  deletable: boolean
}

function toComment(raw: TaskCommentDto): TaskCommentDto {
  return {
    id: raw.id,
    authorId: raw.authorId,
    authorName: raw.authorName,
    body: raw.body,
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

  create(taskKey: string, body: string, mentionedUserIds: number[]): Promise<TaskCommentDto> {
    return api
      .post<ApiResponse<TaskCommentDto>>(`/tasks/${encodeURIComponent(taskKey)}/comments`, {
        body,
        mentionedUserIds
      })
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
