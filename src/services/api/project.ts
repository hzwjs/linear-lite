import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { Project } from '../../types/domain'

interface ApiProject {
  id: number
  name: string
  identifier: string
  createdAt: string
}

function toProject(p: ApiProject): Project {
  return {
    id: p.id,
    name: p.name,
    identifier: p.identifier,
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
  }
}
