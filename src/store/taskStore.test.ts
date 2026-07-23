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

  it('fetchTasks keeps cached tasks for the active project while refreshing', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    const store = useTaskStore()
    const cached = baseTask({ id: 'CACHED', projectId: 1, updatedAt: 10 })
    vi.mocked(taskApi.list).mockResolvedValueOnce([cached])
    await store.fetchTasks()

    let resolveRefresh!: (v: Task[]) => void
    const refresh = new Promise<Task[]>((resolve) => {
      resolveRefresh = resolve
    })
    vi.mocked(taskApi.list).mockReturnValueOnce(refresh)

    const pending = store.fetchTasks()
    expect(store.tasks.map((t) => t.id)).toEqual(['CACHED'])
    expect(store.isLoading).toBe(true)

    resolveRefresh([baseTask({ id: 'FRESH', projectId: 1, updatedAt: 11 })])
    await pending
    expect(store.tasks.map((t) => t.id)).toEqual(['FRESH'])
    expect(store.isLoading).toBe(false)
  })

  it('createTask inserts an optimistic row before API resolves and reconciles it', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    const store = useTaskStore()
    let resolveCreate!: (v: Task) => void
    const create = new Promise<Task>((resolve) => {
      resolveCreate = resolve
    })
    vi.mocked(taskApi.create).mockReturnValueOnce(create)

    const done = store.createTask({
      title: 'New task',
      description: '',
      status: 'todo',
      priority: 'medium',
      progressPercent: 0
    })

    expect(store.tasks).toHaveLength(1)
    expect(store.tasks[0]).toMatchObject({
      title: 'New task',
      projectId: 1,
      status: 'todo',
      priority: 'medium'
    })
    expect(store.tasks[0]?.id).toMatch(/^optimistic-/)

    resolveCreate(baseTask({ id: 'ENG-NEW', title: 'New task', projectId: 1, updatedAt: 2 }))
    await done

    expect(store.tasks.map((t) => t.id)).toEqual(['ENG-NEW'])
  })

  it('createTask rolls back the optimistic row when API fails', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(1)

    const store = useTaskStore()
    store.tasks = [baseTask({ id: 'EXISTING', projectId: 1 })]
    vi.mocked(taskApi.create).mockRejectedValueOnce(new Error('create failed'))

    await expect(
      store.createTask({
        title: 'New task',
        description: '',
        status: 'todo',
        priority: 'medium',
        progressPercent: 0
      })
    ).rejects.toThrow('create failed')

    expect(store.tasks.map((t) => t.id)).toEqual(['EXISTING'])
  })

  it('rolls back local optimistic patch without turning a save failure into a board load error', async () => {
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
    expect(store.error).toBeNull()
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

  it('keeps currentTask resolvable from cache when task list is temporarily cleared', async () => {
    const store = useTaskStore()
    store.currentTaskId = 'ENG-9'
    vi.mocked(taskApi.get).mockResolvedValueOnce(baseTask({ id: 'ENG-9', title: 'Detail', projectId: 7 }))

    await store.fetchTaskByKey('ENG-9')
    expect(store.currentTask?.id).toBe('ENG-9')

    store.tasks = []
    expect(store.currentTask?.id).toBe('ENG-9')
  })

  it('keeps cached task description when a lightweight list refresh arrives after detail', async () => {
    const projectStore = useProjectStore()
    projectStore.setActiveProject(7)
    const store = useTaskStore()
    store.currentTaskId = 'ENG-9'
    const detail = baseTask({
      id: 'ENG-9',
      title: 'Detail title',
      description: 'Large detail body',
      projectId: 7,
      updatedAt: 10
    })
    vi.mocked(taskApi.get).mockResolvedValueOnce(detail)
    vi.mocked(taskApi.list).mockResolvedValueOnce([
      baseTask({
        id: 'ENG-9',
        title: 'List title',
        description: undefined,
        projectId: 7,
        updatedAt: 11
      })
    ])

    await store.fetchTaskByKey('ENG-9')
    await store.fetchTasks()

    expect(store.currentTask).toMatchObject({
      id: 'ENG-9',
      title: 'List title',
      description: 'Large detail body',
      updatedAt: 11
    })
    expect(store.tasks[0]?.description).toBe('Large detail body')
  })

  it('keeps newer pending optimistic fields after older request succeeds', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-L1',
      numericId: 1001,
      title: 'Init',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]
    let resolveA!: (value: Task) => void
    const reqA = new Promise<Task>((resolve) => {
      resolveA = resolve
    })
    vi.mocked(taskApi.update).mockReturnValueOnce(reqA).mockResolvedValueOnce({
      ...task,
      title: 'B',
      updatedAt: 3
    })

    const pA = store.updateTask('ENG-L1', { title: 'A' })
    const pB = store.updateTask('ENG-L1', { title: 'B' })
    expect(store.tasks[0]?.title).toBe('B')

    resolveA({ ...task, title: 'A', updatedAt: 2 })
    await pA
    expect(store.tasks[0]?.title).toBe('B')
    await pB
    expect(store.tasks[0]?.title).toBe('B')
  })

  it('keeps pending optimistic fields after older request fails', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-L2',
      numericId: 1002,
      title: 'Init',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]
    let rejectA!: (reason?: unknown) => void
    const reqA = new Promise<Task>((_resolve, reject) => {
      rejectA = reject
    })
    vi.mocked(taskApi.update).mockReturnValueOnce(reqA).mockResolvedValueOnce({
      ...task,
      title: 'B',
      updatedAt: 3
    })

    const pA = store.updateTask('ENG-L2', { title: 'A' })
    const pB = store.updateTask('ENG-L2', { title: 'B' })
    rejectA(new Error('fail A'))
    await expect(pA).rejects.toThrow('fail A')
    expect(store.tasks[0]?.title).toBe('B')
    await pB
    expect(store.tasks[0]?.title).toBe('B')
  })

  it('clears completedAt in optimistic overlay when status turns non-done', async () => {
    const store = useTaskStore()
    const doneTask: Task = {
      id: 'ENG-DERIVE',
      numericId: 1003,
      title: 'T',
      status: 'done',
      priority: 'medium',
      progressPercent: 100,
      completedAt: 999,
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [doneTask]
    let resolveA!: (value: Task) => void
    const reqA = new Promise<Task>((resolve) => {
      resolveA = resolve
    })
    vi.mocked(taskApi.update).mockReturnValueOnce(reqA).mockResolvedValueOnce({
      ...doneTask,
      status: 'todo',
      progressPercent: 0,
      completedAt: null,
      updatedAt: 3
    })

    const pA = store.updateTask('ENG-DERIVE', { status: 'done' })
    const pB = store.updateTask('ENG-DERIVE', { status: 'todo', progressPercent: 0 })
    resolveA({ ...doneTask, status: 'done', progressPercent: 100, completedAt: 1200, updatedAt: 2 })
    await pA
    expect(store.tasks[0]?.status).toBe('todo')
    expect(store.tasks[0]?.completedAt).toBeFalsy()
    await pB
  })

  it('drainTask returns save_failed only when final intent remains unsaved', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-DRAIN',
      numericId: 1004,
      title: 'Init',
      status: 'todo',
      priority: 'medium',
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]

    vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('x'))
    void store.updateTask('ENG-DRAIN', { title: 'X' }).catch(() => {})
    const r1 = await store.drainTask('ENG-DRAIN', { timeoutMs: 100 })
    expect(r1.ok).toBe(false)
    if (!r1.ok) expect(r1.reason).toBe('save_failed')

    vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('a')).mockResolvedValueOnce({
      ...task,
      title: 'B',
      updatedAt: 3
    })
    void store.updateTask('ENG-DRAIN', { title: 'A' }).catch(() => {})
    void store.updateTask('ENG-DRAIN', { title: 'B' }).catch(() => {})
    const r2 = await store.drainTask('ENG-DRAIN', { timeoutMs: 1000 })
    expect(r2.ok).toBe(true)
  })

  it('prefers clearParent over parentId in update payload', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-PARENT',
      numericId: 1005,
      title: 'T',
      status: 'todo',
      priority: 'medium',
      parentId: '200',
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]
    vi.mocked(taskApi.update).mockResolvedValueOnce({
      ...task,
      parentId: null,
      updatedAt: 2
    })

    await store.updateTask('ENG-PARENT', { parentId: '300', clearParent: true })

    expect(taskApi.update).toHaveBeenCalledWith(
      'ENG-PARENT',
      expect.objectContaining({
        clearParent: true
      })
    )
    const body = vi.mocked(taskApi.update).mock.calls.at(-1)?.[1]
    expect(body).toBeDefined()
    expect(body?.parentId).toBeUndefined()
  })

  it('keeps optimistic labels valid when payload mixes {id} and {name}', async () => {
    const store = useTaskStore()
    const task: Task = {
      id: 'ENG-LABEL',
      numericId: 1006,
      title: 'T',
      status: 'todo',
      priority: 'medium',
      labels: [{ id: 1, name: 'Bug' }],
      createdAt: 1,
      updatedAt: 1
    }
    store.tasks = [task]
    let resolveApi!: (value: Task) => void
    const deferred = new Promise<Task>((resolve) => {
      resolveApi = resolve
    })
    vi.mocked(taskApi.update).mockReturnValueOnce(deferred)

    const done = store.updateTask('ENG-LABEL', {
      labels: [{ id: 1 }, { name: 'New Label' }]
    })

    const optimisticLabels = store.tasks[0]?.labels ?? []
    expect(optimisticLabels).toHaveLength(2)
    expect(optimisticLabels[0]).toEqual({ id: 1, name: 'Bug' })
    expect(optimisticLabels[1]?.name).toBe('New Label')
    expect(Number.isFinite(optimisticLabels[1]?.id)).toBe(true)
    expect(optimisticLabels[1]?.id).toBeLessThan(0)

    resolveApi({
      ...task,
      labels: [
        { id: 1, name: 'Bug' },
        { id: 2, name: 'New Label' }
      ],
      updatedAt: 2
    })
    await done
  })
})
