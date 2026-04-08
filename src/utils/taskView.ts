import type { Task, User } from '../types/domain'
import type { GroupBy, ViewConfig } from './viewPreference'

export interface TaskRow {
  task: Task
  depth: number
  /** 子任务行展示用：父任务标题 */
  parentTitle?: string
  /** 自顶层父到直接父任务的 task.id；子行是否展示取决于路径上每一级是否已展开 */
  subtaskExpandPath?: string[]
}

export interface TaskGroup {
  key: string
  label: string
  tasks: Task[]
  /** Phase 7: 列表展示行（含子任务缩进），有则用 rows 渲染，否则用 tasks 且 depth=0 */
  rows?: TaskRow[]
}

export interface AdjacentTaskIds {
  previousTaskId: string | null
  nextTaskId: string | null
  position: number | null
  total: number
}

export interface BuildTaskGroupsOptions {
  /** 为 true 时：过滤后的子任务也参与分组；列表不再挂嵌套子行，避免与扁平结果重复 */
  searchActive?: boolean
  /**
   * 为 true 时：与 searchActive 相同，用过滤后的全部任务参与分组（含子任务）。
   * 状态/优先级/负责人等筛选后，命中项可能仅为子任务，不能只取 parentId == null。
   */
  taskFiltersActive?: boolean
}

const STATUS_ORDER: Task['status'][] = [
  'backlog',
  'todo',
  'in_progress',
  'in_review',
  'done',
  'canceled',
  'duplicate'
]
const PRIORITY_ORDER: Task['priority'][] = ['urgent', 'high', 'medium', 'low']
const STATUS_LABELS: Record<Task['status'], string> = {
  backlog: 'Backlog',
  todo: 'Todo',
  in_progress: 'In Progress',
  in_review: 'In Review',
  done: 'Done',
  canceled: 'Canceled',
  duplicate: 'Duplicate'
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

export function sortTasks(tasks: Task[], config: ViewConfig) {
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

/** 某父任务下的子任务行（含深度与父标题），nested 时递归包含孙级。parentNumericId 为父任务后端主键，与 Task.parentId（字符串形式的父主键）匹配。 */
function getDescendantRows(
  allTasks: Task[],
  parentNumericId: number,
  parentTitle: string,
  parentTaskId: string,
  pathFromRoot: string[],
  nested: boolean,
  config: ViewConfig
): TaskRow[] {
  const parentIdStr = String(parentNumericId)
  const children = allTasks.filter((t) => t.parentId != null && String(t.parentId) === parentIdStr)
  const sorted = sortTasks(children, config)
  const result: TaskRow[] = []
  const thisPath = [...pathFromRoot, parentTaskId]
  for (const task of sorted) {
    result.push({ task, depth: thisPath.length, parentTitle, subtaskExpandPath: thisPath })
    if (nested && task.numericId != null) {
      result.push(
        ...getDescendantRows(allTasks, task.numericId, task.title, task.id, thisPath, true, config)
      )
    }
  }
  return result
}

/** 按列表内「子任务折叠」状态过滤行；未展开时 depth>0 的行不展示。 */
export function filterVisibleTaskRows(
  rows: TaskRow[],
  expandedByTaskId: Record<string, boolean>
): TaskRow[] {
  return rows.filter((row) => {
    if (row.depth === 0) return true
    const path = row.subtaskExpandPath
    if (!path?.length) return true
    return path.every((id) => expandedByTaskId[id] === true)
  })
}

const TERMINAL_STATUSES: Task['status'][] = ['done', 'canceled', 'duplicate']

export function buildTaskGroups(
  tasks: Task[],
  config: ViewConfig,
  users: User[] = [],
  options?: BuildTaskGroupsOptions
): TaskGroup[] {
  const flatRoots =
    options?.searchActive === true || options?.taskFiltersActive === true
  const source = config.completedVisibility === 'open_only'
    ? tasks.filter((task) => !TERMINAL_STATUSES.includes(task.status))
    : tasks

  const seeds = flatRoots ? source : source.filter((t) => t.parentId == null)
  const grouped = new Map<string, TaskGroup>()

  for (const task of sortTasks(seeds, config)) {
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

  const out = sortGroups([...grouped.values()], config.groupBy)

  if (config.showSubIssues) {
    for (const group of out) {
      if (flatRoots) {
        group.rows = group.tasks.map((task) => ({ task, depth: 0 }))
        continue
      }
      const rows: TaskRow[] = []
      for (const task of group.tasks) {
        rows.push({ task, depth: 0 })
        if (task.numericId != null) {
          rows.push(
            ...getDescendantRows(source, task.numericId, task.title, task.id, [], config.nestedSubIssues, config)
          )
        }
      }
      group.rows = rows
    }
  }

  return out
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
