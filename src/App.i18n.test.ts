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

  it('opens global search idempotently with Cmd/Ctrl+K', () => {
    const shortcutBranch = appSource.match(
      /if \(e\.key === 'k'[\s\S]*?\{([\s\S]*?)\n\s*return\n\s*\}/
    )?.[1]
    const searchTrigger = appSource.match(
      /function triggerFocusSearch\(\) \{([\s\S]*?)\n\}/
    )?.[1]

    expect(shortcutBranch).toContain('e.preventDefault()')
    expect(shortcutBranch).toContain('triggerFocusSearch()')
    expect(shortcutBranch).not.toContain('commandPaletteOpen.value = !commandPaletteOpen.value')
    expect(searchTrigger).toContain('commandPaletteOpen.value = false')
    expect(searchTrigger).toContain('globalSearchOpen.value = true')
  })

  it('keeps one search surface in the app shell and opens document results by document route', () => {
    expect(appSource.match(/<GlobalSearchModal/g)).toHaveLength(1)
    expect(appSource.indexOf('<GlobalSearchModal')).toBeLessThan(appSource.indexOf('<main class="main"'))
    expect(appSource).toContain("result.contentType === 'document'")
    expect(appSource).toContain('`/projects/${result.projectId}/documents/${result.resourceId}`')
  })

  it('navigates to a favorite task without waiting for detail-only preload', () => {
    const handlerStart = appSource.indexOf('function openFavoriteTask')
    const navigation = appSource.indexOf('void router.push(buildTaskRoute(taskId, targetProjectId))', handlerStart)

    expect(handlerStart).toBeGreaterThan(-1)
    expect(navigation).toBeGreaterThan(handlerStart)
    expect(appSource).not.toContain('preloadTaskDetail')
  })

  it('lets the board own task loading after a project switch', () => {
    const handler = appSource.match(/function selectProject\([\s\S]*?\n\}/)?.[0]

    expect(handler).toBeDefined()
    expect(handler).not.toContain('taskStore.fetchTasks()')
  })
})
