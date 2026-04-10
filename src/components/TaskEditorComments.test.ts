import { createApp, defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: vi.fn()
  })
}))

vi.mock('./TiptapEditor.vue', () => ({
  default: defineComponent({
    name: 'TiptapEditorStub',
    props: {
      modelValue: { type: String, default: '' },
      placeholder: { type: String, default: '' },
      mentionMembers: { type: Array, default: undefined }
    },
    emits: ['ready', 'blur', 'upload-state-change', 'update:modelValue'],
    mounted() {
      this.$emit('ready')
      this.$emit('upload-state-change', { hasPending: false, hasFailed: false })
    },
    template:
      "<textarea data-testid=\"tiptap-editor-stub\" :value=\"modelValue\" @input=\"$emit('update:modelValue', $event.target.value)\" />"
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
  const host = document.createElement('div')
  document.body.appendChild(host)
  const pinia = createPinia()
  setActivePinia(pinia)
  const app = createApp(TaskEditor, {
    mode: 'edit',
    task
  })
  app.use(pinia)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  await flushPromises()
  return {
    host,
    app,
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('TaskEditor comments', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(userApi.list).mockResolvedValue([{ id: 2, username: 'Alice' }])
    vi.mocked(activityApi.list).mockResolvedValue([])
    vi.mocked(attachmentsApi.list).mockResolvedValue([])
    vi.mocked(taskCommentsApi.list).mockResolvedValue([])
    vi.mocked(taskCommentsApi.create).mockResolvedValue({
      id: 1,
      body: 'Hello',
      authorName: 'Tester',
      authorId: 1,
      createdAt: '2026-04-10T00:00:00.000Z',
      deletable: false
    })
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(projectApi.listMembers).mockResolvedValue([{ id: 2, username: 'Alice' }])
    vi.mocked(projectApi.listLabels).mockResolvedValue([])
    vi.mocked(taskApi.update).mockResolvedValue(createTask())
  })

  it('shows a shortcut hint and a clearer notify-members prompt', async () => {
    const view = await mountEditor(createTask())
    try {
      expect(view.host.textContent).toContain('Cmd+Enter')
      expect(view.host.textContent).toContain('Notify members')
    } finally {
      view.unmount()
    }
  })

  it('submits a comment when Cmd+Enter or Ctrl+Enter is pressed in the editor', async () => {
    const view = await mountEditor(createTask())
    try {
      const inst = view.app._instance as unknown as { setupState?: Record<string, unknown> } | null
      const state = inst?.setupState
      expect(state).toBeTruthy()
      if (!state) throw new Error('TaskEditor instance not found')

      state.commentBody = 'Hello'
      await nextTick()

      const compose = view.host.querySelector('.comment-compose') as HTMLElement
      expect(compose).toBeTruthy()
      compose.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true, cancelable: true })
      )
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', 'Hello', [])
      expect(state.commentBody).toBe('')

      state.commentBody = 'World'
      await nextTick()

      compose.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true })
      )
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenLastCalledWith('ENG-1', 'World', [])
    } finally {
      view.unmount()
    }
  })
})
