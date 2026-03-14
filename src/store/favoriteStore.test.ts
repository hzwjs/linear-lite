import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFavoriteStore } from './favoriteStore'
import { taskApi } from '../services/api/task'
import { useTaskStore } from './taskStore'
import type { Task } from '../types/domain'

vi.mock('../services/api/task', () => ({
  taskApi: {
    listFavorites: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn()
  }
}))

describe('favoriteStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(taskApi.listFavorites).mockReset()
    vi.mocked(taskApi.addFavorite).mockReset()
    vi.mocked(taskApi.removeFavorite).mockReset()
  })

  it('loads favorites from API', async () => {
    vi.mocked(taskApi.listFavorites).mockResolvedValue([
      {
        id: 'ENG-2',
        numericId: 2,
        title: 'Favorited issue',
        status: 'todo',
        priority: 'medium',
        createdAt: 1,
        updatedAt: 1,
        favorited: true
      }
    ])

    const store = useFavoriteStore()
    await store.fetchFavorites()

    expect(store.favorites).toHaveLength(1)
    expect(store.isFavorite('ENG-2')).toBe(true)
  })

  it('toggleFavorite adds and removes favorite while syncing task store state', async () => {
    const favoriteStore = useFavoriteStore()
    const taskStore = useTaskStore()
    const baseTask: Task = {
      id: 'ENG-3',
      numericId: 3,
      title: 'Favorite me',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 1,
      favorited: false
    }
    taskStore.tasks = [baseTask]

    vi.mocked(taskApi.addFavorite).mockResolvedValue({
      ...baseTask,
      favorited: true
    })
    vi.mocked(taskApi.removeFavorite).mockResolvedValue({
      ...baseTask,
      favorited: false
    })

    await favoriteStore.toggleFavorite(baseTask)

    expect(favoriteStore.isFavorite('ENG-3')).toBe(true)
    expect(taskStore.tasks[0]?.favorited).toBe(true)

    await favoriteStore.toggleFavorite({ ...baseTask, favorited: true })

    expect(favoriteStore.isFavorite('ENG-3')).toBe(false)
    expect(taskStore.tasks[0]?.favorited).toBe(false)
  })
})
