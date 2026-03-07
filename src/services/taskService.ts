import type { Task, Status } from '../types/domain'
import { taskApi } from './api/task'

/**
 * 任务请求层：委托 taskApi，供需要 projectId 的调用方使用。
 * 看板数据由 taskStore 按 activeProjectId 拉取，不直接使用此 service。
 */
export const taskService = {
  async listTasks(projectId: number): Promise<Task[]> {
    return taskApi.list(projectId)
  },

  async getTask(projectId: number, taskKey: string): Promise<Task> {
    const list = await taskApi.list(projectId)
    const task = list.find((t) => t.id === taskKey)
    if (!task) throw new Error('Task not found')
    return task
  },

  async createTask(
    data: Omit<Task, 'id' | 'createdAt' | 'updatedAt'> & { projectId: number }
  ): Promise<Task> {
    return taskApi.create({
      projectId: data.projectId,
      title: data.title,
      description: data.description,
      status: data.status,
      priority: data.priority,
      assigneeId: data.assigneeId ?? null
    })
  },

  async updateTask(
    id: string,
    updates: Partial<Omit<Task, 'id' | 'createdAt' | 'updatedAt'>>
  ): Promise<Task> {
    return taskApi.update(id, {
      title: updates.title,
      description: updates.description,
      status: updates.status,
      priority: updates.priority,
      assigneeId: updates.assigneeId
    })
  },

  async transitionTask(id: string, newStatus: Status): Promise<Task> {
    return taskApi.update(id, { status: newStatus })
  }
}
