import type { Task, User } from '../types/domain'
import type { GroupBy, ViewConfig } from './viewPreference'

export interface TaskGroup {
  key: string
  label: string
  tasks: Task[]
}

export interface AdjacentTaskIds {
  previousTaskId: string | null
  nextTaskId: string | null
  position: number | null
  total: number
}

const STATUS_ORDER: Task['status'][] = ['todo', 'in_progress', 'done']
const PRIORITY_ORDER: Task['priority'][] = ['urgent', 'high', 'medium', 'low']
const STATUS_LABELS: Record<Task['status'], string> = {
  todo: 'Todo',
  in_progress: 'In Progress',
  done: 'Done'
}

function comparePriority(a: Task['priority'], b: Task['priority']) {
  return PRIORITY_ORDER.indexOf(a) - PRIORITY_ORDER.indexOf(b)
}

function compareMaybeNumber(a?: number | null, b?: number | null) {
  if (a == null && b == null) return 0
  if (a == null) return 1
  if (b == null) return -1
  return a - b
}

function sortTasks(tasks: Task[], config: ViewConfig) {
  const sorted = [...tasks].sort((left, right) => {
    let result = 0
    switch (config.orderBy) {
      case 'createdAt':
        result = left.createdAt - right.createdAt
        break
      case 'priority':
        result = comparePriority(left.priority, right.priority)
        break
      case 'dueDate':
        result = compareMaybeNumber(left.dueDate, right.dueDate)
        break
      case 'title':
        result = left.title.localeCompare(right.title)
        break
      case 'updatedAt':
      default:
        result = left.updatedAt - right.updatedAt
        break
    }
    if (result === 0) {
      result = left.id.localeCompare(right.id)
    }
    return config.orderDirection === 'asc' ? result : -result
  })
  return sorted
}

function groupMeta(task: Task, groupBy: GroupBy, users: User[]) {
  switch (groupBy) {
    case 'priority':
      return { key: task.priority, label: task.priority.replace(/^./, (s) => s.toUpperCase()) }
    case 'assignee': {
      if (task.assigneeId == null) return { key: 'unassigned', label: 'Unassigned' }
      const name = users.find((user) => user.id === task.assigneeId)?.username ?? `User ${task.assigneeId}`
      return { key: `assignee:${task.assigneeId}`, label: name }
    }
    case 'project':
      return { key: String(task.projectId ?? 'none'), label: task.projectId == null ? 'No Project' : `Project ${task.projectId}` }
    case 'none':
      return { key: 'all', label: 'All issues' }
    case 'status':
    default:
      return { key: task.status, label: STATUS_LABELS[task.status] }
  }
}

function sortGroups(groups: TaskGroup[], groupBy: GroupBy) {
  if (groupBy === 'status') {
    return [...groups].sort(
      (left, right) => STATUS_ORDER.indexOf(left.key as Task['status']) - STATUS_ORDER.indexOf(right.key as Task['status'])
    )
  }
  if (groupBy === 'priority') {
    return [...groups].sort(
      (left, right) =>
        PRIORITY_ORDER.indexOf(left.key as Task['priority']) - PRIORITY_ORDER.indexOf(right.key as Task['priority'])
    )
  }
  return [...groups].sort((left, right) => left.label.localeCompare(right.label))
}

export function buildTaskGroups(tasks: Task[], config: ViewConfig, users: User[] = []): TaskGroup[] {
  const source = config.completedVisibility === 'open_only'
    ? tasks.filter((task) => task.status !== 'done')
    : tasks

  const grouped = new Map<string, TaskGroup>()

  for (const task of sortTasks(source, config)) {
    const meta = groupMeta(task, config.groupBy, users)
    const existing = grouped.get(meta.key)
    if (existing) {
      existing.tasks.push(task)
      continue
    }
    grouped.set(meta.key, {
      key: meta.key,
      label: meta.label,
      tasks: [task]
    })
  }

  if (config.showEmptyGroups) {
    if (config.groupBy === 'status') {
      for (const status of STATUS_ORDER) {
        if (!grouped.has(status)) {
          grouped.set(status, {
            key: status,
            label: STATUS_LABELS[status],
            tasks: []
          })
        }
      }
    }
    if (config.groupBy === 'priority') {
      for (const priority of PRIORITY_ORDER) {
        if (!grouped.has(priority)) {
          grouped.set(priority, {
            key: priority,
            label: priority,
            tasks: []
          })
        }
      }
    }
  }

  return sortGroups([...grouped.values()], config.groupBy)
}

export function getAdjacentTaskIds(taskIds: string[], currentTaskId: string | null): AdjacentTaskIds {
  const total = taskIds.length
  if (!currentTaskId) {
    return {
      previousTaskId: null,
      nextTaskId: null,
      position: null,
      total
    }
  }

  const index = taskIds.indexOf(currentTaskId)
  if (index === -1) {
    return {
      previousTaskId: null,
      nextTaskId: null,
      position: null,
      total
    }
  }

  return {
    previousTaskId: taskIds[index - 1] ?? null,
    nextTaskId: taskIds[index + 1] ?? null,
    position: index + 1,
    total
  }
}
