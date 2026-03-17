import type { Priority, Status } from '../types/domain'
import { translate } from './i18n'

function translateWithFallback(key: string, fallback: string): string {
  const translated = translate(key)
  return translated === key ? fallback : translated
}

export function getStatusLabel(value: Status | string): string {
  return translateWithFallback(`status.${value}`, value)
}

export function getPriorityLabel(value: Priority | string): string {
  return translateWithFallback(`priority.${value}`, value)
}
