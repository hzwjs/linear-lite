import { createApp, defineComponent, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import type { Task } from '../types/domain'
import type { TaskGroup } from '../utils/taskView'
import { useTaskStore } from '../store/taskStore'
import TaskListView from './TaskListView.vue'

vi.mock('./TaskRowStatusPicker.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['taskId', 'status'],
      emits: ['change'],
      setup(_props, { emit }) {
        return () => h('button', {
          'data-status-picker': '',
          onClick: (event: MouseEvent) => {
            event.stopPropagation()
            emit('change', 'in_review')
          }
        }, 'Change status')
      }
    })
  }
})

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

function makeTask(id: string, status: Task['status'] = 'todo'): Task {
  return {
    id,
    title: `Task ${id}`,
    status,
    priority: 'medium',
    projectId: 7,
    createdAt: 1,
    updatedAt: 1
  }
}

function makeGroup(key: string, tasks: Task[], rows = tasks.map((task) => ({ task, depth: 0 }))): TaskGroup {
  return { key, label: key, tasks, rows }
}

const EmptyStub = defineComponent({
  setup() {
    return () => null
  }
})

async function mountList(groups: TaskGroup[], grouped: boolean, expandGroupsOnEntry = true, selectedTaskId: string | null = null) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(TaskListView, {
    groups,
    grouped,
    expandGroupsOnEntry,
    projectId: 7,
    users: [],
    visibleProperties: ['status'],
    selectedTaskId,
    openingTaskId: null
  })
  app.use(createPinia())
  app.use(i18n)
  for (const name of [
    'TaskRowAssigneePicker',
    'TaskRowInlineDateCell',
    'TaskRowProgressCell',
    'CommandPalette'
  ]) app.component(name, EmptyStub)
  const vm = app.mount(host) as unknown as { locateTask: (taskId: string, focus?: boolean) => Promise<boolean> }
  await nextTick()
  return {
    host,
    vm,
    app,
    store: useTaskStore(),
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('TaskListView active-list interactions', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    i18n.global.locale.value = 'zh-CN'
    HTMLElement.prototype.scrollIntoView = vi.fn()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    document.body.innerHTML = ''
    if (originalScrollIntoView) HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView
    vi.restoreAllMocks()
  })

  it('renders every group row without group headings in the flat list', async () => {
    const groups = [makeGroup('todo', [makeTask('ENG-1')]), makeGroup('in_review', [makeTask('ENG-2', 'in_review')])]
    const view = await mountList(groups, false)
    try {
      expect(view.host.querySelectorAll('.group-header')).toHaveLength(0)
      expect([...view.host.querySelectorAll<HTMLElement>('[data-task-id]')].map((row) => row.dataset.taskId))
        .toEqual(['ENG-1', 'ENG-2'])
      expect(view.host.querySelectorAll('.task-meta-status')).toHaveLength(0)
      expect(view.host.querySelectorAll('[data-status-picker]')).toHaveLength(2)
    } finally {
      view.unmount()
    }
  })

  it('keeps groups independently expanded and collapsed when grouping is enabled', async () => {
    const groups = [makeGroup('todo', [makeTask('ENG-1')]), makeGroup('in_review', [makeTask('ENG-2', 'in_review')])]
    const view = await mountList(groups, true)
    try {
      expect(view.host.querySelectorAll('.group-header')).toHaveLength(2)
      expect(view.host.querySelectorAll('.task-row')).toHaveLength(2)
      const firstGroup = view.host.querySelectorAll('.group')[0]!
      ;(firstGroup.querySelector('.group-toggle') as HTMLButtonElement).click()
      await nextTick()
      expect(firstGroup.querySelector('.group-rows')).toBeNull()
      expect(view.host.querySelectorAll('.group')[1]?.querySelector('.group-rows')).toBeTruthy()
    } finally {
      view.unmount()
    }
  })

  it('starts the all-task grouping with one group mounted and allows opening another', async () => {
    const groups = [makeGroup('todo', [makeTask('ENG-1')]), makeGroup('done', [makeTask('ENG-2', 'done')])]
    const view = await mountList(groups, true, false)
    try {
      expect(view.host.querySelectorAll('.group-header')).toHaveLength(2)
      expect(view.host.querySelector('[data-task-id="ENG-1"]')).toBeTruthy()
      expect(view.host.querySelector('[data-task-id="ENG-2"]')).toBeNull()
      ;(view.host.querySelectorAll('.group-toggle')[1] as HTMLButtonElement).click()
      await nextTick()
      expect(view.host.querySelectorAll('.task-row')).toHaveLength(2)
    } finally {
      view.unmount()
    }
  })

  it('opens the selected task group when entering the all-task scope', async () => {
    const groups = [makeGroup('todo', [makeTask('ENG-1')]), makeGroup('done', [makeTask('ENG-2', 'done')])]
    const view = await mountList(groups, true, false, 'ENG-2')
    try {
      expect(view.host.querySelector('[data-task-id="ENG-1"]')).toBeNull()
      expect(view.host.querySelector('[data-task-id="ENG-2"]')).toBeTruthy()
    } finally {
      view.unmount()
    }
  })

  it('opens a collapsed group and focuses the task at its sorted position', async () => {
    const first = makeTask('ENG-1')
    const moved = makeTask('ENG-2', 'in_review')
    const group = makeGroup('in_review', [moved], [{ task: moved, depth: 0 }])
    const view = await mountList([makeGroup('todo', [first]), group], true)
    try {
      const toggle = view.host.querySelectorAll('.group-toggle')[1] as HTMLButtonElement
      toggle.click()
      await nextTick()
      expect(view.host.querySelector('[data-task-id="ENG-2"]')).toBeNull()

      expect(await view.vm.locateTask('ENG-2')).toBe(true)
      const row = view.host.querySelector('[data-task-id="ENG-2"]') as HTMLElement
      expect(row).toBeTruthy()
      expect(document.activeElement).toBe(row)
      expect(row.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
    } finally {
      view.unmount()
    }
  })

  it('expands ancestor rows before locating a nested task', async () => {
    const parent = makeTask('ENG-1')
    const child = { ...makeTask('ENG-2'), parentId: '101', numericId: 102 }
    const group = makeGroup('todo', [parent, child], [
      { task: parent, depth: 0 },
      { task: child, depth: 1, parentTitle: parent.title, subtaskExpandPath: ['ENG-1'] }
    ])
    const view = await mountList([group], false)
    try {
      expect(view.host.querySelector('[data-task-id="ENG-2"]')).toBeNull()
      expect(await view.vm.locateTask('ENG-2')).toBe(true)
      expect((view.host.querySelector('[data-task-id="ENG-2"]') as HTMLElement).closest('.group-rows') as HTMLElement | null)
        .toBeTruthy()
    } finally {
      view.unmount()
    }
  })

  it('contains a rejected status save without an unhandled rejection', async () => {
    const task = makeTask('ENG-1')
    const view = await mountList([makeGroup('todo', [task])], false)
    const transition = vi.fn().mockRejectedValue(new Error('save failed'))
    view.store.transitionTask = transition
    try {
      ;(view.host.querySelector('[data-status-picker]') as HTMLButtonElement).click()
      await Promise.resolve()
      await nextTick()
      expect(transition).toHaveBeenCalledWith('ENG-1', 'in_review')
      expect(view.host.querySelector('[data-task-id="ENG-1"]')).toBeTruthy()
    } finally {
      view.unmount()
    }
  })
})
