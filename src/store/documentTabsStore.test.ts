import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useDocumentTabsStore } from './documentTabsStore'

describe('documentTabsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('opens new tabs and persists to localStorage', () => {
    const store = useDocumentTabsStore()
    expect(store.getTabs(1)).toEqual([])

    store.openTab({ id: 10, projectId: 1, title: 'Doc 10', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 20, projectId: 1, title: 'Doc 20', contentFormat: 'BLOCKNOTE' })

    const tabs = store.getTabs(1)
    expect(tabs).toHaveLength(2)
    expect(tabs[0]?.id).toBe(10)
    expect(tabs[1]?.id).toBe(20)

    const raw = localStorage.getItem('linear-lite:doc-tabs:1')
    expect(JSON.parse(raw!)).toHaveLength(2)
  })

  it('does not duplicate existing tab and updates title/format', () => {
    const store = useDocumentTabsStore()
    store.openTab({ id: 10, projectId: 1, title: 'Old Title', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 10, projectId: 1, title: 'New Title', contentFormat: 'BLOCKNOTE' })

    const tabs = store.getTabs(1)
    expect(tabs).toHaveLength(1)
    expect(tabs[0]?.title).toBe('New Title')
  })

  it('closes a tab and returns adjacent tab id', () => {
    const store = useDocumentTabsStore()
    store.openTab({ id: 10, projectId: 1, title: 'Doc 10', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 20, projectId: 1, title: 'Doc 20', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 30, projectId: 1, title: 'Doc 30', contentFormat: 'BLOCKNOTE' })

    // Close middle tab 20 -> next is 30
    const next1 = store.closeTab(1, 20)
    expect(next1).toBe(30)
    expect(store.getTabs(1).map((t) => t.id)).toEqual([10, 30])

    // Close last tab 30 -> next is 10
    const next2 = store.closeTab(1, 30)
    expect(next2).toBe(10)
    expect(store.getTabs(1).map((t) => t.id)).toEqual([10])

    // Close only tab 10 -> returns null
    const next3 = store.closeTab(1, 10)
    expect(next3).toBeNull()
    expect(store.getTabs(1)).toEqual([])
  })

  it('closes other tabs and all tabs', () => {
    const store = useDocumentTabsStore()
    store.openTab({ id: 10, projectId: 1, title: 'Doc 10', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 20, projectId: 1, title: 'Doc 20', contentFormat: 'BLOCKNOTE' })
    store.openTab({ id: 30, projectId: 1, title: 'Doc 30', contentFormat: 'BLOCKNOTE' })

    store.closeOtherTabs(1, 20)
    expect(store.getTabs(1).map((t) => t.id)).toEqual([20])

    store.closeAllTabs(1)
    expect(store.getTabs(1)).toEqual([])
  })

  it('syncs tab titles with tree nodes', () => {
    const store = useDocumentTabsStore()
    store.openTab({ id: 10, projectId: 1, title: 'Initial Title', contentFormat: 'BLOCKNOTE' })

    store.syncTabsWithTree(1, [
      { id: 10, title: 'Updated from Tree', contentFormat: 'BLOCKNOTE' },
      { id: 99, title: 'Other Node', contentFormat: 'BLOCKNOTE' }
    ])

    expect(store.getTabs(1)[0]?.title).toBe('Updated from Tree')
  })
})
