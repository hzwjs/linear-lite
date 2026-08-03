import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { documentApi } from '../services/api/documents'
import { useDocumentFavoriteStore } from './documentFavoriteStore'

vi.mock('../services/api/documents', () => ({
  documentApi: {
    listFavorites: vi.fn()
  }
}))

describe('documentFavoriteStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(documentApi.listFavorites).mockReset()
  })

  it('loads global document favorites', async () => {
    const favorite = {
      id: 12,
      projectId: 7,
      parentDocumentId: null,
      title: 'Guide',
      sortOrder: 0,
      version: 1,
      favorited: true,
      updatedAt: '2026-07-29T08:00:00'
    }
    vi.mocked(documentApi.listFavorites).mockResolvedValue([favorite])

    const store = useDocumentFavoriteStore()
    await store.fetchFavorites()

    expect(store.favorites).toEqual([favorite])
  })

  it('syncs favorite changes from the document editor', () => {
    const store = useDocumentFavoriteStore()
    const favorite = {
      id: 12,
      projectId: 7,
      parentDocumentId: null,
      title: 'Guide',
      sortOrder: 0,
      version: 1,
      favorited: true,
      updatedAt: '2026-07-29T08:00:00'
    }

    store.syncDocument(favorite)
    expect(store.favorites).toEqual([favorite])

    store.syncDocument({ ...favorite, favorited: false })
    expect(store.favorites).toEqual([])
  })
})
