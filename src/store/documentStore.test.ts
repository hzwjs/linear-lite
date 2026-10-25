import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi } from '../services/api/documentCollaboration'
import type { DocumentTitleUpdate } from '../services/api/documentCollaboration'
import type { ProjectDocument } from '../types/document'
import { useDocumentStore } from './documentStore'

vi.mock('../services/api/documents', async () => {
  const actual = await vi.importActual<typeof import('../services/api/documents')>('../services/api/documents')
  return {
    ...actual,
    documentApi: {
      listTree: vi.fn(), listArchive: vi.fn(), create: vi.fn(), get: vi.fn(), update: vi.fn(),
      move: vi.fn(), archive: vi.fn(), restore: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn(),
      listRevisions: vi.fn(), getRevision: vi.fn(), restoreRevision: vi.fn()
    }
  }
})
vi.mock('../services/api/documentCollaboration', () => ({
  documentCollaborationApi: { createSession: vi.fn(), updateTitle: vi.fn() }
}))

function titleUpdate(title: string, version: number): DocumentTitleUpdate {
  return { title, version, updatedAt: '2026-10-17T08:00:00', lastEditorId: 1 }
}

function document(overrides: Partial<ProjectDocument> = {}): ProjectDocument {
  return {
    id: 8, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Spec', content: '[]',
    sortOrder: 0, version: 1, favorited: false, creatorId: 1, lastEditorId: 1, archivedAt: null,
    createdAt: '2026-07-29T08:00:00', updatedAt: '2026-07-29T08:00:00', ...overrides
  }
}

describe('documentStore title saves', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.mocked(documentApi.update).mockReset()
    vi.mocked(documentCollaborationApi.updateTitle).mockReset()
    vi.mocked(documentApi.get).mockReset()
    vi.mocked(documentApi.move).mockReset()
    vi.mocked(documentApi.listTree).mockReset()
    vi.mocked(documentApi.addFavorite).mockReset()
    vi.mocked(documentApi.removeFavorite).mockReset()
  })

  it('keeps a paused title draft local until commit and serializes a newer committed title', async () => {
    let resolveFirst!: (value: DocumentTitleUpdate) => void
    vi.mocked(documentCollaborationApi.updateTitle)
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(titleUpdate('Second', 3))
    vi.mocked(documentApi.get).mockResolvedValue(document())
    const store = useDocumentStore()
    await store.loadDocument(8)

    store.updateDraft({ title: 'First' })
    await vi.advanceTimersByTimeAsync(800)
    expect(documentCollaborationApi.updateTitle).not.toHaveBeenCalled()
    expect(store.saveState).toBe('dirty')

    const saving = store.saveNow()
    expect(documentCollaborationApi.updateTitle).toHaveBeenNthCalledWith(1, 8, 'Spec', 'First')

    store.updateDraft({ title: 'Second' })
    void store.saveNow()
    resolveFirst(titleUpdate('First', 2))
    await saving

    expect(documentCollaborationApi.updateTitle).toHaveBeenNthCalledWith(2, 8, 'First', 'Second')
    expect(store.activeDocument?.version).toBe(3)
    expect(store.saveState).toBe('saved')
  })

  it('stops writes after a version conflict and preserves the local draft', async () => {
    vi.mocked(documentCollaborationApi.updateTitle).mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { data: { currentVersion: 4 } } }
    })
    const store = useDocumentStore()
    vi.mocked(documentApi.get).mockResolvedValue(document())
    await store.loadDocument(8)

    store.updateDraft({ title: 'Local draft' })
    await store.saveNow()

    expect(store.saveState).toBe('conflict')
    expect(store.conflictVersion).toBe(4)
    expect(store.activeDocument?.title).toBe('Local draft')
    store.updateDraft({ title: 'Blocked edit' })
    expect(documentCollaborationApi.updateTitle).toHaveBeenCalledTimes(1)
  })

  it('does not commit a draft with an empty title', async () => {
    vi.mocked(documentCollaborationApi.updateTitle).mockResolvedValue(titleUpdate('   ', 2))
    const store = useDocumentStore()
    store.activeDocument = document()

    store.updateDraft({ title: '   ' })
    await store.saveNow()

    expect(documentCollaborationApi.updateTitle).not.toHaveBeenCalled()
    expect(store.saveState).toBe('invalid')
    expect(store.error).toBeNull()
  })

  it('does not save when leaving an unchanged document', async () => {
    const store = useDocumentStore()
    store.activeDocument = document()
    store.saveState = 'saved'

    await store.flushSaves()

    expect(documentCollaborationApi.updateTitle).not.toHaveBeenCalled()
  })

  it('flushes a dirty document before leaving without a client revision flag', async () => {
    vi.mocked(documentCollaborationApi.updateTitle).mockResolvedValue(titleUpdate('Local draft', 2))
    const store = useDocumentStore()
    vi.mocked(documentApi.get).mockResolvedValue(document())
    await store.loadDocument(8)

    store.updateDraft({ title: 'Local draft' })
    await store.flushSaves()

    expect(documentCollaborationApi.updateTitle).toHaveBeenCalledWith(8, 'Spec', 'Local draft')
  })

  it('keeps save failures out of the document tree error state', async () => {
    vi.mocked(documentCollaborationApi.updateTitle).mockRejectedValue(new Error('Network unavailable'))
    const store = useDocumentStore()
    vi.mocked(documentApi.get).mockResolvedValue(document())
    await store.loadDocument(8)

    store.updateDraft({ title: 'Draft' })
    await store.saveNow()

    expect(store.saveState).toBe('failed')
    expect(store.error).toBe('Network unavailable')
    expect(store.treeError).toBeNull()
  })

  it('removes stale content while a replacement document fails to load', async () => {
    vi.mocked(documentApi.get).mockRejectedValue(new Error('Not found'))
    const store = useDocumentStore()
    store.activeDocument = document()

    await expect(store.loadDocument(99)).rejects.toThrow('Not found')

    expect(store.activeDocument).toBeNull()
    expect(store.error).toBe('Not found')
  })

  it('persists a favorite and synchronizes the active document with the tree', async () => {
    const favorited = document({ favorited: true })
    vi.mocked(documentApi.addFavorite).mockResolvedValue(favorited)
    const store = useDocumentStore()
    store.activeDocument = document()
    store.treeNodes = [document()]

    await store.toggleFavorite(store.activeDocument)

    expect(documentApi.addFavorite).toHaveBeenCalledWith(8)
    expect(store.activeDocument?.favorited).toBe(true)
    expect(store.treeNodes[0]?.favorited).toBe(true)
    expect(store.favoritePendingIds.size).toBe(0)
  })

  it('reloads the complete project tree after moving a document', async () => {
    const refreshedTree = [
      { id: 8, projectId: 3, parentDocumentId: 9, title: 'Spec', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ]
    vi.mocked(documentApi.move).mockResolvedValue(undefined)
    vi.mocked(documentApi.listTree).mockResolvedValue(refreshedTree)
    const store = useDocumentStore()
    store.treeNodes = [
      { id: 8, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Spec', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 9, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Parent', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ]

    await store.moveDocument(3, 8, 9, null)

    expect(documentApi.move).toHaveBeenCalledWith(8, { parentDocumentId: 9, previousSiblingId: null })
    expect(documentApi.listTree).toHaveBeenCalledWith(3)
    expect(store.treeNodes).toEqual(refreshedTree)
    expect(store.treeSnapshotVersion).toBe(1)
  })

  it('reorders the local tree before the move request resolves', async () => {
    let resolveMove!: () => void
    vi.mocked(documentApi.move).mockReturnValue(new Promise<void>((resolve) => {
      resolveMove = resolve
    }))
    vi.mocked(documentApi.listTree).mockResolvedValue([
      { id: 9, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Second', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 8, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Spec', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ])
    const store = useDocumentStore()
    store.treeNodes = [
      { id: 8, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Spec', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 9, projectId: 3, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Second', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ]

    const movePromise = store.moveDocument(3, 8, null, 9)

    expect([...store.treeNodes].sort((a, b) => a.sortOrder - b.sortOrder).map((node) => node.id)).toEqual([9, 8])
    expect(documentApi.listTree).not.toHaveBeenCalled()

    resolveMove()
    await movePromise
  })
})
