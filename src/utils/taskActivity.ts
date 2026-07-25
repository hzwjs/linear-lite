import type { TaskActivity } from '../types/domain'
import { translate } from './i18n'
import { getInitials } from './avatar'
import { getPriorityLabel, getStatusLabel } from './enumLabels'

function normalizeFieldLabel(fieldName: string | null | undefined): string {
  if (!fieldName) {
    return translate('fieldLabel.default', undefined, 'field')
  }
  const key = `fieldLabel.${fieldName}`
  return translate(key, undefined, fieldName)
}

function formatFieldValue(fieldName: string | null | undefined, value: string | null | undefined): string {
  if (!value) return translate('activity.emptyValue')
  if (fieldName === 'status') return getStatusLabel(value)
  if (fieldName === 'priority') return getPriorityLabel(value)
  if (fieldName === 'dueDate' || fieldName === 'plannedStartDate') return new Date(value).toLocaleDateString()
  if (fieldName === 'progressPercent') return `${value}%`
  if (fieldName === 'labels') {
    const sep = translate('activity.labelListSeparator', undefined, ', ')
    return value.split(',').filter(Boolean).join(sep)
  }
  return value
}

export function getActivityAvatarLabel(actorName: string | null | undefined): string {
  const trimmed = (actorName ?? '').trim()
  if (!trimmed) return '?'
  return getInitials(trimmed)
}

export function normalizeActivityActorName(actorName: string | null | undefined): string {
  return (actorName ?? '').trim()
}

/**
 * 活动流只呈现协作可见的任务生命周期和属性事件。
 * 描述正文及个人收藏属于内容/个人偏好，不应成为项目活动记录。
 */
export function isTaskActivityTimelineEvent(activity: TaskActivity): boolean {
  if (activity.actionType === 'favorited' || activity.actionType === 'unfavorited') return false
  return !(activity.actionType === 'changed' && activity.fieldName === 'description')
}

export function formatTaskActivityFieldLabel(fieldName: string | null | undefined): string {
  return normalizeFieldLabel(fieldName)
}

function parseLabelNames(value: string | null | undefined): string[] {
  return (value ?? '')
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
}

export type TaskActivityLabelChange = {
  added: string[]
  removed: string[]
}

export function getTaskActivityLabelChange(activity: TaskActivity): TaskActivityLabelChange {
  if (activity.actionType !== 'changed' || activity.fieldName !== 'labels') {
    return { added: [], removed: [] }
  }
  const oldLabels = new Set(parseLabelNames(activity.oldValue))
  const newLabels = new Set(parseLabelNames(activity.newValue))
  return {
    added: [...newLabels].filter((name) => !oldLabels.has(name)),
    removed: [...oldLabels].filter((name) => !newLabels.has(name))
  }
}

export function formatTaskActivity(activity: TaskActivity): string {
  const actor = normalizeActivityActorName(activity.actorName) || 'Someone'
  switch (activity.actionType) {
    case 'created':
      return translate('activity.created', { actor })
    case 'favorited':
      return translate('activity.favorited', { actor })
    case 'unfavorited':
      return translate('activity.unfavorited', { actor })
    case 'changed': {
      if (activity.fieldName === 'labels') {
        const { added, removed } = getTaskActivityLabelChange(activity)
        if (added.length && !removed.length) return translate('activity.addedLabels', { actor })
        if (removed.length && !added.length) return translate('activity.removedLabels', { actor })
        return translate('activity.updatedLabels', { actor })
      }
      const fieldLabel = normalizeFieldLabel(activity.fieldName)
      if (activity.fieldName === 'title' || activity.fieldName === 'description') {
        return translate('activity.changedField', { actor, field: fieldLabel })
      }
      const oldValue = formatFieldValue(activity.fieldName, activity.oldValue)
      const newValue = formatFieldValue(activity.fieldName, activity.newValue)
      return translate('activity.changedFromTo', {
        actor,
        field: fieldLabel,
        oldValue,
        newValue
      })
    }
    default:
      return translate('activity.updated', { actor })
  }
}
