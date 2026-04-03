import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from './i18n'
import { useLocaleStore } from './store/localeStore'
import appSource from './App.vue?raw'

describe('shell translations', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
  })

  it('wires the app shell through i18n and locale store', () => {
    expect(appSource).toContain("const { t } = useI18n()")
    expect(appSource).toContain("{{ t('sidebar.favorites') }}")
    expect(appSource).toContain("{{ t('sidebar.projects') }}")
    expect(appSource).toContain("{{ t('sidebar.signOut') }}")
    expect(appSource).toContain("@click=\"localeStore.setLocale('zh-CN')\"")
    expect(appSource).toContain("@click=\"localeStore.setLocale('en')\"")
  })

  it('reflects locale changes for sidebar labels', () => {
    const store = useLocaleStore()
    expect(i18n.global.t('sidebar.favorites')).toBe('Favorites')

    store.setLocale('zh-CN')

    expect(i18n.global.t('sidebar.favorites')).toBe('收藏')
  })
})
