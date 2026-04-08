import type { Task } from '../types/domain'
import { taskToGanttRow, ganttDatesToTaskPatch, type GanttRow } from './ganttTaskMap'
import { formatDateInputValue } from './taskDate'

export function getTopLevelGanttRows(tasks: Task[]): GanttRow[] {
  return tasks
    .filter((task) => !task.parentId)
    .map((task) => taskToGanttRow(task))
    .filter((row): row is GanttRow => row != null)
}

export function dateRangeToTaskPatch(start: Date, end: Date) {
  return ganttDatesToTaskPatch(formatDateInputValue(start.getTime()), formatDateInputValue(end.getTime()))
}
