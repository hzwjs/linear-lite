import type { Priority, Status } from '../types/domain'

const STORAGE_KEY_PREFIX = 'linear-lite.taskEditDraft.v1:'

export interface TaskEditDraftStored {
  savedAt: number
  title: string
  description: string
  status: Status
  priority: Priority
  assigneeId: number | null
  plannedStartDate: string
  dueDate: string
  progressPercent: number
}

function key(taskId: string): string {
  return `${STORAGE_KEY_PREFIX}${taskId}`
}

export function saveTaskEditDraft(taskId: string, draft: Omit<TaskEditDraftStored, 'savedAt'>): void {
  try {
    const payload: TaskEditDraftStored = { ...draft, savedAt: Date.now() }
    localStorage.setItem(key(taskId), JSON.stringify(payload))
  } catch {
    /* quota or private mode */
  }
}

export function readTaskEditDraft(taskId: string): TaskEditDraftStored | null {
  try {
    const raw = localStorage.getItem(key(taskId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as TaskEditDraftStored
    if (typeof parsed.savedAt !== 'number' || typeof parsed.title !== 'string') return null
    return parsed
  } catch {
    return null
  }
}

export function clearTaskEditDraft(taskId: string): void {
  try {
    localStorage.removeItem(key(taskId))
  } catch {
    /* ignore */
  }
}
