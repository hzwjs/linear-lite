import { api, toApiError, unwrap } from './index'
import { isAxiosError } from 'axios'
import type { ApiResponse } from './types'

/** 与 HostDeviceAuthorizationResponse 对应。 */
export interface HostDeviceAuthorization {
  id: number
  userId: number
  deviceId: string
  deviceName: string
  authorized: boolean
  online: boolean
  lastHeartbeatAt: string | null
  createdAt: string | null
}

export const collaborationHostApi = {
  async list(): Promise<HostDeviceAuthorization[]> {
    try { return unwrap(await api.get<ApiResponse<HostDeviceAuthorization[]>>('/collaboration/hosts')) }
    catch (error) { throw toApiError(error) }
  },
  async revoke(id: number): Promise<void> {
    try { unwrap(await api.delete<ApiResponse<void>>(`/collaboration/hosts/${id}`)) }
    catch (error) { throw toApiError(error) }
  },
  async installer(): Promise<{ blob: Blob; filename: string }> {
    try {
      const response = await api.get<Blob>('/collaboration/host-installer', { responseType: 'blob' })
      const disposition = response.headers['content-disposition']
      const match = typeof disposition === 'string' && /^attachment;\s*filename="([A-Za-z0-9._-]+\.dmg)"$/i.exec(disposition)
      if (!match || !(response.data instanceof Blob)) throw new Error('宿主安装包下载响应无效')
      return { blob: response.data, filename: match[1]! }
    }
    catch (error) {
      // 下载响应按 Blob 读取，错误仍沿用服务端 ApiResponse.message。
      if (isAxiosError(error) && error.response?.data instanceof Blob) {
        let payload: unknown
        try { payload = JSON.parse(await error.response.data.text()) } catch { /* 保留原请求错误 */ }
        if (typeof payload === 'object' && payload !== null && 'message' in payload && typeof payload.message === 'string') {
          throw new Error(payload.message)
        }
      }
      throw toApiError(error)
    }
  },
}
