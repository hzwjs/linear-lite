import type { Priority, Status } from '../types/domain'
import { translate } from './i18n'

export function getStatusLabel(value: Status | string): string {
  return translate(`status.${value}`, undefined, value)
}

export function getPriorityLabel(value: Priority | string): string {
  return translate(`priority.${value}`, undefined, value)
}
