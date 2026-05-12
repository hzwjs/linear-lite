import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from './i18n'
import { useLocaleStore } from './store/localeStore'
import appSource from './App.vue?raw'
import sidebarNavigationSource from './components/SidebarNavigation.vue?raw'

describe('shell translations', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
  })

  it('wires the app shell through i18n and locale store', () => {
    expect(appSource).toContain("const { t } = useI18n()")
    expect(appSource).toContain('<SidebarNavigation')
    expect(sidebarNavigationSource).toContain("{{ t('sidebar.favorites') }}")
    expect(sidebarNavigationSource).toContain("{{ t('sidebar.projects') }}")
    expect(sidebarNavigationSource).toContain("{{ t('sidebar.signOut') }}")
    expect(sidebarNavigationSource).toContain("emit('set-locale', 'zh-CN')")
    expect(sidebarNavigationSource).toContain("emit('set-locale', 'en')")
  })

  it('reflects locale changes for sidebar labels', () => {
    const store = useLocaleStore()
    expect(i18n.global.t('sidebar.favorites')).toBe('Favorites')

    store.setLocale('zh-CN')

    expect(i18n.global.t('sidebar.favorites')).toBe('收藏')
  })
})
