import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import SidebarNavigation from './SidebarNavigation.vue'
import type { Project, Task } from '../types/domain'
import type { ProjectDocumentTreeNode } from '../types/document'

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

function buildFavoriteDocument(overrides: Partial<ProjectDocumentTreeNode> = {}): ProjectDocumentTreeNode {
  return {
    id: 42,
    projectId: 1,
    parentDocumentId: null,
    title: 'Document favorite',
    sortOrder: 0,
    version: 1,
    favorited: true,
    updatedAt: '2026-07-29T08:00:00',
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
      favoriteDocuments: [],
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

  it('renders a full-width global search row and preserves the focus-search event', async () => {
    const onFocusSearch = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(SidebarNavigation, {
      hidden: false,
      userName: 'Alice',
      userInitial: 'A',
      locale: 'en',
      favoritesCollapsed: false,
      projectsCollapsed: false,
      favorites: [],
      favoriteDocuments: [],
      projects: [],
      routePath: '/',
      routeTaskId: null,
      activeProjectId: null,
      onFocusSearch
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const search = host.querySelector('[data-testid="sidebar-global-search"]') as HTMLButtonElement
    expect(search).toBeTruthy()
    expect(search.classList.contains('sidebar-nav__item')).toBe(true)
    expect(search.textContent).toContain('Global search')
    expect(search.querySelector('.sidebar-nav__shortcut')?.textContent).toBe('⌘K')
    expect(search.getAttribute('aria-label')).toBe('Global search (⌘K)')
    expect(search.getAttribute('title')).toBe('Global search (⌘K)')
    expect(host.querySelector('.sidebar-nav__header [data-testid="sidebar-global-search"]')).toBeNull()

    search.click()
    expect(onFocusSearch).toHaveBeenCalledOnce()

    i18n.global.locale.value = 'zh-CN'
    await nextTick()
    expect(search.textContent).toContain('全局搜索')
    expect(search.getAttribute('aria-label')).toBe('全局搜索（⌘K）')
    expect(search.getAttribute('title')).toBe('全局搜索（⌘K）')

    app.unmount()
    host.remove()
  })

  it('emits the new navigation contract without cross-triggering project selection', async () => {
    const onToggleFavoritesCollapsed = vi.fn()
    const onOpenFavoriteTask = vi.fn()
    const onOpenFavoriteDocument = vi.fn()
    const onOpenAnalytics = vi.fn()
    const onToggleProjectsCollapsed = vi.fn()
    const onReorderProjects = vi.fn()
    const onCreateProject = vi.fn()
    const onSelectProject = vi.fn()
    const onOpenProjectDocuments = vi.fn()
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
      favoriteDocuments: [buildFavoriteDocument()],
      projects: [buildProject(), buildProject({ id: 2, name: 'Design', identifier: 'DES' })],
      routePath: '/analytics',
      routeTaskId: 'ENG-1',
      activeProjectId: 1,
      onToggleFavoritesCollapsed,
      onOpenFavoriteTask,
      onOpenFavoriteDocument,
      onOpenAnalytics,
      onToggleProjectsCollapsed,
      onReorderProjects,
      onCreateProject,
      onSelectProject,
      onOpenProjectDocuments,
      onOpenProjectSettings
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="favorite"]')).toBeTruthy()
    expect(
      host
        .querySelector('[data-testid="sidebar-favorite-ENG-1"] .sidebar-nav__item-icon')
        ?.classList.contains('sidebar-nav__item-icon--status-todo')
    ).toBe(true)
    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="analytics"]')).toBeTruthy()
    expect(host.querySelector('.sidebar-nav__item--active[data-item-kind="project"]')).toBeTruthy()

    const dragStart = new Event('dragstart', { bubbles: true }) as DragEvent
    Object.defineProperty(dragStart, 'dataTransfer', {
      value: { effectAllowed: '', setData: vi.fn() }
    })
    const dragOver = new Event('dragover', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(dragOver, 'dataTransfer', {
      value: { dropEffect: '' }
    })
    Object.defineProperty(dragOver, 'clientY', { value: 10 })
    const drop = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent
    const targetProject = host.querySelector('[data-testid="sidebar-project-2"]') as HTMLElement
    targetProject.getBoundingClientRect = () => ({
      top: 0,
      height: 10,
      bottom: 10,
      left: 0,
      right: 100,
      width: 100,
      x: 0,
      y: 0,
      toJSON: () => ({})
    }) as DOMRect
    ;(host.querySelector('[data-testid="sidebar-project-1"]') as HTMLElement).dispatchEvent(dragStart)
    targetProject.dispatchEvent(dragOver)
    targetProject.dispatchEvent(drop)

    ;(host.querySelector('[data-testid="sidebar-favorites-toggle"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-favorite-ENG-1"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-favorite-document-42"]') as HTMLButtonElement).click()
    await new Promise((resolve) => setTimeout(resolve, 40))
    ;(host.querySelector('[data-testid="sidebar-analytics"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-projects-toggle"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-create-project"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="sidebar-project-settings-1"]') as HTMLButtonElement).click()
    await nextTick()

    expect(onToggleFavoritesCollapsed).toHaveBeenCalledTimes(1)
    expect(onOpenFavoriteTask).toHaveBeenCalledWith('ENG-1', 1)
    expect(onOpenFavoriteDocument).toHaveBeenCalledWith(42, 1)
    expect(onOpenAnalytics).toHaveBeenCalledTimes(1)
    expect(onToggleProjectsCollapsed).toHaveBeenCalledTimes(1)
    expect(onReorderProjects).toHaveBeenCalledWith([2, 1])
    expect(onCreateProject).toHaveBeenCalledTimes(1)
    expect(onOpenProjectSettings).toHaveBeenCalledWith(1)
    expect(onSelectProject).not.toHaveBeenCalled()
    expect(onOpenProjectDocuments).not.toHaveBeenCalled()

    app.unmount()
    host.remove()
  })

  it('expands only the active project and exposes task and document child navigation', async () => {
    const onSelectProject = vi.fn()
    const onOpenProjectDocuments = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(SidebarNavigation, {
      hidden: false,
      userName: 'Alice',
      userInitial: 'A',
      locale: 'en',
      favoritesCollapsed: false,
      projectsCollapsed: false,
      favorites: [],
      favoriteDocuments: [],
      projects: [buildProject(), buildProject({ id: 2, name: 'Design', identifier: 'DES' })],
      routePath: '/projects/1/documents/42',
      routeTaskId: null,
      activeProjectId: 1,
      onSelectProject,
      onOpenProjectDocuments
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const project = host.querySelector('[data-testid="sidebar-project-1"]') as HTMLElement
    const tasks = host.querySelector('[data-testid="sidebar-project-tasks-1"]') as HTMLButtonElement
    const documents = host.querySelector('[data-testid="sidebar-project-documents-1"]') as HTMLButtonElement

    expect(project.classList.contains('sidebar-nav__item--active')).toBe(true)
    expect(project.querySelector('.sidebar-nav__item-main')?.getAttribute('aria-expanded')).toBe('true')
    expect(tasks).toBeTruthy()
    expect(documents.getAttribute('aria-current')).toBe('page')
    expect(tasks.getAttribute('aria-current')).toBeNull()
    expect(host.querySelector('[data-testid="sidebar-project-tasks-2"]')).toBeNull()
    expect(host.querySelector('[data-testid="sidebar-project-documents-2"]')).toBeNull()

    tasks.click()
    documents.click()
    expect(onSelectProject).toHaveBeenCalledWith(1)
    expect(onOpenProjectDocuments).toHaveBeenCalledWith(1)

    app.unmount()
    host.remove()
  })

  it('renders the brand header without the workspace subtitle', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(SidebarNavigation, {
      hidden: false,
      userName: 'Alice',
      userInitial: 'A',
      locale: 'en',
      favoritesCollapsed: false,
      projectsCollapsed: false,
      favorites: [],
      favoriteDocuments: [],
      projects: [],
      routePath: '/',
      routeTaskId: null,
      activeProjectId: null
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.sidebar-nav__brand-name')?.textContent).toBe('Linear Lite')
    expect(host.querySelector('.sidebar-nav__brand-meta')).toBeNull()
    expect(host.querySelector('.sidebar-nav__header')?.textContent).not.toContain('Workspace')

    const identity = host.querySelector('.sidebar-nav__identity') as HTMLButtonElement
    expect(identity.getAttribute('aria-expanded')).toBe('false')
    identity.click()
    await nextTick()
    expect(identity.getAttribute('aria-expanded')).toBe('true')
    expect((host.querySelector('.sidebar-nav__menu') as HTMLElement).style.display).not.toBe('none')

    app.unmount()
    host.remove()
  })
})
