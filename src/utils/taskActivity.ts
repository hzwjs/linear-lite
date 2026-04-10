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

export function formatTaskActivityFieldLabel(fieldName: string | null | undefined): string {
  return normalizeFieldLabel(fieldName)
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
