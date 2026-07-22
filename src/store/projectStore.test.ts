import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectStore } from './projectStore'
import { projectApi } from '../services/api/project'

vi.mock('../services/api/project', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    invite: vi.fn(),
    reorder: vi.fn()
  }
}))

describe('projectStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(projectApi.list).mockReset()
    vi.mocked(projectApi.create).mockReset()
    vi.mocked(projectApi.update).mockReset()
    vi.mocked(projectApi.delete).mockReset()
    vi.mocked(projectApi.invite).mockReset()
    vi.mocked(projectApi.reorder).mockReset()
  })

  it('selects the first visible project after fetching filtered list', async () => {
    vi.mocked(projectApi.list).mockResolvedValue([
      { id: 2, name: 'Design', identifier: 'DES', creatorId: 8, createdAt: '2026-03-14T00:00:00' }
    ])

    const store = useProjectStore()
    store.activeProjectId = 999

    await store.fetchProjects()

    expect(store.projects.map((project) => project.id)).toEqual([2])
    expect(store.activeProjectId).toBe(2)
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

  it('invites a user by email through the project api', async () => {
    vi.mocked(projectApi.invite).mockResolvedValue(undefined)

    const store = useProjectStore()
    await store.inviteToProject(3, 'new@example.com')

    expect(projectApi.invite).toHaveBeenCalledWith(3, { email: 'new@example.com' })
  })

  it('optimistically reorders projects and persists the complete project id list', async () => {
    const store = useProjectStore()
    store.projects = [
      { id: 1, name: 'Engineering', identifier: 'ENG', creatorId: 7, createdAt: '2026-03-14T00:00:00' },
      { id: 2, name: 'Design', identifier: 'DES', creatorId: 8, createdAt: '2026-03-14T00:00:00' }
    ]
    vi.mocked(projectApi.reorder).mockResolvedValue(undefined)

    await store.reorderProjects([2, 1])

    expect(store.projects.map((project) => project.id)).toEqual([2, 1])
    expect(projectApi.reorder).toHaveBeenCalledWith([2, 1])
  })

  it('rolls back the optimistic order when persistence fails', async () => {
    const store = useProjectStore()
    store.projects = [
      { id: 1, name: 'Engineering', identifier: 'ENG', creatorId: 7, createdAt: '2026-03-14T00:00:00' },
      { id: 2, name: 'Design', identifier: 'DES', creatorId: 8, createdAt: '2026-03-14T00:00:00' }
    ]
    vi.mocked(projectApi.reorder).mockRejectedValue(new Error('network'))

    await expect(store.reorderProjects([2, 1])).rejects.toThrow('network')
    expect(store.projects.map((project) => project.id)).toEqual([1, 2])
  })
})
