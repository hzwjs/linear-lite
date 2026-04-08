import type { Task } from '../types/domain'
import type { ViewConfig } from './viewPreference'
import { sortTasks } from './taskView'
import { taskToGanttRow, ganttDatesToTaskPatch, type GanttRow } from './ganttTaskMap'
import { formatDateInputValue } from './taskDate'

const MAX_NAME_INDENT_DEPTH = 12

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
  return out
}

export function getGanttRows(
  tasks: Task[],
  config: ViewConfig,
  flatRoots: boolean
): GanttRow[] {
  return orderedTasksForGanttWithDepth(tasks, config, flatRoots)
    .map(({ task, depth }) => {
      const row = taskToGanttRow(task)
      if (!row) return null
      const d = Math.min(depth, MAX_NAME_INDENT_DEPTH)
      const indent = d > 0 ? `${'  '.repeat(d)}` : ''
      return { ...row, name: indent + row.name }
    })
    .filter((r): r is GanttRow => r != null)
}

export function dateRangeToTaskPatch(start: Date, end: Date) {
  return ganttDatesToTaskPatch(formatDateInputValue(start.getTime()), formatDateInputValue(end.getTime()))
}
