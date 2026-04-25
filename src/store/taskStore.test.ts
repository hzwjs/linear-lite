import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectStore } from './projectStore'
import { useTaskStore } from './taskStore'
import { taskApi } from '../services/api/task'
import type { Task } from '../types/domain'

vi.mock('../services/api/task', () => ({
  taskApi: {
    get: vi.fn(),
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn()
  }
}))

describe('taskStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(taskApi.list).mockReset()
    vi.mocked(taskApi.get).mockReset()
    vi.mocked(taskApi.create).mockReset()
    vi.mocked(taskApi.update).mockReset()
  })

  it('updates parent sub-issue progress immediately after a child transitions to done', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    const store = useTaskStore()
    const parentTask = {
      id: 'ENG-1',
      numericId: 101,
      title: 'Parent',
      status: 'todo' as const,
      priority: 'medium' as const,
      createdAt: 1,
      updatedAt: 1,
      subIssueCount: 1,
      completedSubIssueCount: 0
    }
    const childTask = {
      id: 'ENG-2',
      numericId: 102,
      title: 'Child',
      status: 'todo' as const,
      priority: 'medium' as const,
      createdAt: 1,
      updatedAt: 1,
      parentId: '101'
    }
    store.tasks = [parentTask, childTask]

    vi.mocked(taskApi.update).mockResolvedValue({
      ...childTask,
      status: 'done',
      updatedAt: 2,
      completedAt: 2
    })

    await store.transitionTask('ENG-2', 'done')

    expect(store.tasks.find((task) => task.id === 'ENG-1')).toMatchObject({
      subIssueCount: 1,
      completedSubIssueCount: 1
    })
  })

  it('serializes due date as local midnight when updating unrelated fields', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-3',
      numericId: 103,
      title: 'Task',
      status: 'todo',
      priority: 'medium',
      assigneeId: 1,
      dueDate: new Date('2026-03-10T00:00:00+08:00').getTime(),
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]

    vi.mocked(taskApi.update).mockResolvedValue({
      ...task,
      assigneeId: 2,
      updatedAt: 2
    })

    await store.updateTask('ENG-3', {
      assigneeId: 2,
      dueDate: task.dueDate
    })

    expect(taskApi.update).toHaveBeenCalledWith('ENG-3', expect.objectContaining({
      dueDate: '2026-03-10T00:00:00'
    }))
  })

  it('applies local task patch before API resolves', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-4',
      numericId: 104,
      title: 'Old',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 100
    }
    store.tasks = [task]
    let resolveApi!: (value: Task) => void
    const deferred = new Promise<Task>((resolve) => {
      resolveApi = resolve
    })
    vi.mocked(taskApi.update).mockReturnValue(deferred)

    const done = store.updateTask('ENG-4', { title: 'New' })
    const row0 = () => store.tasks[0]
    expect(row0()).toBeDefined()
    expect(row0()!.title).toBe('New')
    expect(row0()!.updatedAt).toBeGreaterThanOrEqual(100)
    resolveApi({ ...task, title: 'New', updatedAt: 200 })
    await done
    expect(row0()!.title).toBe('New')
    expect(row0()!.updatedAt).toBe(200)
  })

  const baseTask = (overrides: Partial<Task> & Pick<Task, 'id'>): Task => ({
    title: 'T',
    status: 'todo',
    priority: 'medium',
    createdAt: 1,
    updatedAt: 1,
    ...overrides
  })

  it('filters by single label id (OR)', () => {
    const store = useTaskStore()
    store.tasks = [
      baseTask({ id: 'A', labels: [{ id: 1, name: 'Bug' }] }),
      baseTask({ id: 'B', labels: [{ id: 2, name: 'Feat' }] }),
      baseTask({ id: 'C', labels: [] })
    ]
    store.filterLabelIds = [1]
    expect(store.filteredTasks.map((t) => t.id)).toEqual(['A'])
  })

  it('filters by multiple label ids as OR', () => {
    const store = useTaskStore()
    store.tasks = [
      baseTask({ id: 'A', labels: [{ id: 1, name: 'Bug' }] }),
      baseTask({ id: 'B', labels: [{ id: 2, name: 'Feat' }] }),
      baseTask({ id: 'C', labels: [] })
    ]
    store.filterLabelIds = [1, 2]
    expect(new Set(store.filteredTasks.map((t) => t.id))).toEqual(new Set(['A', 'B']))
  })

  it('combines label filter with status', () => {
    const store = useTaskStore()
    store.tasks = [
      baseTask({ id: 'A', status: 'todo', labels: [{ id: 1, name: 'Bug' }] }),
      baseTask({ id: 'B', status: 'done', labels: [{ id: 1, name: 'Bug' }] })
    ]
    store.filterLabelIds = [1]
    store.filterStatusList = ['todo']
    expect(store.filteredTasks.map((t) => t.id)).toEqual(['A'])
  })

  it('clearIssueFilters clears label ids', () => {
    const store = useTaskStore()
    store.filterLabelIds = [1]
    store.clearIssueFilters()
    expect(store.filterLabelIds).toEqual([])
  })

  it('stripProjectLabelFromTasks removes label id from filter selection', () => {
    const store = useTaskStore()
    store.tasks = [
      baseTask({ id: 'A', projectId: 10, labels: [{ id: 1, name: 'Bug' }] })
    ]
    store.filterLabelIds = [1]
    store.stripProjectLabelFromTasks(10, 1)
    expect(store.filterLabelIds).toEqual([])
  })

  it('fetchTasks clears tasks immediately and ignores stale responses after project switch', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    let resolveP1!: (v: Task[]) => void
    const p1 = new Promise<Task[]>((r) => {
      resolveP1 = r
    })
    let resolveP2!: (v: Task[]) => void
    const p2 = new Promise<Task[]>((r) => {
      resolveP2 = r
    })
    vi.mocked(taskApi.list)
      .mockImplementationOnce(() => p1)
      .mockImplementationOnce(() => p2)

    const store = useTaskStore()
    store.tasks = [baseTask({ id: 'OLD', projectId: 1 })]

    const first = store.fetchTasks()
    expect(store.tasks).toEqual([])
    expect(store.isLoading).toBe(true)

    projectStore.setActiveProject(2)
    const second = store.fetchTasks()
    expect(store.tasks).toEqual([])
    expect(store.isLoading).toBe(true)

    resolveP2([baseTask({ id: 'P2', projectId: 2 })])
    await second
    expect(store.tasks.map((t) => t.id)).toEqual(['P2'])
    expect(store.isLoading).toBe(false)

    resolveP1([baseTask({ id: 'P1-STALE', projectId: 1 })])
    await first
    expect(store.tasks.map((t) => t.id)).toEqual(['P2'])
  })

  it('rolls back local optimistic patch when API update fails', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-ROLL',
      numericId: 901,
      title: 'Before',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]
    vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('network'))

    await expect(store.updateTask('ENG-ROLL', { title: 'After' })).rejects.toThrow('network')

    expect(store.tasks[0]?.title).toBe('Before')
    expect(store.error).toMatch(/network/i)
  })

  it('rolls back parentId optimistic patch and restores parent sub-issue counts on API failure', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    const store = useTaskStore()
    const parent = {
      id: 'ENG-P',
      numericId: 201,
      title: 'Parent',
      status: 'todo' as const,
      priority: 'medium' as const,
      createdAt: 1,
      updatedAt: 1,
      subIssueCount: 1,
      completedSubIssueCount: 0
    }
    const child = {
      id: 'ENG-C',
      numericId: 202,
      title: 'Child',
      status: 'todo' as const,
      priority: 'medium' as const,
      createdAt: 1,
      updatedAt: 1,
      parentId: '201' as const
    }
    store.tasks = [parent, child]

    vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('fail'))

    await expect(store.updateTask('ENG-C', { parentId: null })).rejects.toThrow('fail')

    expect(store.tasks.find((t) => t.id === 'ENG-C')?.parentId).toBe('201')
    expect(store.tasks.find((t) => t.id === 'ENG-P')).toMatchObject({
      subIssueCount: 1,
      completedSubIssueCount: 0
    })
  })

  it('fetchTaskByKey upserts task by key', async () => {
    const store = useTaskStore()
    store.tasks = [baseTask({ id: 'ENG-1', title: 'Old' })]

    vi.mocked(taskApi.get).mockResolvedValueOnce(baseTask({ id: 'ENG-1', title: 'Updated', projectId: 9 }))
    vi.mocked(taskApi.get).mockResolvedValueOnce(baseTask({ id: 'ENG-2', title: 'New', projectId: 9 }))

    const updated = await store.fetchTaskByKey('ENG-1')
    const created = await store.fetchTaskByKey('ENG-2')

    expect(updated.title).toBe('Updated')
    expect(created.title).toBe('New')
    expect(store.tasks.find((task) => task.id === 'ENG-1')?.title).toBe('Updated')
    expect(store.tasks.some((task) => task.id === 'ENG-2')).toBe(true)
  })
})
