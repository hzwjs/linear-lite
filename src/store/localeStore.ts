import { defineStore } from 'pinia'
import { ref } from 'vue'
import { i18n } from '../i18n'

export const LOCALE_STORAGE_KEY = 'linear-lite.locale'

export type Locale = 'zh-CN' | 'en'

function readStoredLocale(): string | null {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return null
  return window.localStorage.getItem(LOCALE_STORAGE_KEY)
}

function guessBrowserLocale(): Locale {
  if (typeof window === 'undefined' || typeof window.navigator === 'undefined') return 'en'
  const lang = window.navigator.language ?? ''
  return lang.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

function isSupportedLocale(value: string | null): value is Locale {
  return value === 'zh-CN' || value === 'en'
}

function resolveLocale(): Locale {
  const stored = readStoredLocale()
  if (stored && isSupportedLocale(stored)) return stored
  return guessBrowserLocale()
}

function persistLocale(value: Locale) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return
  window.localStorage.setItem(LOCALE_STORAGE_KEY, value)
}

export const useLocaleStore = defineStore('localeStore', () => {
  const initialLocale = resolveLocale()
  const locale = ref<Locale>(initialLocale)
  i18n.global.locale.value = initialLocale

  function setLocale(next: Locale) {
    locale.value = next
    persistLocale(next)
    i18n.global.locale.value = next
  }

  return {
    locale,
    setLocale
  }
})
