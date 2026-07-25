import type { Task, TaskActivity } from '../types/domain'
import { activityApi } from '../services/api/activity'
import { attachmentsApi } from '../services/api/attachments'
import type { TaskAttachment } from '../services/api/types'
import { taskCommentsApi, type TaskCommentDto } from '../services/api/taskComments'

export interface TaskDetailSnapshot {
  subIssueRows: Array<{ task: Task; depth: number }>
  activities: TaskActivity[]
  comments: TaskCommentDto[]
  attachments: TaskAttachment[]
}

const snapshots = new Map<string, TaskDetailSnapshot>()
const pending = new Map<string, Promise<TaskDetailSnapshot>>()

export function readTaskDetailSnapshot(taskId: string): TaskDetailSnapshot | undefined {
  return snapshots.get(taskId)
}

export function invalidateTaskDetailSnapshot(taskId: string): void {
  snapshots.delete(taskId)
}

/**
 * Fetch the detail-only data before opening the workspace. This keeps transient
 * request states out of the continuous task editing surface.
 */
export function preloadTaskDetail(
  task: Task,
  fetchSubIssues: (parentNumericId: number) => Promise<Task[]>
): Promise<TaskDetailSnapshot> {
  const cached = snapshots.get(task.id)
  if (cached) return Promise.resolve(cached)
  const current = pending.get(task.id)
  if (current) return current

  const request = (async () => {
    const loadSubIssueRows = async () => {
      if (task.numericId == null) return []
      const rows: Array<{ task: Task; depth: number }> = []
      const appendChildren = async (parentNumericId: number, depth: number): Promise<void> => {
        const children = await fetchSubIssues(parentNumericId)
        for (const child of children) {
          rows.push({ task: child, depth })
          if (child.numericId != null) await appendChildren(child.numericId, depth + 1)
        }
      }
      await appendChildren(task.numericId, 0)
      return rows
    }

    const [subIssueRows, activities, comments, attachments] = await Promise.all([
      loadSubIssueRows(),
      activityApi.list(task.id),
      taskCommentsApi.list(task.id),
      attachmentsApi.list(task.id)
    ])
    const snapshot = { subIssueRows, activities, comments, attachments }
    snapshots.set(task.id, snapshot)
    return snapshot
  })()

  pending.set(task.id, request)
  return request.finally(() => pending.delete(task.id))
}
