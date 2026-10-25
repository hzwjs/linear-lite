import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Task } from '../types/domain'
import type { TaskRow } from '../utils/taskView'
import TaskListWindowRows from './TaskListWindowRows.vue'

type Harness = {
  host: HTMLElement
  root: HTMLElement
  vm: {
    setLayout(layoutKey: string, logicalTop: number): void
    setPinnedTaskId(taskId: string | null): void
    reverseRows(): void
    ensureTaskVisible(taskId: string): Promise<boolean>
  }
  unmount(): void
}

const originalRect = HTMLElement.prototype.getBoundingClientRect

function makeRows(count: number): TaskRow[] {
  return Array.from({ length: count }, (_, index) => {
    const id = `TASK-${String(index).padStart(4, '0')}`
    const task: Task = {
      id,
      title: id,
      status: 'todo',
      priority: 'medium',
      projectId: 7,
      createdAt: index,
      updatedAt: index
    }
    return { task, depth: 0 }
  })
}

async function flush() {
  for (let index = 0; index < 6; index++) await nextTick()
}

async function mountWindow(rows: TaskRow[], initialLogicalTop = 0): Promise<Harness> {
  const root = document.createElement('div')
  document.body.appendChild(root)
  Object.defineProperty(root, 'clientHeight', { configurable: true, value: 420 })
  const host = document.createElement('div')
  root.appendChild(host)
  let logicalTop = initialLogicalTop

  HTMLElement.prototype.getBoundingClientRect = function () {
    const top = this === root ? 0 : logicalTop - root.scrollTop
    return {
      x: 0, y: top, width: 1000, height: this === root ? 420 : rows.length * 42,
      top, right: 1000, bottom: top + (this === root ? 420 : rows.length * 42),
      left: 0, toJSON: () => ({})
    } as DOMRect
  }

  const wrapper = defineComponent({
    setup(_, { expose }) {
      const layoutKey = ref('layout-0')
      const topInset = ref(0)
      const pinnedTaskId = ref<string | null>(null)
      const currentRows = ref(rows)
      const child = ref<InstanceType<typeof TaskListWindowRows> | null>(null)
      expose({
        setLayout: (key: string, top: number) => {
          logicalTop = top
          layoutKey.value = key
        },
        setPinnedTaskId: (taskId: string | null) => { pinnedTaskId.value = taskId },
        reverseRows: () => { currentRows.value = [...currentRows.value].reverse() },
        ensureTaskVisible: (taskId: string) => child.value?.ensureTaskVisible(taskId) ?? Promise.resolve(false)
      })
      return () => h(TaskListWindowRows, {
        ref: child,
        rows: currentRows.value,
        scrollRoot: root,
        rowHeight: 42,
        layoutKey: layoutKey.value,
        topInset: topInset.value,
        pinnedTaskId: pinnedTaskId.value
      }, {
        default: ({ row }: { row: TaskRow }) => h('div', { 'data-task-id': row.task.id })
      })
    }
  })
  const app = createApp(wrapper)
  const vm = app.mount(host) as unknown as Harness['vm']
  await flush()
  return { host, root, vm, unmount: () => { app.unmount(); root.remove() } }
}

describe('TaskListWindowRows fixed-height window', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
  })

  afterEach(() => {
    HTMLElement.prototype.getBoundingClientRect = originalRect
    vi.unstubAllGlobals()
  })

  it('uses the mounted group position before rendering the initial window', async () => {
    const view = await mountWindow(makeRows(1000), 1500)
    try {
      expect(view.host.querySelectorAll('[data-task-id]')).toHaveLength(0)
    } finally {
      view.unmount()
    }
  })

  it('recalculates the visible window when layout moves the group without scrolling', async () => {
    const view = await mountWindow(makeRows(1000))
    try {
      expect(view.host.querySelectorAll('[data-task-id]').length).toBeGreaterThan(0)
      view.vm.setLayout('layout-1', 1500)
      await flush()

      expect(view.host.querySelectorAll('[data-task-id]')).toHaveLength(0)
    } finally {
      view.unmount()
    }
  })

  it('keeps a distant pinned row mounted, then releases it with a bounded window', async () => {
    const view = await mountWindow(makeRows(1000))
    try {
      view.vm.setPinnedTaskId('TASK-0999')
      await flush()
      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).not.toBeNull()
      expect(view.host.querySelectorAll('[data-task-id]').length).toBeLessThan(40)

      view.vm.setPinnedTaskId(null)
      await flush()
      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).toBeNull()
      expect(view.host.querySelectorAll('[data-task-id]').length).toBeLessThan(40)
    } finally {
      view.unmount()
    }
  })

  it('makes a distant task visible on demand', async () => {
    const view = await mountWindow(makeRows(1000))
    try {
      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).toBeNull()
      expect(await view.vm.ensureTaskVisible('TASK-0999')).toBe(true)
      await flush()
      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).not.toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('keeps the target visible when sorting changes during pending location', async () => {
    const rows = makeRows(1000)
    const view = await mountWindow(rows)
    try {
      const pending = view.vm.ensureTaskVisible('TASK-0999')
      view.vm.reverseRows()
      expect(await pending).toBe(true)
      await flush()

      expect(view.host.querySelector('[data-task-id="TASK-0999"]')).not.toBeNull()
      expect(view.root.scrollTop).toBe(0)
    } finally {
      view.unmount()
    }
  })
})
