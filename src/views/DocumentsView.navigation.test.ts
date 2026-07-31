import { createPinia } from 'pinia'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import { projectApi } from '../services/api/project'
import { useDocumentStore } from '../store/documentStore'
import type { ProjectDocument, ProjectDocumentTreeNode } from '../types/document'
import DocumentsView from './DocumentsView.vue'

vi.mock('../components/documents/DocumentEditor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/documents/DocumentHistoryPanel.vue', () => ({ default: { template: '<div />' } }))

vi.mock('../services/api/documents', async () => {
  const actual = await vi.importActual<typeof import('../services/api/documents')>('../services/api/documents')
  return {
    ...actual,
    documentApi: {
      ...actual.documentApi,
      listTree: vi.fn(),
      get: vi.fn(),
      move: vi.fn(),
      search: vi.fn()
    }
  }
})

vi.mock('../services/api/project', async () => {
  const actual = await vi.importActual<typeof import('../services/api/project')>('../services/api/project')
  return {
    ...actual,
    projectApi: {
      ...actual.projectApi,
      listMembers: vi.fn()
    }
  }
})

const treeNodes: ProjectDocumentTreeNode[] = [
  { id: 1, projectId: 7, parentDocumentId: null, title: 'First', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: null, title: 'Second', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
]

function projectDocument(id: number): ProjectDocument {
  return {
    ...treeNodes[id - 1]!,
    content: '[]',
    creatorId: 1,
    lastEditorId: 1,
    archivedAt: null,
    createdAt: '2026-07-29T08:00:00'
  }
}

async function flushNavigation() {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

describe('DocumentsView navigation', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    vi.mocked(documentApi.listTree).mockReset().mockResolvedValue(treeNodes)
    vi.mocked(documentApi.get).mockReset().mockImplementation(async (id) => projectDocument(id))
    vi.mocked(documentApi.move).mockReset().mockResolvedValue(undefined)
    vi.mocked(documentApi.search).mockReset().mockResolvedValue([])
    vi.mocked(projectApi.listMembers).mockReset().mockResolvedValue([])
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('navigates between documents without reloading the document tree', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:projectId/documents/:documentId', component: DocumentsView }
      ]
    })
    await router.push('/projects/7/documents/1')
    await router.isReady()

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({ template: '<RouterView />' })
    app.use(createPinia())
    app.use(router)
    app.use(i18n)
    app.mount(host)
    await flushNavigation()

    expect(documentApi.listTree).toHaveBeenCalledTimes(1)
    ;(host.querySelector('[data-document-tree-id="2"]') as HTMLButtonElement).click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await flushNavigation()

    expect(router.currentRoute.value.params.documentId).toBe('2')
    expect(documentApi.get).toHaveBeenLastCalledWith(2)
    expect(documentApi.listTree).toHaveBeenCalledTimes(1)
    app.unmount()
  })

  it('starts loading the routed document without waiting for the tree', async () => {
    let resolveTree!: (nodes: ProjectDocumentTreeNode[]) => void
    let resolveDocument!: (document: ProjectDocument) => void
    vi.mocked(documentApi.listTree).mockReturnValue(new Promise((resolve) => {
      resolveTree = resolve
    }))
    vi.mocked(documentApi.get).mockReturnValue(new Promise((resolve) => {
      resolveDocument = resolve
    }))

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:projectId/documents/:documentId', component: DocumentsView }
      ]
    })
    await router.push('/projects/7/documents/1')
    await router.isReady()

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({ template: '<RouterView />' })
    app.use(createPinia())
    app.use(router)
    app.use(i18n)
    app.mount(host)
    await flushNavigation()

    // 带文档 ID 的路由必须立即请求正文，不能被文档树的慢查询串行阻塞。
    expect(documentApi.get).toHaveBeenCalledWith(1)
    expect(host.querySelector('.documents-content .spin')).not.toBeNull()

    resolveDocument(projectDocument(1))
    resolveTree(treeNodes)
    await flushNavigation()
    app.unmount()
  })

  it('keeps the current tree mounted while a moved tree snapshot is loading, then recreates it', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/projects/:projectId/documents/:documentId', component: DocumentsView }
      ]
    })
    await router.push('/projects/7/documents/1')
    await router.isReady()

    const host = document.createElement('div')
    document.body.appendChild(host)
    const pinia = createPinia()
    const app = createApp({ template: '<RouterView />' })
    app.use(pinia)
    app.use(router)
    app.use(i18n)
    app.mount(host)
    await flushNavigation()
    const firstTreeInstance = host.querySelector('.document-tree')

    let resolveMovedTree!: (nodes: ProjectDocumentTreeNode[]) => void
    vi.mocked(documentApi.listTree).mockReturnValueOnce(new Promise((resolve) => {
      resolveMovedTree = resolve
    }))
    const store = useDocumentStore(pinia)
    const movePromise = store.moveDocument(7, 1, null, 2)
    await flushNavigation()

    // 放下后的服务端同步在后台完成，期间不能卸载用户正在操作的整棵树。
    expect(store.loadingTree).toBe(false)
    expect(host.querySelector('.document-tree')).toBe(firstTreeInstance)
    expect(Array.from(host.querySelectorAll('[role="tree"] > [role="treeitem"] [data-document-tree-id]'))
      .map((element) => Number((element as HTMLElement).dataset.documentTreeId)))
      .toEqual([2, 1])

    resolveMovedTree(treeNodes)
    await movePromise
    await flushNavigation()

    expect(documentApi.listTree).toHaveBeenCalledTimes(2)
    expect(host.querySelector('.document-tree')).not.toBe(firstTreeInstance)
    app.unmount()
  })

  it('debounces server search, replaces the tree with flat results, and clears search after navigation', async () => {
    vi.useFakeTimers()
    vi.mocked(documentApi.search).mockResolvedValue([{
      contentType: 'document', resourceId: '2', projectId: 7, projectIdentifier: 'JLNX', projectName: 'JLNX',
      title: 'Security guide', excerpt: 'Review the security checklist'
    }])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:projectId/documents/:documentId', component: DocumentsView }]
    })
    await router.push('/projects/7/documents/1')
    await router.isReady()

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({ template: '<RouterView />' })
    app.use(createPinia())
    app.use(router)
    app.use(i18n)
    app.mount(host)
    await flushNavigation()

    const input = host.querySelector('[aria-label="搜索当前项目文档"]') as HTMLInputElement
    input.value = 'security'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    expect(host.querySelector('.document-tree')).toBeNull()
    expect(documentApi.search).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(249)
    expect(documentApi.search).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await flushNavigation()

    expect(documentApi.search).toHaveBeenCalledWith(7, 'security')
    expect(host.querySelector('.document-search-results__path')?.textContent).toBe('文档')
    expect(host.textContent).toContain('Review the security checklist')
    ;(host.querySelector('.document-search-results li button') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(router.currentRoute.value.params.documentId).toBe('2'))
    await flushNavigation()

    expect(input.value).toBe('')
    expect(host.querySelector('.document-tree')).not.toBeNull()
    app.unmount()
  })

  it('ignores stale responses and cancels a pending search when unmounted', async () => {
    vi.useFakeTimers()
    let resolveFirst!: (value: Awaited<ReturnType<typeof documentApi.search>>) => void
    vi.mocked(documentApi.search)
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce([{
        contentType: 'document', resourceId: '2', projectId: 7, projectIdentifier: 'JLNX', projectName: 'JLNX',
        title: 'Newest result', excerpt: 'second query'
      }])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:projectId/documents/:documentId', component: DocumentsView }]
    })
    await router.push('/projects/7/documents/1')
    await router.isReady()

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({ template: '<RouterView />' })
    app.use(createPinia())
    app.use(router)
    app.use(i18n)
    app.mount(host)
    await flushNavigation()

    const input = host.querySelector('[aria-label="搜索当前项目文档"]') as HTMLInputElement
    input.value = 'first'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    input.value = 'second'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushNavigation()
    expect(host.textContent).toContain('Newest result')

    resolveFirst([{
      contentType: 'document', resourceId: '1', projectId: 7, projectIdentifier: 'JLNX', projectName: 'JLNX',
      title: 'Stale result', excerpt: 'first query'
    }])
    await flushNavigation()
    expect(host.textContent).not.toContain('Stale result')

    input.value = 'pending'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    app.unmount()
    await vi.advanceTimersByTimeAsync(250)
    expect(documentApi.search).toHaveBeenCalledTimes(2)
  })
})
