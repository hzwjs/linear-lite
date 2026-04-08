import type { Task } from '../types/domain'
import type { ViewConfig } from './viewPreference'
import { applyCompletedVisibility, sortTasks } from './taskView'
import {
  taskToGanttRow,
  ganttDatesToTaskPatch,
  frappeGanttTaskId,
  type GanttRow
} from './ganttTaskMap'
import { formatDateInputValue } from './taskDate'

const MAX_NAME_INDENT_DEPTH = 12

/**
 * 子任务 `parentId` 存父任务 numericId；解析为父任务在甘特上的 id（与 Frappe 任务 id 一致）。
 */
function resolveParentGanttDependencyId(
  child: Task,
  allTasks: Task[]
): string | undefined {
  if (child.parentId == null || child.parentId === '') return undefined
  const pid = String(child.parentId)
  const parent = allTasks.find(
    (t) => t.numericId != null && String(t.numericId) === pid
  )
  if (!parent) return undefined
  return frappeGanttTaskId(parent.id)
}

function flattenDescendantTasks(
  allTasks: Task[],
  parentNumericId: number,
  depth: number,
  nested: boolean,
  config: ViewConfig
): Array<{ task: Task; depth: number }> {
  const parentIdStr = String(parentNumericId)
  const children = allTasks.filter(
    (t) => t.parentId != null && String(t.parentId) === parentIdStr
  )
  const sorted = sortTasks(children, config)
  const result: Array<{ task: Task; depth: number }> = []
  for (const task of sorted) {
    result.push({ task, depth })
    if (nested && task.numericId != null) {
      result.push(
        ...flattenDescendantTasks(allTasks, task.numericId, depth + 1, true, config)
      )
    }
  }
  return result
}

/**
 * 与列表一致：开启 showSubIssues 时父任务后紧跟子任务（可选 nested）；否则仅顶层。
 * flatRoots（搜索/筛选命中子任务等）时与分组视图一致，扁平排序、不嵌套。
 *
 * `tasks` 须已与列表同源（例如已 applyCompletedVisibility）。
 */
export function orderedTasksForGanttWithDepth(
  tasks: Task[],
  config: ViewConfig,
  flatRoots: boolean
): Array<{ task: Task; depth: number }> {
  if (flatRoots) {
    return sortTasks(tasks, config).map((task) => ({ task, depth: 0 }))
  }
  if (!config.showSubIssues) {
    return sortTasks(tasks.filter((t) => t.parentId == null), config).map((task) => ({
      task,
      depth: 0
    }))
  }
  const roots = sortTasks(tasks.filter((t) => t.parentId == null), config)
  const out: Array<{ task: Task; depth: number }> = []
  for (const task of roots) {
    out.push({ task, depth: 0 })
    if (task.numericId != null) {
      out.push(
        ...flattenDescendantTasks(
          tasks,
          task.numericId,
          1,
          config.nestedSubIssues,
          config
        )
      )
    }
  }
  // 父任务无 numericId、或 parentId 与父 numericId 不一致导致未挂到树上的子任务，列表里仍可能存在，甘特补在末尾避免「少条」
  const seen = new Set(out.map(({ task: t }) => t.id))
  const orphans = sortTasks(
    tasks.filter((t) => !seen.has(t.id)),
    config
  ).map((task) => ({ task, depth: 0 as const }))
  return [...out, ...orphans]
}

export function getGanttRows(
  tasks: Task[],
  config: ViewConfig,
  flatRoots: boolean
): GanttRow[] {
  const source = applyCompletedVisibility(tasks, config)
  return orderedTasksForGanttWithDepth(source, config, flatRoots)
    .map(({ task, depth }) => {
      const row = taskToGanttRow(task)
      if (!row) return null
      const d = Math.min(depth, MAX_NAME_INDENT_DEPTH)
      const indent = d > 0 ? `${'  '.repeat(d)}` : ''
      const base = { ...row, name: indent + row.name }
      if (flatRoots || d === 0) return base
      const dep = resolveParentGanttDependencyId(task, tasks)
      if (!dep) return base
      return { ...base, dependencies: dep }
    })
    .filter((r): r is GanttRow => r != null)
}

export function dateRangeToTaskPatch(start: Date, end: Date) {
  return ganttDatesToTaskPatch(formatDateInputValue(start.getTime()), formatDateInputValue(end.getTime()))
}
