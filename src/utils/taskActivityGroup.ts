import type { TaskActivity } from '../types/domain'
import { translate } from './i18n'
import {
  formatTaskActivity,
  formatTaskActivityFieldLabel,
  normalizeActivityActorName
} from './taskActivity'

export type TaskActivityDisplayItem =
  | {
      kind: 'single'
      activity: TaskActivity
      actorName: string
    }
  | {
      kind: 'grouped_changes'
      activities: TaskActivity[]
      actorName: string
      fieldName: string | null | undefined
    }

const GROUP_WINDOW_MS = 5 * 60 * 1000

function sortActivitiesForDisplay(activities: TaskActivity[]): TaskActivity[] {
  return [...activities].sort((a, b) => {
    const byCreatedAt = b.createdAt - a.createdAt
    if (byCreatedAt !== 0) return byCreatedAt
    return b.id - a.id
  })
}

function canMergeActivity(previous: TaskActivity, next: TaskActivity): boolean {
  if (previous.actionType !== 'changed' || next.actionType !== 'changed') return false
  if (normalizeActivityActorName(previous.actorName) !== normalizeActivityActorName(next.actorName)) return false
  if (previous.fieldName !== next.fieldName) return false
  const gapMs = previous.createdAt - next.createdAt
  return gapMs >= 0 && gapMs <= GROUP_WINDOW_MS
}

export function groupTaskActivitiesForDisplay(activities: TaskActivity[]): TaskActivityDisplayItem[] {
  const grouped: TaskActivityDisplayItem[] = []

  for (const activity of sortActivitiesForDisplay(activities)) {
    const lastItem = grouped[grouped.length - 1]
    const normalizedActorName = normalizeActivityActorName(activity.actorName)

    if (lastItem?.kind === 'grouped_changes') {
      const lastActivity = lastItem.activities[lastItem.activities.length - 1]
      if (lastActivity && canMergeActivity(lastActivity, activity)) {
        lastItem.activities.push(activity)
        continue
      }
    } else if (lastItem?.kind === 'single' && canMergeActivity(lastItem.activity, activity)) {
      grouped[grouped.length - 1] = {
        kind: 'grouped_changes',
        activities: [lastItem.activity, activity],
        actorName: normalizeActivityActorName(lastItem.activity.actorName),
        fieldName: lastItem.activity.fieldName
      }
      continue
    }

    grouped.push({
      kind: 'single',
      activity,
      actorName: normalizedActorName
    })
  }

  return grouped
}

export function formatTaskActivityDisplayItem(item: TaskActivityDisplayItem): string {
  if (item.kind === 'single') {
    return formatTaskActivity(item.activity)
  }

  const actor = item.actorName || 'Someone'
  const fieldLabel = formatTaskActivityFieldLabel(item.fieldName)
  return `${translate('activity.updated', { actor })} · ${fieldLabel} ×${item.activities.length}`
}
