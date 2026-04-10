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

function getAttachmentSection(container: HTMLElement) {
  const sections = [...container.querySelectorAll('.linear-section')]
  const section = sections.find((node) => node.textContent?.includes('附件'))
  if (!section) throw new Error('attachment section not found')
  return section
}

describe('TaskEditor attachments', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(userApi.list).mockResolvedValue([])
    vi.mocked(activityApi.list).mockResolvedValue([])
    vi.mocked(taskCommentsApi.list).mockResolvedValue([])
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(projectApi.listMembers).mockResolvedValue([])
    vi.mocked(projectApi.listLabels).mockResolvedValue([])
    vi.mocked(taskApi.update).mockResolvedValue(createTask())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps the attachments section compact when there are no attachments', async () => {
    vi.mocked(attachmentsApi.list).mockResolvedValue([])

    const view = await mountEditor(createTask())
    try {
      const section = getAttachmentSection(view.container)
      expect(section.querySelector('button.linear-section-head')).toBeNull()
      expect(section.querySelector('.linear-section-chevron')).toBeNull()
      expect(section.querySelector('.linear-section-body')).toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('shows attachment rows and still lets the section collapse and expand', async () => {
    vi.mocked(attachmentsApi.list).mockResolvedValue([
      {
        id: 7,
        fileName: 'spec.md',
        fileSize: 1024,
        url: 'https://example.test/attachments/7',
        createdAt: '2026-04-10T00:00:00.000Z'
      }
    ])

    const view = await mountEditor(createTask())
    try {
      const section = getAttachmentSection(view.container)
      expect(section.textContent).toContain('spec.md')
      expect(section.querySelector('.linear-sub-list')).toBeTruthy()

      const toggle = section.querySelector('.linear-section-head') as HTMLButtonElement
      expect(toggle).toBeTruthy()
      toggle.click()
      await nextTick()
      await flushPromises()
      expect((section.querySelector('.linear-section-body') as HTMLElement | null)?.style.display).toBe('none')

      toggle.click()
      await nextTick()
      await flushPromises()
      expect((section.querySelector('.linear-section-body') as HTMLElement | null)?.style.display).not.toBe('none')
    } finally {
      view.unmount()
    }
  })
})
