import { api, unwrap } from './index'
import type { ApiResponse } from './types'

export interface InAppNotificationDto {
  id: number
  type: string
  taskKey: string
  commentId: number
  summary: string | null
  readAt: string | null
  createdAt: string
}

export const notificationsApi = {
  list(params?: { beforeId?: number; limit?: number; unreadOnly?: boolean }): Promise<InAppNotificationDto[]> {
    return api
      .get<ApiResponse<InAppNotificationDto[]>>('/me/notifications', { params })
      .then((res) => unwrap(res))
  },

  unreadCount(): Promise<number> {
    return api
      .get<ApiResponse<{ count: number }>>('/me/notifications/unread-count')
      .then((res) => unwrap(res).count)
  },

  markRead(id: number): Promise<void> {
    return api.post<ApiResponse<null>>(`/me/notifications/${id}/read`).then((res) => {
      unwrap(res)
    })
  },

  markAllRead(): Promise<void> {
    return api.post<ApiResponse<null>>('/me/notifications/read-all').then((res) => {
      unwrap(res)
    })
  }
}
