import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import SidebarNavigation from './SidebarNavigation.vue'
import type { Project, Task } from '../types/domain'

function buildFavorite(overrides: Partial<Task> = {}): Task {
  return {
    id: 'ENG-1',
    title: 'Stabilize sidebar tokens',
    status: 'todo',
    priority: 'medium',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    projectId: 1,
    ...overrides
  }
}

function buildProject(overrides: Partial<Project> = {}): Project {
  return {
    id: 1,
    name: 'Core',
    identifier: 'CORE',
    creatorId: 1,
    createdAt: new Date().toISOString(),
    ...overrides
  }
}

describe('SidebarNavigation', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('renders a reopen affordance while hidden and emits show-sidebar', async () => {
    const onShowSidebar = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(SidebarNavigation, {
      hidden: true,
      userName: 'Alice',
      userInitial: 'A',
      locale: 'en',
      favoritesCollapsed: false,
      projectsCollapsed: false,
      favorites: [],
      projects: [],
      routePath: '/',
      routeTaskId: null,
      activeProjectId: null,
      onShowSidebar
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const reopen = host.querySelector('.sidebar-nav__reopen') as HTMLButtonElement
    expect(reopen).toBeTruthy()

    reopen.click()
    expect(onShowSidebar).toHaveBeenCalledTimes(1)

    app.unmount()
    host.remove()
  })

  it('emits the new navigation contract without cross-triggering project selection', async () => {
    const onToggleFavoritesCollapsed = vi.fn()
    const onOpenFavoriteTask = vi.fn()
    const onOpenAnalytics = vi.fn()
    const onToggleProjectsCollapsed = vi.fn()
    const onCreateProject = vi.fn()
    const onSelectProject = vi.fn()
    const onOpenProjectSettings = vi.fn()

    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(SidebarNavigation, {
      hidden: false,
      userName: 'Alice',
      userInitial: 'A',
      locale: 'en',
      favoritesCollapsed: false,
      projectsCollapsed: false,
      favorites: [buildFavorite()],
      projects: [buildProject()],
      routePath: '/analytics',
      routeTaskId: 'ENG-1',
      activeProjectId: 1,
      onToggleFavoritesCollapsed,
      onOpenFavoriteTask,
      onOpenAnalytics,
      onToggleProjectsCollapsed,
      onCreateProject,
      onSelectProject,
      onOpenProjectSettings
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="favorite"]')).toBeTruthy()
    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="analytics"]')).toBeTruthy()
    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="project"]')).toBeTruthy()

    ;(host.querySelector('[data-testid="sidebar-favorites-toggle"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-favorite-ENG-1"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-analytics"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-projects-toggle"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-create-project"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-project-settings-1"]') as HTMLButtonElement).click()
    await nextTick()

    expect(onToggleFavoritesCollapsed).toHaveBeenCalledTimes(1)
    expect(onOpenFavoriteTask).toHaveBeenCalledWith('ENG-1', 1)
    expect(onOpenAnalytics).toHaveBeenCalledTimes(1)
    expect(onToggleProjectsCollapsed).toHaveBeenCalledTimes(1)
    expect(onCreateProject).toHaveBeenCalledTimes(1)
    expect(onOpenProjectSettings).toHaveBeenCalledWith(1)
    expect(onSelectProject).not.toHaveBeenCalled()

    app.unmount()
    host.remove()
  })
})
