import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import { useViewModeStore } from '../store/viewModeStore'
import { taskApi, type TaskMutationResult } from '../services/api/task'
import type { Task } from '../types/domain'
import BoardViewContent from './BoardViewContent.vue'

vi.mock('../services/api/task', () => ({ taskApi: { update: vi.fn() } }))
vi.mock('../utils/taskDetailPreload', () => ({
  invalidateTaskDetailSnapshot: vi.fn(),
  preloadTaskDetail: vi.fn().mockResolvedValue(undefined)
}))
vi.mock('../components/TaskEditor.vue', () => ({ __esModule: true, default: { template: '<div class="editor-stub" />' } }))
vi.mock('../components/IssueComposer.vue', () => ({ __esModule: true, default: { template: '<div />' } }))
vi.mock('../components/GanttChart.vue', () => ({ __esModule: true, default: { template: '<div />' } }))
vi.mock('../components/CommandPalette.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({
    props: ['commands'],
    setup(props) {
      return () => h('div', props.commands.map((command: { id: string; label: string; run: () => void }) =>
        h('button', { 'data-command': command.id, onClick: command.run }, command.label)))
    }
  }) }
})

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
let cleanup: (() => void) | undefined

function task(id: string, overrides: Partial<Task> = {}): Task {
  return {
    id, title: id, status: 'todo', priority: 'medium', projectId: 7,
    createdAt: 1, updatedAt: 10, ...overrides
  }
}

async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve()
    await nextTick()
  }
  await new Promise(resolve => setTimeout(resolve, 0))
}

async function mountWorkspace(tasks: Task[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useProjectStore().setActiveProject(7)
  const store = useTaskStore()
  store.tasks = tasks
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/projects/:projectId/tasks/:taskId', component: { template: '<div />' } }
    ]
  })
  await router.push('/')
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(BoardViewContent, { users: [] })
  app.use(pinia).use(i18n).use(router)
  app.mount(host)
  cleanup = () => { app.unmount(); host.remove() }
  await flush()
  return { host, store, router, panel: useIssuePanelStore() }
}

async function bulkChangeStatus(host: HTMLElement, status: string) {
  host.querySelectorAll<HTMLInputElement>('.task-row-checkbox').forEach(checkbox => checkbox.click())
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true }))
  await flush()
  ;(document.querySelector('[data-command="change-status"]') as HTMLButtonElement).click()
  await flush()
  ;(document.querySelector(`[data-command="bulk-status-${status}"]`) as HTMLButtonElement).click()
  await flush()
}

describe('active list status updates', () => {
  beforeEach(() => {
    localStorage.clear()
    i18n.global.locale.value = 'zh-CN'
    vi.mocked(taskApi.update).mockReset()
    HTMLElement.prototype.scrollIntoView = vi.fn()
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 0 })
  })

  afterEach(() => {
    cleanup?.()
    cleanup = undefined
    document.body.innerHTML = ''
    if (originalScrollIntoView) HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView
    vi.unstubAllGlobals()
  })

  it('keeps backlog rows headerless and records backlog as their detail source', async () => {
    const view = await mountWorkspace([task('ENG-BACKLOG', { status: 'backlog' })])
    useViewModeStore().setScope('backlog')
    await flush()
    expect(view.host.querySelector('.group-header')).toBeNull()
    const row = view.host.querySelector<HTMLElement>('[data-task-id="ENG-BACKLOG"]')!
    expect(row).not.toBeNull()
    row.click()
    await flush()
    expect(view.panel.workspaceSourceLabel).toBe('待规划')
  })

  it('shows pending feedback, then locates the confirmed row in cross-status sorted order', async () => {
    const first = task('ENG-1')
    const second = task('ENG-2', { status: 'in_progress', updatedAt: 20 })
    const view = await mountWorkspace([first, second])
    let resolve!: (value: TaskMutationResult) => void
    vi.mocked(taskApi.update).mockImplementation(() => new Promise((done) => { resolve = done }))
    const saving = view.store.transitionTask(first.id, 'in_review')
    await flush()
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('保存 ENG-1 的状态')
    expect(view.host.querySelector('.status-feedback')?.getAttribute('aria-busy')).toBe('true')
    resolve({ task: { ...first, status: 'in_review', updatedAt: 30 }, autoCompletedAncestors: [] })
    await saving
    await flush()
    expect([...view.host.querySelectorAll<HTMLElement>('[data-task-id]')].map(row => row.dataset.taskId))
      .toEqual(['ENG-1', 'ENG-2'])
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('状态保存为「审核中」')
    expect((document.activeElement as HTMLElement).dataset.taskId).toBe(first.id)
    expect(view.panel.selectedTaskId).toBe(first.id)
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  it('keeps picker arrow keys inside the control instead of navigating the task list', async () => {
    const first = task('ENG-1', { updatedAt: 30 })
    const second = task('ENG-2', { updatedAt: 20 })
    const view = await mountWorkspace([first, second])
    view.panel.setSelectedTask(first.id)
    await flush()
    const trigger = view.host.querySelector<HTMLButtonElement>('[data-task-id="ENG-1"] .task-row-status-trigger')!
    trigger.focus()
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }))
    await flush()
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(view.panel.selectedTaskId).toBe(first.id)
  })

  it('explains why a task left a status filter and offers its detail', async () => {
    const first = task('ENG-1')
    const view = await mountWorkspace([first])
    view.store.filterStatusList = ['todo']
    vi.mocked(taskApi.update).mockResolvedValue({
      task: { ...first, status: 'in_review', updatedAt: 30 }, autoCompletedAncestors: []
    })
    await view.store.transitionTask(first.id, 'in_review')
    await flush()
    expect(view.host.querySelector('[data-task-id="ENG-1"]')).toBeNull()
    expect(view.host.querySelector('.empty-state')).toBeTruthy()
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('任务移出当前列表')
    ;(view.host.querySelector('.status-feedback button') as HTMLButtonElement).click()
    await flush()
    expect(view.router.currentRoute.value.params.taskId).toBe(first.id)
    expect(view.host.querySelector('.status-feedback')).toBeNull()
  })

  it('keeps a nearby selection when a completed task leaves the active list', async () => {
    const first = task('ENG-1', { updatedAt: 30 })
    const second = task('ENG-2', { updatedAt: 20 })
    const view = await mountWorkspace([first, second])
    view.panel.setSelectedTask(first.id)
    vi.mocked(taskApi.update).mockResolvedValue({
      task: { ...first, status: 'done', updatedAt: 40 }, autoCompletedAncestors: []
    })
    await view.store.transitionTask(first.id, 'done')
    await flush()
    expect(view.panel.selectedTaskId).toBe(second.id)
    expect((document.activeElement as HTMLElement).dataset.taskId).toBe(second.id)
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('已完成')
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('移出当前列表')
  })

  it('shows an empty result and feedback after the last active task completes', async () => {
    const first = task('ENG-1')
    const view = await mountWorkspace([first])
    vi.mocked(taskApi.update).mockResolvedValue({
      task: { ...first, status: 'done', updatedAt: 40 }, autoCompletedAncestors: []
    })
    await view.store.transitionTask(first.id, 'done')
    await flush()
    expect(view.host.querySelector('.empty-state')).toBeTruthy()
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('移出当前列表')
    expect(view.host.querySelector('.list-view')).toBeNull()
  })

  it('reports a failed save and locates the restored status instead of claiming success', async () => {
    const first = task('ENG-1')
    const view = await mountWorkspace([first])
    vi.mocked(taskApi.update).mockRejectedValue(new Error('network error'))
    await expect(view.store.transitionTask(first.id, 'in_review')).rejects.toThrow('network error')
    await flush()
    expect(view.store.tasks[0]?.status).toBe('todo')
    expect(view.host.querySelector('.status-feedback[role="alert"]')?.textContent).toContain('状态保存失败')
    expect(view.host.querySelector('.status-feedback')?.textContent).not.toContain('状态保存为')
    expect((document.activeElement as HTMLElement).dataset.taskId).toBe(first.id)
  })

  it('restores task context and feedback when returning from its edited detail', async () => {
    const first = task('ENG-1')
    const view = await mountWorkspace([first])
    await view.router.push('/projects/7/tasks/ENG-1')
    view.store.currentTaskId = first.id
    await flush()
    vi.mocked(taskApi.update).mockResolvedValue({
      task: { ...first, status: 'in_review', updatedAt: 30 }, autoCompletedAncestors: []
    })
    await view.store.transitionTask(first.id, 'in_review')
    await flush()
    expect(view.host.querySelector('.status-feedback')).toBeNull()
    await view.router.push('/')
    await flush()
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('审核中')
    expect((document.activeElement as HTMLElement).dataset.taskId).toBe(first.id)
  })

  it('summarizes partial bulk failure and focuses the failed task', async () => {
    const first = task('ENG-1')
    const second = task('ENG-2')
    const view = await mountWorkspace([first, second])
    vi.mocked(taskApi.update).mockImplementation(async (id) => {
      if (id === second.id) throw new Error('network error')
      return { task: { ...first, status: 'done', updatedAt: 30 }, autoCompletedAncestors: [] }
    })
    await bulkChangeStatus(view.host, 'done')
    expect(view.host.querySelector('.status-feedback[role="alert"]')?.textContent)
      .toContain('状态保存成功 1 项，失败 1 项')
    expect(view.store.tasks.find(item => item.id === first.id)?.status).toBe('done')
    expect(view.store.tasks.find(item => item.id === second.id)?.status).toBe('todo')
    expect((document.activeElement as HTMLElement).dataset.taskId).toBe(second.id)
  })

  it('retains the bulk summary when all changed tasks leave the filter and unmount the list', async () => {
    const tasks = [task('ENG-1'), task('ENG-2')]
    const view = await mountWorkspace(tasks)
    view.store.filterStatusList = ['todo']
    vi.mocked(taskApi.update).mockImplementation(async id => ({
      task: { ...tasks.find(item => item.id === id)!, status: 'in_review', updatedAt: 30 },
      autoCompletedAncestors: []
    }))
    await bulkChangeStatus(view.host, 'in_review')
    expect(view.host.querySelector('.list-view')).toBeNull()
    expect(view.host.querySelector('.status-feedback')?.textContent).toContain('2 项任务的状态保存为「审核中」')
  })
})
