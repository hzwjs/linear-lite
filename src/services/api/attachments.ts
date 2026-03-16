import { api, unwrap } from './index'
import type { ApiResponse, TaskAttachment } from './types'

export const attachmentsApi = {
  upload(taskKey: string, file: File): Promise<TaskAttachment> {
    const formData = new FormData()
    formData.append('file', file)
    return api
      .post<ApiResponse<TaskAttachment>>(`/tasks/${taskKey}/attachments`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      .then((res) => unwrap(res))
  },

  list(taskKey: string): Promise<TaskAttachment[]> {
    return api
      .get<ApiResponse<TaskAttachment[]>>(`/tasks/${taskKey}/attachments`)
      .then((res) => unwrap(res))
  },

  delete(taskKey: string, attachmentId: number): Promise<void> {
    return api
      .delete(`/tasks/${taskKey}/attachments/${attachmentId}`)
      .then(() => undefined)
  }
}
