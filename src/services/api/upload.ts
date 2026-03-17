import { api, unwrap } from './index'
import type { ApiResponse, ImageUploadResponse } from './types'

export const uploadApi = {
  uploadImage(file: File): Promise<ImageUploadResponse> {
    const formData = new FormData()
    formData.append('file', file)
    return api
      .post<ApiResponse<ImageUploadResponse>>('/uploads/images', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      .then((res) => unwrap(res))
  }
}
