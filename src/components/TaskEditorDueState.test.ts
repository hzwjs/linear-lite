import { createApp, defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '../i18n'
import TaskEditor from './TaskEditor.vue'
import type { Task } from '../types/domain'
import { projectApi } from '../services/api/project'
import { userApi } from '../services/api/user'
import { activityApi } from '../services/api/activity'
import { attachmentsApi } from '../services/api/attachments'
import { taskCommentsApi } from '../services/api/taskComments'
import { taskApi } from '../services/api/task'
import { useIssuePanelStore } from '../store/issuePanelStore'

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: vi.fn()
  })
}))

vi.mock('./TiptapEditor.vue', () => ({
  default: defineComponent({
    name: 'TiptapEditorStub',
    emits: ['ready', 'blur', 'upload-state-change'],
    mounted() {
      this.$emit('ready')
      this.$emit('upload-state-change', { hasPending: false, hasFailed: false })
    },
    template: '<div tabindex="0" data-testid="tiptap-editor-stub"></div>'
  })
}))

vi.mock('./ui/CustomSelect.vue', () => ({
  default: defineComponent({
    name: 'CustomSelectStub',
    props: {
      ariaLabel: { type: String, default: '' }
    },
    template: '<button type="button" class="custom-select-stub">{{ ariaLabel }}</button>'
  })
}))

vi.mock('./ui/CustomDatePicker.vue', () => ({
  default: defineComponent({
    name: 'CustomDatePickerStub',
    props: {
      ariaLabel: { type: String, default: '' }
    },
    template: '<button type="button" class="custom-date-picker-stub">{{ ariaLabel }}</button>'
  })
}))

vi.mock('./TaskLabelCombobox.vue', () => ({
  default: defineComponent({
    name: 'TaskLabelComboboxStub',
    template: '<div class="task-label-combobox-stub"></div>'
  })
}))

vi.mock('./TaskRowStatusPicker.vue', () => ({
  default: defineComponent({
    name: 'TaskRowStatusPickerStub',
    template: '<div class="task-row-status-picker-stub"></div>'
  })
}))

vi.mock('../services/api/project', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    invite: vi.fn(),
    listLabels: vi.fn(),
    listMembers: vi.fn(),
    deleteLabel: vi.fn().mockResolvedValue(undefined)
  }
}))

vi.mock('../services/api/user', () => ({
  userApi: {
    list: vi.fn()
  }
}))

vi.mock('../services/api/activity', () => ({
  activityApi: {
    list: vi.fn()
  }
}))

vi.mock('../services/api/attachments', () => ({
  attachmentsApi: {
    list: vi.fn(),
    upload: vi.fn(),
    download: vi.fn(),
    delete: vi.fn()
  }
}))

vi.mock('../services/api/taskComments', () => ({
  taskCommentsApi: {
    list: vi.fn(),
    create: vi.fn(),
    delete: vi.fn()
  }
}))

vi.mock('../services/api/task', () => ({
  taskApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    listFavorites: vi.fn(),
    addFavorite: vi.fn(),
    removeFavorite: vi.fn()
  }
}))

function flushPromises() {
  return Promise.resolve()
}

function localDate(year: number, month: number, day: number): Date {
  return new Date(year, month - 1, day, 12, 0, 0, 0)
}

function createTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'ENG-1',
    numericId: 1,
    title: 'Task',
    status: 'todo',
    priority: 'medium',
    projectId: 10,
    createdAt: 1,
    updatedAt: 1,
    labels: [],
    ...overrides
  }
}

async function mountEditor(task: Task) {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const pinia = createPinia()
  setActivePinia(pinia)
  const issuePanelStore = useIssuePanelStore()
  issuePanelStore.openWorkspace(task.id, '全部任务')
  const app = createApp(TaskEditor, {
    mode: 'edit',
    task
  })
  app.use(pinia)
  app.use(i18n)
  app.mount(container)
  await nextTick()
  await flushPromises()
  return {
    container,
    unmount: () => {
      app.unmount()
      container.remove()
    }
  }
}

describe('TaskEditor due state', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 3, 10, 12, 0, 0))
    vi.clearAllMocks()
    vi.mocked(userApi.list).mockResolvedValue([])
    vi.mocked(activityApi.list).mockResolvedValue([])
    vi.mocked(attachmentsApi.list).mockResolvedValue([])
    vi.mocked(taskCommentsApi.list).mockResolvedValue([])
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(projectApi.listMembers).mockResolvedValue([])
    vi.mocked(projectApi.listLabels).mockResolvedValue([])
    vi.mocked(taskApi.update).mockResolvedValue(createTask())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('groups sidebar properties into execution, time, and archive blocks', async () => {
    const view = await mountEditor(
      createTask({
        dueDate: localDate(2026, 4, 12).getTime(),
        completedAt: localDate(2026, 4, 9).getTime(),
        labels: [{ id: 1, name: '运维任务' }]
      })
    )

    try {
      const groupTitles = [...view.container.querySelectorAll('.prop-group-title')].map(
        (node) => node.textContent?.trim()
      )
      expect(groupTitles).toEqual(['执行', '时间', '归档'])
      expect(view.container.querySelector('.issue-source')?.textContent).toContain('全部任务')
    } finally {
      view.unmount()
    }
  })

  it('refreshes due helper text after midnight when time advances', async () => {
    vi.setSystemTime(new Date(2026, 3, 10, 23, 59, 0))
    const view = await mountEditor(createTask({ dueDate: localDate(2026, 4, 11).getTime() }))

    try {
      expect(view.container.textContent).toContain('还有 1 天')

      vi.setSystemTime(new Date(2026, 3, 11, 0, 1, 0))
      vi.advanceTimersByTime(60_000)
      await nextTick()

      expect(view.container.textContent).toContain('今天截止')
    } finally {
      view.unmount()
    }
  })

  it('shows due helper text for today', async () => {
    const view = await mountEditor(createTask({ dueDate: localDate(2026, 4, 10).getTime() }))

    try {
      expect(view.container.textContent).toContain('今天截止')
    } finally {
      view.unmount()
    }
  })

  it('shows due helper text for overdue dates', async () => {
    const view = await mountEditor(createTask({ dueDate: localDate(2026, 4, 7).getTime() }))

    try {
      expect(view.container.textContent).toContain('已逾期 3 天')
    } finally {
      view.unmount()
    }
  })
})
