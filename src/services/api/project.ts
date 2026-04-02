import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { Project } from '../../types/domain'

interface ApiProject {
  id: number
  name: string
  identifier: string
  creatorId: number
  createdAt: string
}

function toProject(p: ApiProject): Project {
  return {
    id: p.id,
    name: p.name,
    identifier: p.identifier,
    creatorId: p.creatorId,
    createdAt: p.createdAt
  }
}

export const projectApi = {
  list(): Promise<Project[]> {
    return api
      .get<ApiResponse<ApiProject[]>>('/projects')
      .then((res) => unwrap(res).map(toProject))
  },

  create(body: { name: string; identifier: string }) {
    return api
      .post<ApiResponse<ApiProject>>('/projects', body)
      .then((res) => toProject(unwrap(res)))
  },

  update(id: number, body: { name?: string; identifier?: string }) {
    return api
      .put<ApiResponse<ApiProject>>(`/projects/${id}`, body)
      .then((res) => toProject(unwrap(res)))
  },

  delete(id: number): Promise<void> {
    return api
      .delete<ApiResponse<null>>(`/projects/${id}`)
      .then((res) => {
        unwrap(res)
      })
  },

  invite(id: number, body: { email: string }): Promise<void> {
    return api
      .post<ApiResponse<null>>(`/projects/${id}/invitations`, body)
      .then((res) => {
        unwrap(res)
      })
  },

  listLabels(projectId: number, query?: string): Promise<{ id: number; name: string }[]> {
    return api
      .get<ApiResponse<{ id: number; name: string }[]>>(`/projects/${projectId}/labels`, {
        params: query != null && query !== '' ? { query } : undefined
      })
      .then((res) => unwrap(res))
  },

  /** 从项目标签表删除定义，并移除所有任务上该标签的关联 */
  deleteLabel(projectId: number, labelId: number): Promise<void> {
    return api
      .delete<ApiResponse<null>>(`/projects/${projectId}/labels/${labelId}`)
      .then((res) => {
        unwrap(res)
      })
  }
}
