import { i18n } from '../i18n'

export function translate(key: string, params?: Record<string, unknown>): string {
  return i18n.global.t(key, params)
}
