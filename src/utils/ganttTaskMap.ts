import type { Task } from '../types/domain'
import { formatDateInputValue, parseDateInputValue } from './taskDate'

/** Frappe Gantt 任务行：`end` 为「含意上的最后一天」（含），与库内对纯日期 `end` 加 24h 得到的排他上界一致，映射层使用日历日字符串，不做 exclusive +1。 */
export interface GanttRow {
  id: string
  name: string
  start: string
  end: string
  progress: number
}

function calendarDayKey(ms: number): string {
  return formatDateInputValue(ms)
}

export function taskToGanttRow(task: Task): GanttRow | null {
  const hasPlanned = task.plannedStartDate != null
  const hasDue = task.dueDate != null

  if (!hasPlanned && !hasDue) return null

  let startMs: number
  let endMs: number

  if (hasPlanned && hasDue) {
    startMs = task.plannedStartDate!
    endMs = task.dueDate!
    if (calendarDayKey(startMs) > calendarDayKey(endMs)) return null
  } else if (hasDue) {
    startMs = endMs = task.dueDate!
  } else {
    startMs = endMs = task.plannedStartDate!
  }

  const start = formatDateInputValue(startMs)
  const end = formatDateInputValue(endMs)

  let progress = task.progressPercent ?? 0
  if (progress < 0) progress = 0
  if (progress > 100) progress = 100

  return {
    id: task.id,
    name: task.title,
    start,
    end,
    progress,
  }
}

export function ganttDatesToTaskPatch(
  start: string,
  end: string
): { plannedStartDate: number; dueDate: number } {
  const plannedStartDate = parseDateInputValue(start)
  const dueDate = parseDateInputValue(end)
  if (plannedStartDate === undefined || dueDate === undefined) {
    throw new Error(
      'ganttDatesToTaskPatch: start and end must be non-empty YYYY-MM-DD'
    )
  }
  return { plannedStartDate, dueDate }
}
