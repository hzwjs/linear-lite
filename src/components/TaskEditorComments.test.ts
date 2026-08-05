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
import { useTaskStore } from '../store/taskStore'

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: vi.fn()
  })
}))

vi.mock('../utils/mermaidHydrate', () => ({
  runMermaidIn: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('./BlockNoteEditorWrapper.vue', () => ({
  default: defineComponent({
    name: 'BlockNoteEditorStub',
    props: {
      modelValue: { type: String, default: '' },
      placeholder: { type: String, default: '' },
      mentionMembers: { type: Array, default: undefined }
    },
    emits: ['ready', 'focus', 'blur', 'upload-state-change', 'update:modelValue'],
    mounted() {
      this.$emit('ready')
      this.$emit('upload-state-change', { hasPending: false, hasFailed: false })
    },
    template:
      "<textarea data-testid=\"tiptap-editor-stub\" :placeholder=\"placeholder\" :value=\"modelValue\" @focus=\"$emit('focus')\" @blur=\"$emit('blur')\" @input=\"$emit('update:modelValue', $event.target.value)\" />"
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
  useTaskStore().tasks = [task]
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

async function mountEditorHost(task: Task) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const pinia = createPinia()
  setActivePinia(pinia)
  useTaskStore().tasks = [task]
  const Host = defineComponent({
    components: { TaskEditor },
    data() {
      return { currentTask: task as Task }
    },
    template: '<TaskEditor mode="edit" :task="currentTask" />'
  })
  const app = createApp(Host)
  app.use(pinia)
  app.use(i18n)
  const vm = app.mount(host) as { currentTask: Task }
  await nextTick()
  await flushPromises()
  return {
    host,
    vm,
    app,
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('TaskEditor comments adapter', () => {
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
      deletable: false,
      parentId: null,
      rootId: null,
      depth: 0
    })
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(projectApi.listMembers).mockResolvedValue([{ id: 2, username: 'Alice' }])
    vi.mocked(projectApi.listLabels).mockResolvedValue([])
    vi.mocked(taskApi.update).mockResolvedValue({ task: createTask(), autoCompletedAncestors: [] })
  })

  it('passes a root comment from the extracted component to the task API', async () => {
    const view = await mountEditor(createTask())
    try {
      const editor = view.host.querySelector('.comment-compose [data-testid="tiptap-editor-stub"]') as HTMLTextAreaElement
      editor.value = 'Hello'
      editor.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      view.host.querySelector<HTMLButtonElement>('.comment-compose [aria-label="Send"]')?.click()
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', {
        body: 'Hello',
        mentionedUserIds: [],
        parentId: null
      })
    } finally {
      view.unmount()
    }
  })

  it('passes the extracted reply parent and automatic mention to the task API', async () => {
    vi.mocked(taskCommentsApi.list).mockResolvedValue([
      {
        id: 10,
        body: 'Root comment',
        authorName: 'Alice',
        authorId: 2,
        createdAt: '2026-04-10T00:00:00.000Z',
        deletable: true,
        parentId: null,
        rootId: null,
        depth: 0
      }
    ])
    const view = await mountEditor(createTask())
    try {
      view.host.querySelector<HTMLButtonElement>('.task-comment-reply-btn')?.click()
      await nextTick()

      const editor = view.host.querySelector('.task-comment-reply-compose [data-testid="tiptap-editor-stub"]') as HTMLTextAreaElement
      editor.value = 'Nested reply'
      editor.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      view.host.querySelector<HTMLButtonElement>('.task-comment-reply-compose [aria-label="Send"]')?.click()
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', {
        body: 'Nested reply',
        mentionedUserIds: [2],
        parentId: 10
      })
    } finally {
      view.unmount()
    }
  })

  it('passes a deleted comment from the extracted component to the task API', async () => {
    vi.mocked(taskCommentsApi.list).mockResolvedValue([
      {
        id: 10,
        body: 'Root comment',
        authorName: 'Alice',
        authorId: 2,
        createdAt: '2026-04-10T00:00:00.000Z',
        deletable: true,
        parentId: null,
        rootId: null,
        depth: 0
      }
    ])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const view = await mountEditor(createTask())
    try {
      view.host.querySelector<HTMLButtonElement>('.task-comment-delete')?.click()
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.delete).toHaveBeenCalledWith('ENG-1', 10)
    } finally {
      view.unmount()
    }
  })
})
