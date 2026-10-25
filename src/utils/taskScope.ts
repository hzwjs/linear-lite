import type { Status, Task } from '../types/domain'

export type TaskScope = 'all' | 'active' | 'backlog'

const ALL_STATUSES: readonly Status[] = ['backlog', 'todo', 'in_progress', 'in_review', 'done', 'canceled', 'duplicate']
const ACTIVE_STATUSES: readonly Status[] = ['todo', 'in_progress', 'in_review']
const BACKLOG_STATUSES: readonly Status[] = ['backlog']

export function scopeStatuses(scope: TaskScope): readonly Status[] {
  if (scope === 'active') return ACTIVE_STATUSES
  if (scope === 'backlog') return BACKLOG_STATUSES
  return ALL_STATUSES
}

export function applyTaskScope(tasks: Task[], scope: TaskScope): Task[] {
  if (scope === 'all') return tasks
  const statuses = scopeStatuses(scope)
  return tasks.filter((task) => statuses.includes(task.status))
}
