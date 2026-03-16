import type { TaskActivity } from '../types/domain'

const STATUS_LABELS: Record<string, string> = {
  backlog: 'Backlog',
  todo: 'Todo',
  in_progress: 'In Progress',
  in_review: 'In Review',
  done: 'Done',
  canceled: 'Canceled',
  duplicate: 'Duplicate'
}

const PRIORITY_LABELS: Record<string, string> = {
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  urgent: 'Urgent'
}

function normalizeFieldLabel(fieldName: string | null | undefined): string {
  switch (fieldName) {
    case 'assigneeId':
      return 'assignee'
    case 'dueDate':
      return 'due date'
    default:
      return fieldName ?? 'field'
  }
}

function formatFieldValue(fieldName: string | null | undefined, value: string | null | undefined): string {
  if (!value) return 'empty'
  if (fieldName === 'status') return STATUS_LABELS[value] ?? value
  if (fieldName === 'priority') return PRIORITY_LABELS[value] ?? value
  if (fieldName === 'dueDate') return new Date(value).toLocaleDateString()
  return value
}

export function getActivityAvatarLabel(actorName: string | null | undefined): string {
  const trimmed = (actorName ?? '').trim()
  if (!trimmed) return '?'
  return trimmed[0]!.toUpperCase()
}

export function formatTaskActivity(activity: TaskActivity): string {
  switch (activity.actionType) {
    case 'created':
      return `${activity.actorName} created the issue`
    case 'favorited':
      return `${activity.actorName} favorited the issue`
    case 'unfavorited':
      return `${activity.actorName} removed the issue from favorites`
    case 'changed':
      if (activity.fieldName === 'title' || activity.fieldName === 'description') {
        return `${activity.actorName} changed ${normalizeFieldLabel(activity.fieldName)}`
      }
      return `${activity.actorName} changed ${normalizeFieldLabel(activity.fieldName)} from ${formatFieldValue(activity.fieldName, activity.oldValue)} to ${formatFieldValue(activity.fieldName, activity.newValue)}`
    default:
      return `${activity.actorName} updated the issue`
  }
}
