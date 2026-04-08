import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { i18n } from '../i18n'
import { useTaskStore } from '../store/taskStore'
import { parseDateInputValue } from '../utils/taskDate'
import GanttChart from './GanttChart.vue'

type MockGanttInstance = {
  selector: string
  tasks: unknown[]
  options: Record<string, (...args: unknown[]) => unknown>
  refresh: ReturnType<typeof vi.fn>
  clear: ReturnType<typeof vi.fn>
}

const ganttInstances: MockGanttInstance[] = []

vi.mock('frappe-gantt', () => ({
  default: class MockGantt {
    selector: string
    tasks: unknown[]
    options: Record<string, (...args: unknown[]) => unknown>
    refresh = vi.fn((tasks: unknown[]) => {
      this.tasks = tasks
    })
    clear = vi.fn()

    constructor(
      selector: string,
      tasks: unknown[],
      options: Record<string, (...args: unknown[]) => unknown>
    ) {
      this.selector = selector
      this.tasks = tasks
      this.options = options
      ganttInstances.push(this)
    }
  }
}))

async function mountChart(pinia: Pinia) {
  const container = document.createElement('div')
  document.body.appendChild(container)

  setActivePinia(pinia)
  const app = createApp(GanttChart)
  app.use(pinia)
  app.use(i18n)
  app.mount(container)
  await nextTick()
  await Promise.resolve()

  return {
    container,
    unmount() {
      app.unmount()
      container.remove()
    }
  }
}

describe('GanttChart', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    ganttInstances.length = 0
    vi.clearAllMocks()
    vi.useRealTimers()
    setActivePinia(createPinia())
  })

  it('renders top-level tasks (subtasks excluded); undated use createdAt; clears on unmount', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    const undatedCreated = parseDateInputValue('2026-04-10')!
    store.tasks = [
      {
        id: 'ENG-1',
        title: 'Top level',
        status: 'todo',
        priority: 'medium',
        plannedStartDate: parseDateInputValue('2026-04-01'),
        dueDate: parseDateInputValue('2026-04-03'),
        createdAt: 1,
        updatedAt: 3
      },
      {
        id: 'ENG-2',
        title: 'Child',
        status: 'todo',
        priority: 'medium',
        parentId: '101',
        plannedStartDate: parseDateInputValue('2026-04-02'),
        dueDate: parseDateInputValue('2026-04-04'),
        createdAt: 1,
        updatedAt: 2
      },
      {
        id: 'ENG-3',
        title: 'No dates',
        status: 'todo',
        priority: 'medium',
        createdAt: undatedCreated,
        updatedAt: 1
      }
    ]

    const view = await mountChart(pinia)
    try {
      expect(ganttInstances).toHaveLength(1)
      expect(ganttInstances[0]?.tasks).toEqual([
        {
          id: 'ENG-1',
          name: 'Top level',
          start: '2026-04-01',
          end: '2026-04-03',
          progress: 0
        },
        {
          id: 'ENG-3',
          name: 'No dates',
          start: '2026-04-10',
          end: '2026-04-10',
          progress: 0
        }
      ])
    } finally {
      const instance = ganttInstances[0]
      view.unmount()
      expect(instance?.clear).toHaveBeenCalledTimes(1)
    }
  })

  it('re-fetches tasks and refreshes bars when drag persistence fails', async () => {
    vi.useFakeTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.tasks = [
      {
        id: 'ENG-1',
        title: 'Top level',
        status: 'todo',
        priority: 'medium',
        plannedStartDate: parseDateInputValue('2026-04-01'),
        dueDate: parseDateInputValue('2026-04-03'),
        createdAt: 1,
        updatedAt: 3
      }
    ]

    store.updateTask = vi.fn().mockRejectedValue(new Error('network failed'))
    store.fetchTasks = vi.fn().mockImplementation(async () => {
      store.tasks = [
        {
          id: 'ENG-1',
          title: 'Server truth',
          status: 'todo',
          priority: 'medium',
          plannedStartDate: parseDateInputValue('2026-04-05'),
          dueDate: parseDateInputValue('2026-04-07'),
          createdAt: 1,
          updatedAt: 4
        }
      ]
    })

    const view = await mountChart(pinia)
    try {
      const instance = ganttInstances[0]
      expect(instance).toBeTruthy()

      instance!.options.on_date_change?.(
        { id: 'ENG-1' },
        new Date('2026-04-02T00:00:00'),
        new Date('2026-04-04T23:59:59')
      )

      await vi.advanceTimersByTimeAsync(350)
      await nextTick()
      await Promise.resolve()

      expect(store.updateTask).toHaveBeenCalledWith('ENG-1', {
        plannedStartDate: parseDateInputValue('2026-04-02'),
        dueDate: parseDateInputValue('2026-04-04')
      })
      expect(store.fetchTasks).toHaveBeenCalledTimes(1)
      expect(instance!.refresh).toHaveBeenLastCalledWith([
        {
          id: 'ENG-1',
          name: 'Server truth',
          start: '2026-04-05',
          end: '2026-04-07',
          progress: 0
        }
      ])
    } finally {
      view.unmount()
      vi.useRealTimers()
    }
  })
})
