import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { Project, User } from '../../types/domain'

export interface GitLabRepository {
  id: number
  repositoryUrl: string
  repositoryPath: string
  /** 仅创建或重置 Secret 后返回一次。 */
  webhookToken?: string | null
  createdAt: string
}

export interface GitHubRepository {
  id: number
  repositoryUrl: string
  repositoryPath: string
  webhookSecret?: string | null
  createdAt: string
}

/** 供 GitLab Webhook 配置使用，随前端 API 部署前缀变化。 */
export function gitlabWebhookUrl(): string {
  const base = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '')
  return new URL(`${base}/webhooks/gitlab`, window.location.origin).href
}

export function githubWebhookUrl(): string {
  const base = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '')
  return new URL(`${base}/webhooks/github`, window.location.origin).href
}

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

function asArray<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : []
}

export const projectApi = {
  list(): Promise<Project[]> {
    return api
      .get<ApiResponse<ApiProject[]>>('/projects')
      .then((res) => asArray(unwrap(res)).map(toProject))
  },

  create(body: { name: string; identifier: string }) {
    return api
      .post<ApiResponse<ApiProject>>('/projects', body)
      .then((res) => toProject(unwrap(res)))
  },

  reorder(projectIds: number[]): Promise<void> {
    return api
      .put<ApiResponse<null>>('/projects/order', { projectIds })
      .then((res) => {
        unwrap(res)
      })
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
  },

  getEmailSettings(projectId: number): Promise<{ scenarioKey: string; enabled: boolean }[]> {
    return api
      .get<ApiResponse<{ scenarioKey: string; enabled: boolean }[]>>(`/projects/${projectId}/email-settings`)
      .then((res) => asArray(unwrap(res)))
  },

  putEmailSettings(
    projectId: number,
    items: { scenarioKey: string; enabled: boolean }[]
  ): Promise<void> {
    return api
      .put<ApiResponse<null>>(`/projects/${projectId}/email-settings`, { items })
      .then((res) => {
        unwrap(res)
      })
  },

  listGitLabRepositories(projectId: number): Promise<GitLabRepository[]> {
    return api
      .get<ApiResponse<GitLabRepository[]>>(`/projects/${projectId}/gitlab-repositories`)
      .then((res) => asArray(unwrap(res)))
  },

  createGitLabRepository(projectId: number, repositoryUrl: string): Promise<GitLabRepository> {
    return api
      .post<ApiResponse<GitLabRepository>>(`/projects/${projectId}/gitlab-repositories`, { repositoryUrl })
      .then(unwrap)
  },

  resetGitLabWebhookToken(projectId: number, repositoryId: number): Promise<GitLabRepository> {
    return api
      .post<ApiResponse<GitLabRepository>>(
        `/projects/${projectId}/gitlab-repositories/${repositoryId}/webhook-token/reset`
      )
      .then(unwrap)
  },

  deleteGitLabRepository(projectId: number, repositoryId: number): Promise<void> {
    return api
      .delete<ApiResponse<null>>(`/projects/${projectId}/gitlab-repositories/${repositoryId}`)
      .then((res) => {
        unwrap(res)
      })
  },

  listGitHubRepositories(projectId: number): Promise<GitHubRepository[]> {
    return api.get<ApiResponse<GitHubRepository[]>>(`/projects/${projectId}/github-repositories`).then((res) => asArray(unwrap(res)))
  },

  createGitHubRepository(projectId: number, repositoryUrl: string): Promise<GitHubRepository> {
    return api.post<ApiResponse<GitHubRepository>>(`/projects/${projectId}/github-repositories`, { repositoryUrl }).then(unwrap)
  },

  resetGitHubWebhookSecret(projectId: number, repositoryId: number): Promise<GitHubRepository> {
    return api.post<ApiResponse<GitHubRepository>>(`/projects/${projectId}/github-repositories/${repositoryId}/webhook-secret/reset`).then(unwrap)
  },

  deleteGitHubRepository(projectId: number, repositoryId: number): Promise<void> {
    return api.delete<ApiResponse<null>>(`/projects/${projectId}/github-repositories/${repositoryId}`).then((res) => { unwrap(res) })
  },

  /** 获取项目成员列表（负责人选择用） */
  listMembers(projectId: number): Promise<User[]> {
    return api
      .get<ApiResponse<{ id: number; username: string; avatar_url?: string }[]>>(
        `/projects/${projectId}/members`
      )
      .then((res) =>
        asArray(unwrap(res)).map((u) => ({
          id: u.id,
          username: u.username,
          ...(u.avatar_url != null && { avatar_url: u.avatar_url })
        }))
      )
  }
}
