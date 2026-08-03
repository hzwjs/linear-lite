import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { documentApi } from '../services/api/documents'
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

function document(overrides: Partial<ProjectDocument> = {}): ProjectDocument {
  return {
    id: 8, projectId: 3, parentDocumentId: null, title: 'Spec', content: '[]',
    sortOrder: 0, version: 1, favorited: false, creatorId: 1, lastEditorId: 1, archivedAt: null,
    createdAt: '2026-07-29T08:00:00', updatedAt: '2026-07-29T08:00:00', ...overrides
  }
}

describe('documentStore autosave', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.mocked(documentApi.update).mockReset()
    vi.mocked(documentApi.get).mockReset()
    vi.mocked(documentApi.move).mockReset()
    vi.mocked(documentApi.listTree).mockReset()
    vi.mocked(documentApi.addFavorite).mockReset()
    vi.mocked(documentApi.removeFavorite).mockReset()
  })

  it('serializes edits made while an update is in flight onto the acknowledged version', async () => {
    let resolveFirst!: (value: ProjectDocument) => void
    vi.mocked(documentApi.update)
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(document({ title: 'Second', version: 3 }))
    const store = useDocumentStore()
    store.activeDocument = document()

    store.updateDraft({ title: 'First' })
    await vi.advanceTimersByTimeAsync(800)
    expect(documentApi.update).toHaveBeenNthCalledWith(1, 8, {
      expectedVersion: 1, title: 'First', content: '[]'
    })

    store.updateDraft({ title: 'Second' })
    resolveFirst(document({ title: 'First', version: 2 }))
    await vi.runAllTimersAsync()

    expect(documentApi.update).toHaveBeenNthCalledWith(2, 8, {
      expectedVersion: 2, title: 'Second', content: '[]'
    })
    expect(store.activeDocument?.version).toBe(3)
    expect(store.saveState).toBe('saved')
  })

  it('stops writes on 409 and preserves the local draft', async () => {
    vi.mocked(documentApi.update).mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { data: { currentVersion: 4 } } }
    })
    const store = useDocumentStore()
    store.activeDocument = document()

    store.updateDraft({ title: 'Local draft' })
    await vi.advanceTimersByTimeAsync(800)

    expect(store.saveState).toBe('conflict')
    expect(store.conflictVersion).toBe(4)
    expect(store.activeDocument?.title).toBe('Local draft')
    store.updateDraft({ title: 'Blocked edit' })
    await vi.runAllTimersAsync()
    expect(documentApi.update).toHaveBeenCalledTimes(1)
  })

  it('does not autosave a draft with an empty title', async () => {
    vi.mocked(documentApi.update).mockResolvedValue(document({ title: '   ', version: 2 }))
    const store = useDocumentStore()
    store.activeDocument = document()

    store.updateDraft({ title: '   ' })
    await vi.advanceTimersByTimeAsync(800)

    expect(documentApi.update).not.toHaveBeenCalled()
    expect(store.saveState).toBe('invalid')
    expect(store.error).toBeNull()
  })

  it('keeps save failures out of the document tree error state', async () => {
    vi.mocked(documentApi.update).mockRejectedValue(new Error('Network unavailable'))
    const store = useDocumentStore()
    store.activeDocument = document()

    store.updateDraft({ title: 'Draft' })
    await vi.advanceTimersByTimeAsync(800)

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
      { id: 8, projectId: 3, parentDocumentId: null, title: 'Spec', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 9, projectId: 3, parentDocumentId: null, title: 'Parent', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
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
      { id: 9, projectId: 3, parentDocumentId: null, title: 'Second', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 8, projectId: 3, parentDocumentId: null, title: 'Spec', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ])
    const store = useDocumentStore()
    store.treeNodes = [
      { id: 8, projectId: 3, parentDocumentId: null, title: 'Spec', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
      { id: 9, projectId: 3, parentDocumentId: null, title: 'Second', sortOrder: 1, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
    ]

    const movePromise = store.moveDocument(3, 8, null, 9)

    expect([...store.treeNodes].sort((a, b) => a.sortOrder - b.sortOrder).map((node) => node.id)).toEqual([9, 8])
    expect(documentApi.listTree).not.toHaveBeenCalled()

    resolveMove()
    await movePromise
  })
})
