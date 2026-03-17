import { i18n } from '../i18n'

export function translate(key: string, params?: Record<string, unknown>, fallback?: string): string {
  if (i18n.global.te(key)) {
    return (params === undefined ? i18n.global.t(key) : i18n.global.t(key, params)) as string
  }
  if (fallback !== undefined) return fallback
  return key
}
