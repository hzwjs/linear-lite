import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useViewModeStore } from './viewModeStore'

describe('viewModeStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('uses board layout defaults with status grouping', () => {
    const store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('board')
    expect(store.viewConfig.groupBy).toBe('status')
    expect(store.viewConfig.orderBy).toBe('updatedAt')
    expect(store.viewConfig.orderDirection).toBe('desc')
    expect(store.viewType).toBe('board')
    expect(store.visibleProperties).toEqual(['assignee', 'dueDate', 'priority'])
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
    store.toggleVisibleProperty('updatedAt')

    expect(store.visibleProperties).toEqual(['assignee', 'dueDate', 'updatedAt'])
  })

  it('persists and restores full view config', async () => {
    let store = useViewModeStore()
    store.setView('list')
    store.setGroupBy('priority')
    store.setOrderBy('createdAt')
    store.toggleVisibleProperty('updatedAt')
    store.setCompletedVisibility('open_only')
    await nextTick()

    setActivePinia(createPinia())
    store = useViewModeStore()

    expect(store.viewConfig.layout).toBe('list')
    expect(store.viewConfig.groupBy).toBe('priority')
    expect(store.viewConfig.orderBy).toBe('createdAt')
    expect(store.visibleProperties).toContain('updatedAt')
    expect(store.viewConfig.completedVisibility).toBe('open_only')
  })
})
