import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTaskStore } from './taskStore'
import { useViewModeStore } from './viewModeStore'
import { taskApi } from '../services/api/task'
import type { Task } from '../types/domain'

vi.mock('../services/api/task', () => ({ taskApi: { update: vi.fn() } }))

function task(id: string, status: Task['status'], patch: Partial<Task> = {}): Task {
  return { id, numericId: Number(id), title: 'Release', projectId: 7, status, priority: 'high',
    assigneeKey: 'user:1', assigneeName: 'Sam', labels: [{ id: 3, name: 'Product' }],
    createdAt: 1, updatedAt: 1, ...patch }
}

describe('task status statistics', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(taskApi.update).mockReset()
  })

  it('uses the active scope before the status filter, including nested children', () => {
    const store = useTaskStore()
    store.tasks = [task('1', 'todo'), task('2', 'in_progress', { parentId: '1' }),
      task('3', 'in_review'), task('4', 'backlog'), task('5', 'done')]
    store.filterStatusList = ['todo']
    expect(store.filteredTasks.map((row) => row.id)).toEqual(['1'])
    expect(store.statusStatistics).toEqual({ total: 3, items: [
      { status: 'todo', count: 1 }, { status: 'in_progress', count: 1 }, { status: 'in_review', count: 1 }
    ] })
    useViewModeStore().setShowSubIssues(false)
    useViewModeStore().setShowEmptyGroups(true)
    expect(store.statusStatistics.total).toBe(3)
  })

  it('combines search, priority, labels and canonical assignee keys without matching names', () => {
    const store = useTaskStore()
    store.tasks = [task('1', 'todo'), task('2', 'in_progress', { assigneeKey: 'external:1' }),
      task('3', 'todo', { priority: 'low' }), task('4', 'in_progress', { title: 'Other' }),
      task('5', 'in_review', { labels: [{ id: 4, name: 'Other' }] })]
    store.searchQuery = 'release'
    store.filterPriorityList = ['high']
    store.filterLabelIds = [3]
    store.filterAssigneeList = ['external:1']
    expect(store.statusStatistics.total).toBe(1)
    expect(store.statusStatistics.items.find((item) => item.status === 'in_progress')?.count).toBe(1)
    expect(store.filteredTasks.map((row) => row.id)).toEqual(['2'])
    store.filterAssigneeList = ['user:1']
    expect(store.filteredTasks.map((row) => row.id)).toEqual(['1'])
  })

  it('counts unassigned separately from external assignees', () => {
    const store = useTaskStore()
    store.tasks = [task('1', 'todo', { assigneeKey: null, assigneeName: null }),
      task('2', 'todo', { assigneeKey: 'external:1' })]
    store.filterAssigneeList = ['unassigned']
    expect(store.statusStatistics.total).toBe(1)
    expect(store.filteredTasks[0]?.id).toBe('1')
  })

  it('keeps scope independent from status and counts zero matches', () => {
    const store = useTaskStore()
    const view = useViewModeStore()
    store.tasks = [task('1', 'backlog'), task('2', 'todo'), task('3', 'done')]
    view.setScope('backlog')
    store.filterStatusList = ['backlog']
    store.filterStatusList = []
    expect(view.viewConfig.scope).toBe('backlog')
    expect(store.statusStatistics).toEqual({ total: 1, items: [{ status: 'backlog', count: 1 }] })
    store.searchQuery = 'Missing'
    expect(store.statusStatistics).toEqual({ total: 0, items: [{ status: 'backlog', count: 0 }] })
    view.setScope('all')
    store.searchQuery = ''
    expect(store.statusStatistics.total).toBe(3)
    expect(store.statusStatistics.items).toHaveLength(7)
  })

  it('reuses aggregated counts while changing status selection across a thousand tasks', () => {
    const store = useTaskStore()
    store.tasks = Array.from({ length: 1000 }, (_, index) => task(String(index + 1),
      index % 2 === 0 ? 'todo' : 'in_progress'))
    const statistics = store.statusStatistics
    store.filterStatusList = ['todo']
    expect(store.filteredTasks).toHaveLength(500)
    expect(store.statusStatistics).toBe(statistics)
    store.filterStatusList = ['in_progress']
    expect(store.filteredTasks).toHaveLength(500)
    expect(store.statusStatistics).toBe(statistics)
  })

  it('updates counts on status changes and restores them after a failed save', async () => {
    const store = useTaskStore()
    const row = task('1', 'todo')
    store.tasks = [row]
    vi.mocked(taskApi.update).mockResolvedValueOnce({ task: { ...row, status: 'in_review' }, autoCompletedAncestors: [] })
    await store.transitionTask(row.id, 'in_review')
    expect(store.statusStatistics.items.find((item) => item.status === 'in_review')?.count).toBe(1)
    vi.mocked(taskApi.update).mockRejectedValueOnce(new Error('Offline'))
    await expect(store.transitionTask(row.id, 'done')).rejects.toThrow('Offline')
    expect(store.statusStatistics.total).toBe(1)
    expect(store.statusStatistics.items.find((item) => item.status === 'in_review')?.count).toBe(1)
  })
})
