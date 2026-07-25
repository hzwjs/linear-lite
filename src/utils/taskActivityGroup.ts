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
  | {
      kind: 'grouped_label_changes'
      activities: TaskActivity[]
      actorName: string
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
  if (previous.fieldName === 'labels' || next.fieldName === 'labels') return false
  if (normalizeActivityActorName(previous.actorName) !== normalizeActivityActorName(next.actorName)) return false
  if (previous.fieldName !== next.fieldName) return false
  const gapMs = previous.createdAt - next.createdAt
  return gapMs >= 0 && gapMs <= GROUP_WINDOW_MS
}

function isLabelActivity(activity: TaskActivity): boolean {
  return activity.actionType === 'changed' && activity.fieldName === 'labels'
}

function labelActivityGroupsByActor(sortedActivities: TaskActivity[]): Map<string, TaskActivity[]> {
  const groups = new Map<string, TaskActivity[]>()
  for (const activity of sortedActivities) {
    if (!isLabelActivity(activity)) continue
    const actorName = normalizeActivityActorName(activity.actorName)
    const group = groups.get(actorName)
    if (group) group.push(activity)
    else groups.set(actorName, [activity])
  }
  return groups
}

/**
 * 连续标签保存会产生多条 old/new 快照；以最早旧值和最新新值还原为一次变更，
 * 从而显示为 Linear 风格的一行标签活动。
 */
export function getTaskActivityDisplaySource(item: TaskActivityDisplayItem): TaskActivity {
  if (item.kind === 'single') return item.activity
  if (item.kind === 'grouped_changes') return item.activities[0]!

  const latest = item.activities[0]!
  const earliest = item.activities[item.activities.length - 1]!
  return {
    ...latest,
    oldValue: earliest.oldValue,
    newValue: latest.newValue
  }
}

export function groupTaskActivitiesForDisplay(activities: TaskActivity[]): TaskActivityDisplayItem[] {
  const grouped: TaskActivityDisplayItem[] = []
  const sortedActivities = sortActivitiesForDisplay(activities)
  const labelsByActor = labelActivityGroupsByActor(sortedActivities)
  const emittedLabelActors = new Set<string>()

  for (const activity of sortedActivities) {
    const lastItem = grouped[grouped.length - 1]
    const normalizedActorName = normalizeActivityActorName(activity.actorName)

    if (isLabelActivity(activity)) {
      if (emittedLabelActors.has(normalizedActorName)) continue
      emittedLabelActors.add(normalizedActorName)
      const labelActivities = labelsByActor.get(normalizedActorName) ?? [activity]
      grouped.push(
        labelActivities.length === 1
          ? { kind: 'single', activity, actorName: normalizedActorName }
          : { kind: 'grouped_label_changes', activities: labelActivities, actorName: normalizedActorName }
      )
      continue
    }

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
  if (item.kind === 'grouped_label_changes') {
    return formatTaskActivity(getTaskActivityDisplaySource(item))
  }

  const actor = item.actorName || 'Someone'
  const fieldLabel = formatTaskActivityFieldLabel(item.fieldName)
  return `${translate('activity.updated', { actor })} · ${fieldLabel} ×${item.activities.length}`
}
