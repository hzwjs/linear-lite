import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useViewModeStore } from './viewModeStore'

const VIEW_PREF_KEY = 'linear-lite-view-v2'

describe('viewModeStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('defaults every task scope to an ungrouped list while retaining board grouping', () => {
    const store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewConfig.groupBy).toBe('status')
    expect(store.viewConfig.listGroupBy).toBe('none')
    for (const scope of ['active', 'all', 'backlog'] as const) {
      store.setScope(scope)
      expect(store.effectiveViewConfig.groupBy).toBe('none')
    }
    store.setScope('active')
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
    expect(store.viewConfig.scope).toBe('active')
  })

  it('updates the layout through setView', () => {
    const store = useViewModeStore()

    store.setView('list')

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewType).toBe('list')
  })

  it('persists and restores gantt layout', async () => {
    const store = useViewModeStore()
    store.setView('gantt')
    await nextTick()

    setActivePinia(createPinia())
    const restored = useViewModeStore()

    expect(restored.viewConfig.layout).toBe('gantt')
    expect(restored.viewType).toBe('gantt')
  })

  it('updates grouping and ordering independently', () => {
    const store = useViewModeStore()

    store.setGroupBy('priority')
    store.setOrderBy('createdAt')
    store.setOrderDirection('asc')

    expect(store.viewConfig.listGroupBy).toBe('priority')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.orderBy).toBe('createdAt')
    expect(store.viewConfig.orderDirection).toBe('asc')
  })

  it('shares optional grouping across list scopes without changing board grouping', () => {
    const store = useViewModeStore()

    store.setGroupBy('priority')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.groupBy).toBe('status')

    store.setView('board')
    expect(store.effectiveViewConfig.groupBy).toBe('status')

    store.setView('list')
    store.setScope('all')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')
    store.setScope('backlog')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')

    store.setScope('active')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')
  })

  it('restores the list grouping preference', async () => {
    const store = useViewModeStore()
    store.setGroupBy('status')
    await nextTick()

    setActivePinia(createPinia())
    const restored = useViewModeStore()

    expect(restored.viewConfig.listGroupBy).toBe('status')
    expect(restored.effectiveViewConfig.groupBy).toBe('status')
  })

  it('defaults saved configs without a list preference to flat lists for every scope', () => {
    localStorage.setItem(
      VIEW_PREF_KEY,
      JSON.stringify({ layout: 'list', groupBy: 'status', scope: 'active', viewPrefVersion: 5 })
    )
    setActivePinia(createPinia())

    const restored = useViewModeStore()

    expect(restored.viewConfig.groupBy).toBe('status')
    expect(restored.viewConfig.listGroupBy).toBe('none')
    for (const scope of ['all', 'backlog', 'active'] as const) {
      restored.setScope(scope)
      expect(restored.effectiveViewConfig.groupBy).toBe('none')
    }
  })

  it('toggles empty groups visibility', () => {
    const store = useViewModeStore()

    store.setShowEmptyGroups(true)

    expect(store.viewConfig.showEmptyGroups).toBe(true)
  })

  it('toggles visible properties', () => {
    const store = useViewModeStore()

    store.toggleVisibleProperty('priority')

    expect(store.visibleProperties).toEqual([
      'assignee',
      'dueDate',
      'labels',
      'plannedStart',
      'progress'
    ])
  })

  it('keeps the ungrouped list from adding a status text column to visible properties', () => {
    const store = useViewModeStore()

    expect(store.visibleProperties).not.toContain('status')
  })

  it('does not reinterpret status filters or legacy preferences as task scope', () => {
    localStorage.setItem('linear-lite-view', JSON.stringify({ completedVisibility: 'all' }))
    const store = useViewModeStore()
    expect(store.viewConfig.scope).toBe('active')
    store.setScope('backlog')
    expect(store.viewConfig.scope).toBe('backlog')
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
        scope: 'all',
        showSubIssues: true,
        nestedSubIssues: true,
        viewPrefVersion: 5
      })
    )
    setActivePinia(createPinia())
    const store = useViewModeStore()
    expect(store.viewConfig.scope).toBe('all')
    expect(store.viewConfig.viewPrefVersion).toBe(5)
  })

  it('persists and restores full view config', async () => {
    let store = useViewModeStore()
    store.setView('list')
    store.setGroupBy('priority')
    store.setOrderBy('createdAt')
    store.toggleVisibleProperty('priority')
    store.setScope('active')
    await nextTick()

    setActivePinia(createPinia())
    store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewConfig.groupBy).toBe('status')
    expect(store.viewConfig.listGroupBy).toBe('priority')
    expect(store.effectiveViewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.orderBy).toBe('createdAt')
    expect(store.visibleProperties).not.toContain('priority')
    expect(store.viewConfig.scope).toBe('active')
  })
})
