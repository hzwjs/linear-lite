import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectStore } from './projectStore'
import { projectApi } from '../services/api/project'

vi.mock('../services/api/project', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn()
  }
}))

describe('projectStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(projectApi.list).mockReset()
    vi.mocked(projectApi.create).mockReset()
    vi.mocked(projectApi.update).mockReset()
    vi.mocked(projectApi.delete).mockReset()
  })

  it('switches to the first remaining project when deleting the active project', async () => {
    const store = useProjectStore()
    store.projects = [
      { id: 1, name: 'Engineering', identifier: 'ENG', creatorId: 7, createdAt: '2026-03-14T00:00:00' },
      { id: 2, name: 'Design', identifier: 'DES', creatorId: 8, createdAt: '2026-03-14T00:00:00' }
    ]
    store.activeProjectId = 1

    vi.mocked(projectApi.delete).mockResolvedValue(undefined)

    await store.deleteProject(1)

    expect(store.projects.map((project) => project.id)).toEqual([2])
    expect(store.activeProjectId).toBe(2)
  })

  it('clears the active project when deleting the last project', async () => {
    const store = useProjectStore()
    store.projects = [
      { id: 1, name: 'Engineering', identifier: 'ENG', creatorId: 7, createdAt: '2026-03-14T00:00:00' }
    ]
    store.activeProjectId = 1

    vi.mocked(projectApi.delete).mockResolvedValue(undefined)

    await store.deleteProject(1)

    expect(store.projects).toEqual([])
    expect(store.activeProjectId).toBeNull()
  })
})
