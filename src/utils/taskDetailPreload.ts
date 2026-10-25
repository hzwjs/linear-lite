import type { Task, TaskActivity } from '../types/domain'
import { activityApi } from '../services/api/activity'
import { attachmentsApi } from '../services/api/attachments'
import type { TaskAttachment } from '../services/api/types'
import { taskCommentsApi } from '../services/api/taskComments'
import type { CommentDto } from '../types/comment'

export interface TaskDetailSnapshot {
  subIssueRows: Array<{ task: Task; depth: number }>
  activities: TaskActivity[]
  comments: CommentDto[]
  attachments: TaskAttachment[]
}

const DETAIL_CACHE_VERSION = 'v2'
const snapshots = new Map<string, TaskDetailSnapshot>()
const pending = new Map<string, Promise<TaskDetailSnapshot>>()

function cacheKey(taskId: string): string {
  return `${DETAIL_CACHE_VERSION}:${taskId}`
}

export function readTaskDetailSnapshot(taskId: string): TaskDetailSnapshot | undefined {
  return snapshots.get(cacheKey(taskId))
}

export function invalidateTaskDetailSnapshot(taskId: string): void {
  snapshots.delete(cacheKey(taskId))
}

/**
 * Fetch detail-only data in the background. The task workspace must not wait
 * for these secondary requests before it can render.
 */
export function preloadTaskDetail(
  task: Task,
  fetchSubIssues: (parentNumericId: number) => Promise<Task[]>
): Promise<TaskDetailSnapshot> {
  const key = cacheKey(task.id)
  const cached = snapshots.get(key)
  if (cached) return Promise.resolve(cached)
  const current = pending.get(key)
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
    snapshots.set(key, snapshot)
    return snapshot
  })()

  pending.set(key, request)
  return request.finally(() => pending.delete(key))
}
