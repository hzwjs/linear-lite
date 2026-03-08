import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useIssuePanelStore } from './issuePanelStore'

describe('issuePanelStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('opens composer with provided defaults', () => {
    const store = useIssuePanelStore()

    store.openComposer({ status: 'done', projectId: 42 })

    expect(store.isComposerOpen).toBe(true)
    expect(store.composerDefaults).toEqual({
      status: 'done',
      projectId: 42
    })
    expect(store.workspaceTaskId).toBeNull()
  })

  it('replaces previous defaults when reopening composer', () => {
    const store = useIssuePanelStore()

    store.openComposer({ status: 'todo', projectId: 1 })
    store.openComposer({ status: 'in_progress' })

    expect(store.composerDefaults).toEqual({
      status: 'in_progress'
    })
  })

  it('closes composer and clears defaults', () => {
    const store = useIssuePanelStore()

    store.openComposer({ status: 'todo' })
    store.closeComposer()

    expect(store.isComposerOpen).toBe(false)
    expect(store.composerDefaults).toEqual({})
  })

  it('opens workspace and closes composer', () => {
    const store = useIssuePanelStore()

    store.openComposer({ status: 'todo' })
    store.openWorkspace('ENG-1')

    expect(store.isComposerOpen).toBe(false)
    expect(store.composerDefaults).toEqual({})
    expect(store.workspaceTaskId).toBe('ENG-1')
  })

  it('opening composer closes existing workspace context', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-9')
    store.openComposer({ status: 'todo' })

    expect(store.workspaceTaskId).toBeNull()
    expect(store.isComposerOpen).toBe(true)
  })

  it('closes workspace without touching composer state callback order', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-2')
    store.closeWorkspace()

    expect(store.workspaceTaskId).toBeNull()
  })

  it('moves selection through visible task ids', () => {
    const store = useIssuePanelStore()

    store.moveSelection(['ENG-1', 'ENG-2', 'ENG-3'], 1)
    expect(store.selectedTaskId).toBe('ENG-1')

    store.moveSelection(['ENG-1', 'ENG-2', 'ENG-3'], 1)
    expect(store.selectedTaskId).toBe('ENG-2')

    store.moveSelection(['ENG-1', 'ENG-2', 'ENG-3'], -1)
    expect(store.selectedTaskId).toBe('ENG-1')
  })

  it('keeps selection in bounds and clears when tasks disappear', () => {
    const store = useIssuePanelStore()

    store.setSelectedTask('ENG-3')
    store.moveSelection(['ENG-1', 'ENG-2'], 1)
    expect(store.selectedTaskId).toBe('ENG-2')

    store.syncSelection(['ENG-5'])
    expect(store.selectedTaskId).toBeNull()
  })
})
