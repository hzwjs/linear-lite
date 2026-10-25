import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { useProjectStore } from '../store/projectStore'
import type { Task } from '../types/domain'
import type { TaskGroup } from '../utils/taskView'
import TaskListView from './TaskListView.vue'

vi.mock('./TaskRowStatusPicker.vue', () => ({ __esModule: true, default: { template: '<span />' } }))
vi.mock('./TaskRowAssigneePicker.vue', () => ({ __esModule: true, default: { template: '<span />' } }))
vi.mock('./TaskRowInlineDateCell.vue', () => ({ __esModule: true, default: { template: '<span />' } }))
vi.mock('./TaskRowProgressCell.vue', () => ({ __esModule: true, default: { template: '<span />' } }))
vi.mock('./CommandPalette.vue', () => ({ __esModule: true, default: { template: '<div />' } }))

type Harness = {
  host: HTMLElement
  vm: {
    replaceGroups(groups: TaskGroup[]): void
    locateTask(taskId: string): Promise<boolean>
  }
  unmount(): void
}

const originalClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight')
const originalRect = HTMLElement.prototype.getBoundingClientRect
const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

function makeTask(index: number, status: Task['status'] = 'todo'): Task {
  const id = `TASK-${String(index).padStart(4, '0')}`
  return {
    id,
    title: id,
    status,
    priority: 'medium',
    projectId: 7,
    createdAt: index,
    updatedAt: index
  }
}

function makeGroup(key: string, tasks: Task[]): TaskGroup {
  return { key, label: key, tasks, rows: tasks.map((task) => ({ task, depth: 0 })) }
}

async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve()
    await nextTick()
  }
  await new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountList(groups: TaskGroup[], grouped = false, expandGroupsOnEntry = true): Promise<Harness> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useProjectStore().setActiveProject(7)

  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = defineComponent({
    setup(_, { expose }) {
      const currentGroups = ref(groups)
      const child = ref<InstanceType<typeof TaskListView> | null>(null)
      expose({
        replaceGroups: (next: TaskGroup[]) => { currentGroups.value = next },
        locateTask: (taskId: string) => child.value?.locateTask(taskId) ?? Promise.resolve(false)
      })
      return () => h(TaskListView, {
        ref: child,
        groups: currentGroups.value,
        grouped,
        expandGroupsOnEntry,
        projectId: 7,
        users: [],
        visibleProperties: []
      })
    }
  })
  const app = createApp(wrapper)
  app.use(pinia).use(i18n)
  const vm = app.mount(host) as Harness['vm']
  await flush()
  return { host, vm, unmount: () => { app.unmount(); host.remove() } }
}

describe('TaskListView visible-window rendering', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'zh-CN'
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get: () => 420
    })
    HTMLElement.prototype.getBoundingClientRect = () => ({
      x: 0, y: 0, width: 1000, height: 420, top: 0, right: 1000,
      bottom: 420, left: 0, toJSON: () => ({})
    }) as DOMRect
    HTMLElement.prototype.scrollIntoView = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    if (originalClientHeight) Object.defineProperty(HTMLElement.prototype, 'clientHeight', originalClientHeight)
    else delete (HTMLElement.prototype as Partial<HTMLElement>).clientHeight
    HTMLElement.prototype.getBoundingClientRect = originalRect
    HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  it('mounts a bounded initial window and renders the final row after scrolling to the end', async () => {
    const tasks = Array.from({ length: 1000 }, (_, index) => makeTask(index))
    const view = await mountList([makeGroup('all', tasks)])
    try {
      expect(view.host.querySelectorAll('[data-task-id]').length).toBeLessThan(80)

      const scrollContainer = view.host.querySelector<HTMLElement>('.list-view-scroll')!
      scrollContainer.scrollTop = 42_000
      scrollContainer.dispatchEvent(new Event('scroll'))
      await flush()

      expect([...view.host.querySelectorAll<HTMLElement>('[data-task-id]')]
        .some((row) => row.dataset.taskId === 'TASK-0999')).toBe(true)
    } finally {
      view.unmount()
    }
  })

  it('locates and focuses a distant task, then follows its ID after sorting changes', async () => {
    const tasks = Array.from({ length: 1000 }, (_, index) => makeTask(index))
    const view = await mountList([makeGroup('all', tasks)])
    try {
      expect(await view.vm.locateTask('TASK-0999')).toBe(true)
      await flush()
      let target = view.host.querySelector<HTMLElement>('[data-task-id="TASK-0999"]')
      expect(target).not.toBeNull()
      expect(document.activeElement).toBe(target)

      view.vm.replaceGroups([makeGroup('all', [...tasks].reverse())])
      await flush()
      expect(await view.vm.locateTask('TASK-0999')).toBe(true)
      await flush()
      target = view.host.querySelector<HTMLElement>('[data-task-id="TASK-0999"]')
      expect(target).not.toBeNull()
      expect(document.activeElement).toBe(target)
    } finally {
      view.unmount()
    }
  })

  it('keeps the focused row pinned while dragging the scrollbar and releases it after focus leaves', async () => {
    const tasks = Array.from({ length: 1000 }, (_, index) => makeTask(index))
    const view = await mountList([makeGroup('all', tasks)])
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    try {
      const firstRow = view.host.querySelector<HTMLElement>('[data-task-id="TASK-0000"]')!
      firstRow.focus()
      await flush()

      const scrollRoot = view.host.querySelector<HTMLElement>('.list-view-scroll')!
      scrollRoot.dispatchEvent(new Event('pointerdown', { bubbles: true }))
      scrollRoot.scrollTop = 42_000
      scrollRoot.dispatchEvent(new Event('scroll'))
      await flush()

      expect(view.host.querySelector('[data-task-id="TASK-0000"]')).toBe(firstRow)
      expect(document.activeElement).toBe(firstRow)
      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).not.toBeNull()
      expect(view.host.querySelectorAll('[data-task-id]').length).toBeLessThan(80)

      outside.focus()
      await flush()
      expect(view.host.querySelector('[data-task-id="TASK-0000"]')).toBeNull()
    } finally {
      outside.remove()
      view.unmount()
    }
  })

  it('keeps collapsed groups free of task rows and expands a distant group during locate', async () => {
    const firstTask = makeTask(1, 'todo')
    const distantTask = makeTask(2, 'in_progress')
    const view = await mountList([
      makeGroup('todo', [firstTask]),
      makeGroup('in_progress', [distantTask])
    ], true, false)
    try {
      expect(view.host.querySelector('[data-task-id="TASK-0002"]')).toBeNull()

      expect(await view.vm.locateTask('TASK-0002')).toBe(true)
      await flush()
      const target = view.host.querySelector<HTMLElement>('[data-task-id="TASK-0002"]')
      expect(target).not.toBeNull()
      expect(document.activeElement).toBe(target)
    } finally {
      view.unmount()
    }
  })
})
