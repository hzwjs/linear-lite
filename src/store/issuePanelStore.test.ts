import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useIssuePanelStore } from './issuePanelStore'
import { resolveWorkspaceSourceLabel } from '../views/BoardViewContent.vue'

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
    store.openWorkspace('ENG-1', '全部任务')

    expect(store.isComposerOpen).toBe(false)
    expect(store.composerDefaults).toEqual({})
    expect(store.workspaceTaskId).toBe('ENG-1')
    expect(store.workspaceSourceLabel).toBe('全部任务')
  })

  it('opens workspace without a source label when none is provided', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-2')

    expect(store.workspaceTaskId).toBe('ENG-2')
    expect(store.workspaceSourceLabel).toBeNull()
  })

  it('opens workspace with null source label without throwing and clears source', () => {
    const store = useIssuePanelStore()

    expect(() => store.openWorkspace('ENG-x', null)).not.toThrow()

    expect(store.workspaceTaskId).toBe('ENG-x')
    expect(store.workspaceSourceLabel).toBeNull()
  })

  it('keeps an existing source label when the same task is opened again without one', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-3', '全部任务')
    store.openWorkspace('ENG-3')

    expect(store.workspaceTaskId).toBe('ENG-3')
    expect(store.workspaceSourceLabel).toBe('全部任务')
  })

  it('resolves the source label from the task being opened instead of reusing the previous one', () => {
    const groups = [
      {
        label: '全部任务',
        tasks: [{ id: 'ENG-1' }],
        rows: [{ task: { id: 'ENG-1' } }]
      },
      {
        label: '进行中',
        tasks: [{ id: 'ENG-2' }],
        rows: [{ task: { id: 'ENG-2' } }]
      }
    ]

    expect(resolveWorkspaceSourceLabel('ENG-2', groups)).toBe('进行中')
  })

  it('opening composer closes existing workspace context', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-9', '全部任务')
    store.openComposer({ status: 'todo' })

    expect(store.workspaceTaskId).toBeNull()
    expect(store.workspaceSourceLabel).toBeNull()
    expect(store.isComposerOpen).toBe(true)
  })

  it('closes workspace without touching composer state callback order', () => {
    const store = useIssuePanelStore()

    store.openWorkspace('ENG-2', '进行中')
    store.closeWorkspace()

    expect(store.workspaceTaskId).toBeNull()
    expect(store.workspaceSourceLabel).toBeNull()
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
