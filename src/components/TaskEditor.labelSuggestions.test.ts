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
    listLabels: vi.fn()
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
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function createTask(): Task {
  return {
    id: 'ENG-1',
    numericId: 1,
    title: 'Task',
    status: 'todo',
    priority: 'medium',
    projectId: 10,
    createdAt: 1,
    updatedAt: 1,
    labels: [{ id: 1, name: '运维任务' }]
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

describe('TaskEditor label suggestions', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(userApi.list).mockResolvedValue([])
    vi.mocked(activityApi.list).mockResolvedValue([])
    vi.mocked(attachmentsApi.list).mockResolvedValue([])
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(taskApi.update).mockImplementation(async (id, payload) => ({
      ...createTask(),
      id,
      ...payload,
      favorited: false
    }))
    vi.mocked(projectApi.listLabels).mockResolvedValue([
      { id: 1, name: '运维任务' },
      { id: 2, name: '运维问题' }
    ])
  })

  it('keeps suggestions open when input blurs after a mousedown inside the labels combobox', async () => {
    const view = await mountEditor(createTask())
    try {
      const labelInput = view.container.querySelector('.prop-label-input') as HTMLInputElement
      expect(labelInput).toBeTruthy()

      labelInput.focus()
      labelInput.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      const labelsEditor = view.container.querySelector('.prop-label-editor') as HTMLElement
      expect(labelsEditor).toBeTruthy()
      labelsEditor.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      labelInput.dispatchEvent(new FocusEvent('blur', { relatedTarget: null }))
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      const labelsRow = view.container.querySelector('.prop-row-labels') as HTMLElement
      const suggestionList = labelsRow.querySelector('.prop-label-suggestions') as HTMLUListElement
      expect(suggestionList).toBeTruthy()
      expect(labelsRow.contains(suggestionList)).toBe(true)
      expect(labelsRow.querySelector('.task-label-combobox')?.contains(suggestionList)).toBe(true)
      expect(labelsRow.nextElementSibling?.classList.contains('prop-row--linear-action')).toBe(true)
    } finally {
      view.unmount()
    }
  })

  it('closes suggestions when focus moves to another control in the sidebar', async () => {
    const view = await mountEditor(createTask())
    try {
      const labelInput = view.container.querySelector('.prop-label-input') as HTMLInputElement
      expect(labelInput).toBeTruthy()

      labelInput.focus()
      labelInput.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      const dueDateTrigger = view.container.querySelector('#task-due') as HTMLElement
      expect(dueDateTrigger).toBeTruthy()
      dueDateTrigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      dueDateTrigger.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.container.querySelector('.prop-label-suggestions')).toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('selects suggestion, writes label, and closes the list', async () => {
    const view = await mountEditor(createTask())
    try {
      const labelInput = view.container.querySelector('.prop-label-input') as HTMLInputElement
      expect(labelInput).toBeTruthy()

      labelInput.focus()
      labelInput.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      const suggestion = view.container.querySelectorAll('.prop-label-suggestion')[1] as HTMLElement
      expect(suggestion).toBeTruthy()
      suggestion.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维任务')
      expect(document.body.textContent).toContain('运维问题')
      expect(view.container.querySelector('.prop-label-suggestions')).toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('closes suggestions after focus leaves the sidebar', async () => {
    const view = await mountEditor(createTask())
    const outsideButton = document.createElement('button')
    outsideButton.type = 'button'
    outsideButton.textContent = 'outside'
    document.body.appendChild(outsideButton)

    try {
      const labelInput = view.container.querySelector('.prop-label-input') as HTMLInputElement
      expect(labelInput).toBeTruthy()

      labelInput.focus()
      labelInput.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      outsideButton.focus()
      await nextTick()
      await flushPromises()

      const suggestionList = document.body.querySelector('.prop-label-suggestions')
      expect(suggestionList).toBeNull()
    } finally {
      outsideButton.remove()
      view.unmount()
    }
  })
})
