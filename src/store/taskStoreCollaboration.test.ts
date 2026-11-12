import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { taskApi, type TaskMutationResult } from '../services/api/task'
import type { Task } from '../types/domain'
import { useTaskStore } from './taskStore'
import { useProjectStore } from './projectStore'

vi.mock('../services/api/task', () => ({ taskApi: { update: vi.fn(), list: vi.fn() } }))

const initial: Task = { id: 'LL-1', numericId: 1, projectId: 6, title: '初始', description: '需求',
  status: 'todo', progressPercent: 0, priority: 'medium', createdAt: 1, updatedAt: 1, labels: [], inputRevision: 4 }
const newer = { inputRevision: 5, status: 'done' as const, progressPercent: 100, completedAt: 10, updatedAt: 10 }

describe('task store collaboration state observations', () => {
  beforeEach(() => { localStorage.clear(); vi.resetAllMocks(); setActivePinia(createPinia()) })

  it('applies newer state and revision while retaining the task text', () => {
    const store = useTaskStore(); store.tasks = [{ ...initial }]; store.currentTaskId = initial.id
    const marker = store.captureCollaborationStatusObservation(initial.id)
    expect(marker).not.toBeNull()
    expect(store.applyCollaborationStatusObservation(marker!, newer)).toBe(true)
    expect(store.currentTask).toMatchObject({ id: 'LL-1', title: '初始', description: '需求', ...newer })
    expect(store.taskByKeyCache['LL-1']).toMatchObject({ title: '初始', ...newer })
  })

  it('drops an observation captured before an ordinary save starts', async () => {
    const store = useTaskStore(); store.tasks = [{ ...initial }]; store.currentTaskId = initial.id
    const marker = store.captureCollaborationStatusObservation(initial.id)
    expect(marker).not.toBeNull()
    let release!: (value: TaskMutationResult) => void
    vi.mocked(taskApi.update).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const saving = store.updateTask(initial.id, { title: '待保存正文' })
    expect(store.captureCollaborationStatusObservation(initial.id)).toBeNull()
    expect(store.applyCollaborationStatusObservation(marker!, newer)).toBe(false)
    expect(store.currentTask).toMatchObject({ title: '待保存正文', inputRevision: 4, status: 'todo', progressPercent: 0 })
    release({ task: { ...initial, title: '待保存正文' }, autoCompletedAncestors: [] })
    await saving
  })

  it('drops a captured observation after a local edit or task switch', () => {
    const store = useTaskStore(); store.tasks = [{ ...initial }, { ...initial, id: 'LL-2', numericId: 2 }]
    store.currentTaskId = initial.id
    const old = store.captureCollaborationStatusObservation(initial.id)!
    store.applyLocalTaskPatch(initial.id, { title: '新编辑' })
    expect(store.applyCollaborationStatusObservation(old, newer)).toBe(false)
    expect(store.tasks[0]).toMatchObject({ title: '新编辑', inputRevision: 4, status: 'todo' })
    const oldSelection = store.captureCollaborationStatusObservation(initial.id)!
    store.currentTaskId = 'LL-2'
    expect(store.applyCollaborationStatusObservation(oldSelection, newer)).toBe(false)
  })

  it('refuses facts from an older version or timestamp', () => {
    const store = useTaskStore(); store.tasks = [{ ...initial, updatedAt: 5 }]; store.currentTaskId = initial.id
    const marker = store.captureCollaborationStatusObservation(initial.id)!
    expect(store.applyCollaborationStatusObservation(marker, { ...newer, inputRevision: 3 })).toBe(false)
    expect(store.applyCollaborationStatusObservation(marker, { ...newer, updatedAt: 4 })).toBe(false)
    expect(store.currentTask).toMatchObject({ inputRevision: 4, status: 'todo', updatedAt: 5 })
  })

  it('updates a cached task and its project snapshot when the selected row is unloaded', async () => {
    const store = useTaskStore(); useProjectStore().setActiveProject(6)
    vi.mocked(taskApi.list).mockResolvedValueOnce([{ ...initial }])
    await store.fetchTasks()
    store.currentTaskId = initial.id; store.tasks = []
    const marker = store.captureCollaborationStatusObservation(initial.id)!
    expect(store.applyCollaborationStatusObservation(marker, newer)).toBe(true)
    expect(store.currentTask).toMatchObject({ inputRevision: 5, status: 'done' })
    let release!: (value: Task[]) => void
    vi.mocked(taskApi.list).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const reloading = store.fetchTasks()
    expect(store.tasks[0]).toMatchObject({ inputRevision: 5, status: 'done' })
    release([{ ...initial, ...newer }]); await reloading
    expect(store.tasks[0]).toMatchObject({ inputRevision: 5, status: 'done' })
  })
})
