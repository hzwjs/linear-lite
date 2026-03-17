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
  },

  /** 通过后端代理下载，保留原始文件名（带鉴权） */
  async download(taskKey: string, attachmentId: number, fileName: string): Promise<void> {
    const res = await api.get(`/tasks/${taskKey}/attachments/${attachmentId}/download`, {
      responseType: 'blob'
    })
    const blob = res.data instanceof Blob ? res.data : new Blob([res.data])
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName || 'download'
    a.click()
    URL.revokeObjectURL(url)
  }
}
