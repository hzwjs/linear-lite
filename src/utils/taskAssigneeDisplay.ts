import type { Task } from '../types/domain'

export function assigneeDisplayLabel(task: Task, unassignedLabel: string): string {
  return task.assigneeKey == null ? unassignedLabel : task.assigneeName ?? ''
}
