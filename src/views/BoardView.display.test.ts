import { createApp, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import BoardView from './BoardView.vue'

vi.mock('./BoardViewContent.vue', () => ({
  __esModule: true,
  default: { template: '<div class="board-view-content-stub" />' }
}))
vi.mock('../components/issue-filters/AddIssueFilterMenu.vue', () => ({
  __esModule: true,
  default: { template: '<div class="issue-filter-menu-stub" />' }
}))
vi.mock('../services/api/project', () => ({
  projectApi: { listAssignees: vi.fn().mockResolvedValue([]), listMembers: vi.fn().mockResolvedValue([]) }
}))

let cleanup: (() => void) | undefined

async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve()
    await nextTick()
  }
  await new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountBoard() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const projectStore = useProjectStore()
  projectStore.setActiveProject(7)
  const taskStore = useTaskStore()
  taskStore.fetchTasks = vi.fn().mockResolvedValue(undefined)
  const viewStore = useViewModeStore()
  viewStore.setView('list')
  viewStore.setScope('active')

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }]
  })
  await router.push('/')
  await router.isReady()
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(BoardView)
  app.use(pinia).use(i18n).use(router)
  app.mount(host)
  cleanup = () => { app.unmount(); host.remove() }
  await flush()
  return { host, taskStore, viewStore }
}

function openDisplay(host: HTMLElement) {
  host.querySelector<HTMLButtonElement>('.toolbar-options button[aria-label="显示"]')!.click()
}

describe('BoardView display controls', () => {
  beforeEach(() => {
    localStorage.clear()
    i18n.global.locale.value = 'zh-CN'
  })

  afterEach(() => {
    cleanup?.()
    cleanup = undefined
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('keeps status grouping available and applies it to the list preference', async () => {
    const { host, viewStore } = await mountBoard()
    openDisplay(host)
    await nextTick()

    const groupSelect = host.querySelector<HTMLButtonElement>('.popover-display .custom-select-trigger')!
    groupSelect.click()
    await nextTick()
    const listboxId = groupSelect.getAttribute('aria-controls')!
    const listbox = document.getElementById(listboxId)!
    const statusOption = [...listbox.querySelectorAll<HTMLButtonElement>('[role="option"]')]
      .find((option) => option.textContent?.trim() === '状态')
    expect(statusOption).toBeTruthy()
    statusOption!.click()
    await nextTick()

    expect(viewStore.viewConfig.listGroupBy).toBe('status')
  })

  it('does not show a duplicate status field in the task display checkboxes', async () => {
    const { host } = await mountBoard()
    openDisplay(host)
    await nextTick()

    const fieldLabels = [...host.querySelectorAll<HTMLElement>('.popover-display-options .popover-display-option')]
      .map((label) => label.textContent?.trim())
    expect(fieldLabels).not.toContain('状态')
  })

  it('keeps all scope tabs flat without refetching tasks', async () => {
    const { host, taskStore, viewStore } = await mountBoard()
    expect(taskStore.fetchTasks).toHaveBeenCalledTimes(1)
    vi.mocked(taskStore.fetchTasks).mockClear()

    const scopeTabs = [...host.querySelectorAll<HTMLButtonElement>('.scope-tab')]
    for (const label of ['全部任务', '待规划', '进行中']) {
      scopeTabs.find((tab) => tab.textContent?.trim() === label)!.click()
      await nextTick()
      expect(viewStore.effectiveViewConfig.groupBy).toBe('none')
    }

    expect(taskStore.fetchTasks).not.toHaveBeenCalled()
  })

  it('filters by a status count and clears only status without changing scope or refetching', async () => {
    const { host, taskStore, viewStore } = await mountBoard()
    taskStore.tasks = ['todo', 'in_progress', 'in_review'].map((status, index) => ({
      id: `ENG-${index}`, title: 'Release', projectId: 7, status: status as 'todo' | 'in_progress' | 'in_review',
      priority: 'high', assigneeKey: 'external:1', assigneeName: 'Sam', labels: [{ id: 1, name: 'Product' }],
      createdAt: index, updatedAt: index
    }))
    taskStore.searchQuery = 'Release'
    taskStore.filterPriorityList = ['high']
    taskStore.filterAssigneeList = ['external:1']
    taskStore.filterLabelIds = [1]
    vi.mocked(taskStore.fetchTasks).mockClear()
    await nextTick()
    const button = (text: string) => [...host.querySelectorAll<HTMLButtonElement>('.status-navigation button')]
      .find((item) => item.textContent?.includes(text))!
    expect(button('全部状态').textContent).toContain('3')
    button('审核中').click()
    await nextTick()
    expect(taskStore.filterStatusList).toEqual(['in_review'])
    expect(button('审核中').getAttribute('aria-pressed')).toBe('true')
    expect(button('待处理').textContent).toContain('1')
    button('全部状态').click()
    await nextTick()
    expect(taskStore.filterStatusList).toEqual([])
    expect(taskStore.searchQuery).toBe('Release')
    expect(taskStore.filterPriorityList).toEqual(['high'])
    expect(taskStore.filterAssigneeList).toEqual(['external:1'])
    expect(taskStore.filterLabelIds).toEqual([1])
    expect(viewStore.viewConfig.scope).toBe('active')
    expect(viewStore.effectiveViewConfig.groupBy).toBe('none')
    expect(taskStore.fetchTasks).not.toHaveBeenCalled()
  })

  it('reflects existing multi-status filters and keeps backlog scope after clearing status', async () => {
    const { host, taskStore, viewStore } = await mountBoard()
    taskStore.filterStatusList = ['todo', 'in_review']
    await nextTick()
    expect(host.querySelectorAll('.status-navigation [aria-pressed="true"]')).toHaveLength(2)
    viewStore.setScope('backlog')
    taskStore.filterStatusList = ['backlog']
    await nextTick()
    host.querySelector<HTMLButtonElement>('.status-navigation-total')!.click()
    await nextTick()
    expect(viewStore.viewConfig.scope).toBe('backlog')
    expect(taskStore.filterStatusList).toEqual([])
    expect(host.querySelectorAll('.status-navigation button')).toHaveLength(2)
    expect(host.querySelector('.status-navigation')).not.toBeNull()
    expect(host.querySelector('.board-view-content-stub .status-navigation')).toBeNull()
  })
})
