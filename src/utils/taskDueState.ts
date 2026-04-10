export type TaskDueStateKind = 'no_due_date' | 'overdue' | 'today' | 'upcoming'

export type TaskDueState = {
  kind: TaskDueStateKind
  dayCount: number
  isOverdue: boolean
  isToday: boolean
  isUpcoming: boolean
  hasDueDate: boolean
}

const MS_PER_DAY = 24 * 60 * 60 * 1000

function localDayIndex(date: Date): number {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / MS_PER_DAY
}

export function getTaskDueState(dueDateMs?: number | null, now: Date = new Date()): TaskDueState {
  if (dueDateMs == null || !Number.isFinite(dueDateMs)) {
    return {
      kind: 'no_due_date',
      dayCount: 0,
      isOverdue: false,
      isToday: false,
      isUpcoming: false,
      hasDueDate: false
    }
  }

  const dueDate = new Date(dueDateMs)
  if (Number.isNaN(dueDate.getTime()) || Number.isNaN(now.getTime())) {
    return {
      kind: 'no_due_date',
      dayCount: 0,
      isOverdue: false,
      isToday: false,
      isUpcoming: false,
      hasDueDate: false
    }
  }

  const diffDays = localDayIndex(dueDate) - localDayIndex(now)

  if (diffDays < 0) {
    return {
      kind: 'overdue',
      dayCount: Math.abs(diffDays),
      isOverdue: true,
      isToday: false,
      isUpcoming: false,
      hasDueDate: true
    }
  }

  if (diffDays === 0) {
    return {
      kind: 'today',
      dayCount: 0,
      isOverdue: false,
      isToday: true,
      isUpcoming: false,
      hasDueDate: true
    }
  }

  return {
    kind: 'upcoming',
    dayCount: diffDays,
    isOverdue: false,
    isToday: false,
    isUpcoming: true,
    hasDueDate: true
  }
}
