import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useViewModeStore } from './viewModeStore'

const VIEW_PREF_KEY = 'linear-lite-view'

describe('viewModeStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('uses list layout defaults with status grouping', () => {
    const store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewConfig.groupBy).toBe('status')
    expect(store.viewConfig.orderBy).toBe('updatedAt')
    expect(store.viewConfig.orderDirection).toBe('desc')
    expect(store.viewType).toBe('list')
    expect(store.visibleProperties).toEqual([
      'assignee',
      'dueDate',
      'labels',
      'plannedStart',
      'priority',
      'progress'
    ])
    expect(store.viewConfig.completedVisibility).toBe('open_only')
  })

  it('updates the layout through setView', () => {
    const store = useViewModeStore()

    store.setView('list')

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewType).toBe('list')
  })

  it('updates grouping and ordering independently', () => {
    const store = useViewModeStore()

    store.setGroupBy('priority')
    store.setOrderBy('createdAt')
    store.setOrderDirection('asc')

    expect(store.viewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.orderBy).toBe('createdAt')
    expect(store.viewConfig.orderDirection).toBe('asc')
  })

  it('toggles empty groups visibility', () => {
    const store = useViewModeStore()

    store.setShowEmptyGroups(true)

    expect(store.viewConfig.showEmptyGroups).toBe(true)
  })

  it('toggles visible properties', () => {
    const store = useViewModeStore()

    store.toggleVisibleProperty('priority')

    expect(store.visibleProperties).toEqual(['assignee', 'dueDate', 'labels', 'plannedStart', 'progress'])
  })

  it('migrates legacy stored visibleProperties to include progress and plannedStart once', () => {
    localStorage.setItem(
      VIEW_PREF_KEY,
      JSON.stringify({
        layout: 'list',
        groupBy: 'status',
        orderBy: 'updatedAt',
        orderDirection: 'desc',
        visibleProperties: ['assignee', 'dueDate', 'priority', 'updatedAt'],
        showEmptyGroups: false,
        completedVisibility: 'all',
        showSubIssues: true,
        nestedSubIssues: true
      })
    )
    setActivePinia(createPinia())
    const store = useViewModeStore()

    expect(store.visibleProperties).toContain('progress')
    expect(store.visibleProperties).toContain('plannedStart')
    expect(store.visibleProperties).toContain('labels')
    expect(store.viewConfig.viewPrefVersion).toBe(4)
    expect(store.viewConfig.completedVisibility).toBe('open_only')
  })

  it('does not re-add progress after user turned it off on v2 config', async () => {
    localStorage.setItem(
      VIEW_PREF_KEY,
      JSON.stringify({
        layout: 'list',
        groupBy: 'status',
        orderBy: 'updatedAt',
        orderDirection: 'desc',
        visibleProperties: ['assignee', 'dueDate', 'plannedStart', 'priority', 'updatedAt'],
        showEmptyGroups: false,
        completedVisibility: 'all',
        showSubIssues: true,
        nestedSubIssues: true,
        viewPrefVersion: 2
      })
    )
    setActivePinia(createPinia())
    const store = useViewModeStore()
    expect(store.visibleProperties).not.toContain('progress')
    expect(store.visibleProperties).toContain('labels')
    expect(store.viewConfig.viewPrefVersion).toBe(4)
    expect(store.viewConfig.completedVisibility).toBe('open_only')
  })

  it('preserves 全部任务 when stored at viewPrefVersion 4 with completedVisibility all', () => {
    localStorage.setItem(
      VIEW_PREF_KEY,
      JSON.stringify({
        layout: 'list',
        groupBy: 'status',
        orderBy: 'updatedAt',
        orderDirection: 'desc',
        visibleProperties: [
          'assignee',
          'dueDate',
          'labels',
          'plannedStart',
          'priority',
          'progress'
        ],
        showEmptyGroups: false,
        completedVisibility: 'all',
        showSubIssues: true,
        nestedSubIssues: true,
        viewPrefVersion: 4
      })
    )
    setActivePinia(createPinia())
    const store = useViewModeStore()
    expect(store.viewConfig.completedVisibility).toBe('all')
    expect(store.viewConfig.viewPrefVersion).toBe(4)
  })

  it('persists and restores full view config', async () => {
    let store = useViewModeStore()
    store.setView('list')
    store.setGroupBy('priority')
    store.setOrderBy('createdAt')
    store.toggleVisibleProperty('priority')
    store.setCompletedVisibility('open_only')
    await nextTick()

    setActivePinia(createPinia())
    store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.orderBy).toBe('createdAt')
    expect(store.visibleProperties).not.toContain('priority')
    expect(store.viewConfig.completedVisibility).toBe('open_only')
  })
})
