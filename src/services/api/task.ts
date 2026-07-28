import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { Task } from '../../types/domain'
import type {
  ApiTask,
  ApiTaskMutationResponse,
  ApiTaskStateChange,
  CreateTaskRequest,
  TaskImportRequest,
  TaskImportResponse,
  UpdateTaskRequest
} from './types'

export interface TaskAncestorStateChange {
  numericId: number
  id: string
  status: Task['status']
  progressPercent: number
  completedAt: number
  updatedAt: number
}

export interface TaskMutationResult {
  task: Task
  autoCompletedAncestors: TaskAncestorStateChange[]
}

function toTask(t: ApiTask): Task {
  return {
    id: t.taskKey,
    numericId: t.id,
    title: t.title,
    description: t.description ?? undefined,
    status: t.status as Task['status'],
    priority: t.priority as Task['priority'],
    projectId: t.projectId,
    creatorId: t.creatorId ?? undefined,
    assigneeId: t.assigneeId ?? undefined,
    assigneeDisplayName: t.assigneeDisplayName ?? undefined,
    dueDate: t.dueDate ? new Date(t.dueDate).getTime() : undefined,
    plannedStartDate: t.plannedStartDate ? new Date(t.plannedStartDate).getTime() : undefined,
    progressPercent: t.progressPercent != null ? t.progressPercent : 0,
    completedAt: t.completedAt ? new Date(t.completedAt).getTime() : undefined,
    createdAt: t.createdAt ? new Date(t.createdAt).getTime() : 0,
    updatedAt: t.updatedAt ? new Date(t.updatedAt).getTime() : 0,
    parentId: t.parentId != null ? String(t.parentId) : undefined,
    subIssueCount: t.subIssueCount,
    completedSubIssueCount: t.completedSubIssueCount,
    favorited: t.favorited ?? false,
    labels: t.labels?.map((l) => ({ id: l.id, name: l.name })) ?? undefined
  }
}

function asArray<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : []
}

function toAncestorStateChange(change: ApiTaskStateChange): TaskAncestorStateChange {
  return {
    numericId: change.id,
    id: change.taskKey,
    status: change.status as Task['status'],
    progressPercent: change.progressPercent,
    completedAt: new Date(change.completedAt).getTime(),
    updatedAt: new Date(change.updatedAt).getTime()
  }
}

function toMutationResult(response: ApiTaskMutationResponse): TaskMutationResult {
  return {
    task: toTask(response.task),
    autoCompletedAncestors: response.autoCompletedAncestors.map(toAncestorStateChange)
  }
}

export const taskApi = {
  get(taskKey: string): Promise<Task> {
    return api
      .get<ApiResponse<ApiTask>>(`/tasks/${taskKey}`)
      .then((res) => toTask(unwrap(res)))
  },

  list(projectId: number, params?: { topLevelOnly?: boolean; parentId?: number }): Promise<Task[]> {
    const query: Record<string, unknown> = { projectId }
    if (params?.topLevelOnly != null) query.topLevelOnly = params.topLevelOnly
    if (params?.parentId != null) query.parentId = params.parentId
    return api
      .get<ApiResponse<ApiTask[]>>('/tasks', { params: query })
      .then((res) => asArray(unwrap(res)).map(toTask))
  },

  search(query: string): Promise<Task[]> {
    return api
      // Embedding + Qdrant are remote calls; keep the search dialog from waiting indefinitely.
      .get<ApiResponse<ApiTask[]>>('/tasks/search', { params: { query }, timeout: 10000 })
      .then((res) => asArray(unwrap(res)).map(toTask))
  },

  create(body: CreateTaskRequest): Promise<TaskMutationResult> {
    return api
      .post<ApiResponse<ApiTaskMutationResponse>>('/tasks', body)
      .then((res) => toMutationResult(unwrap(res)))
  },

  import(body: TaskImportRequest): Promise<TaskImportResponse> {
    return api
      .post<ApiResponse<TaskImportResponse>>('/tasks/import', body)
      .then((res) => unwrap(res))
  },

  update(taskKey: string, body: UpdateTaskRequest): Promise<TaskMutationResult> {
    return api
      .put<ApiResponse<ApiTaskMutationResponse>>(`/tasks/${taskKey}`, body)
      .then((res) => toMutationResult(unwrap(res)))
  },

  listFavorites(): Promise<Task[]> {
    return api
      .get<ApiResponse<ApiTask[]>>('/tasks/favorites')
      .then((res) => asArray(unwrap(res)).map(toTask))
  },

  addFavorite(taskKey: string): Promise<Task> {
    return api
      .post<ApiResponse<ApiTask>>(`/tasks/${taskKey}/favorite`)
      .then((res) => toTask(unwrap(res)))
  },

  removeFavorite(taskKey: string): Promise<Task> {
    return api
      .delete<ApiResponse<ApiTask>>(`/tasks/${taskKey}/favorite`)
      .then((res) => toTask(unwrap(res)))
  }
}
