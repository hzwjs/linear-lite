import { api, toApiError, unwrap } from './index'
import type { ApiResponse } from './types'

/** 字段与项目设备绑定响应保持一致。 */
export interface ProjectDeviceBinding {
  id: number
  userId: number
  projectId: number
  deviceId: string
  projectDirectory: string
  revision: number
  createdAt: string
  updatedAt: string
}

export const projectDeviceBindingApi = {
  async list(projectId: number): Promise<ProjectDeviceBinding[]> {
    try {
      const data = unwrap(await api.post<ApiResponse<{ bindings: ProjectDeviceBinding[] }>>('/collaboration/bindings/list', { projectId }))
      return data.bindings
    } catch (error) {
      throw toApiError(error)
    }
  },

  async save(projectId: number, deviceId: string, projectDirectory: string): Promise<ProjectDeviceBinding> {
    try {
      return unwrap(await api.post<ApiResponse<ProjectDeviceBinding>>('/collaboration/bindings/save', {
        projectId, deviceId, projectDirectory,
      }))
    } catch (error) {
      throw toApiError(error)
    }
  },

  async delete(projectId: number, deviceId: string): Promise<void> {
    try {
      unwrap(await api.post<ApiResponse<{ deleted: boolean }>>('/collaboration/bindings/delete', { projectId, deviceId }))
    } catch (error) {
      throw toApiError(error)
    }
  },
}
