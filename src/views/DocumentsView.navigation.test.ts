import { createPinia } from 'pinia'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import { projectApi } from '../services/api/project'
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
      get: vi.fn()
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
  { id: 1, projectId: 7, parentDocumentId: null, title: 'First', sortOrder: 0, version: 1, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: null, title: 'Second', sortOrder: 1, version: 1, updatedAt: '2026-07-29T08:00:00' }
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
    vi.mocked(projectApi.listMembers).mockReset().mockResolvedValue([])
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
})
