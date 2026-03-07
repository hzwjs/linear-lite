import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { Task } from '../../types/domain'
import type { ApiTask, CreateTaskRequest, UpdateTaskRequest } from './types'

function toTask(t: ApiTask): Task {
  return {
    id: t.taskKey,
    title: t.title,
    description: t.description ?? undefined,
    status: t.status as Task['status'],
    priority: t.priority as Task['priority'],
    projectId: t.projectId,
    creatorId: t.creatorId ?? undefined,
    assigneeId: t.assigneeId ?? undefined,
    dueDate: t.dueDate ? new Date(t.dueDate).getTime() : undefined,
    completedAt: t.completedAt ? new Date(t.completedAt).getTime() : undefined,
    createdAt: t.createdAt ? new Date(t.createdAt).getTime() : 0,
    updatedAt: t.updatedAt ? new Date(t.updatedAt).getTime() : 0
  }
}

export const taskApi = {
  list(projectId: number): Promise<Task[]> {
    return api
      .get<ApiResponse<ApiTask[]>>('/tasks', { params: { projectId } })
      .then((res) => unwrap(res).map(toTask))
  },

  create(body: CreateTaskRequest): Promise<Task> {
    return api
      .post<ApiResponse<ApiTask>>('/tasks', body)
      .then((res) => toTask(unwrap(res)))
  },

  update(taskKey: string, body: UpdateTaskRequest): Promise<Task> {
    return api
      .put<ApiResponse<ApiTask>>(`/tasks/${taskKey}`, body)
      .then((res) => toTask(unwrap(res)))
  }
}
