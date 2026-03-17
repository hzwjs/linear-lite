import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useLocaleStore, LOCALE_STORAGE_KEY } from './localeStore'
import type { Locale } from './localeStore'

function mockNavigatorLanguage(value: string) {
  vi.stubGlobal('navigator', {
    ...window.navigator,
    language: value
  })
}

describe('localeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('prefers stored locale over browser preference', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'en')
    mockNavigatorLanguage('zh-CN')

    const store = useLocaleStore()

    expect(store.locale).toBe('en')
  })

  it('ignores unsupported stored locale values', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr')
    mockNavigatorLanguage('zh-CN')

    const store = useLocaleStore()

    expect(store.locale).toBe('zh-CN')
  })

  it('falls back to zh-CN when browser prefers Chinese', () => {
    mockNavigatorLanguage('zh-TW')

    const store = useLocaleStore()

    expect(store.locale).toBe('zh-CN')
  })

  it('persists locale changes', () => {
    const store = useLocaleStore()

    store.setLocale('en' as Locale)

    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
  })
})
