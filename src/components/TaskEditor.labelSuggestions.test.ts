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
import { useTaskStore } from '../store/taskStore'

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
    listMembers: vi.fn(),
    listLabels: vi.fn(),
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
  useTaskStore().tasks = [task]
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
    vi.mocked(projectApi.listMembers).mockResolvedValue([])
    vi.mocked(activityApi.list).mockResolvedValue([])
    vi.mocked(attachmentsApi.list).mockResolvedValue([])
    vi.mocked(taskApi.list).mockResolvedValue([])
    vi.mocked(taskApi.update).mockImplementation(async (id, payload) => {
      const labels = payload.labels?.map((label) => {
        if ('id' in label) {
          const labelId = Number(label.id)
          return { id: labelId, name: labelId === 1 ? '运维任务' : '运维问题' }
        }
        return { id: 99, name: label.name }
      })
      return {
        task: {
          ...createTask(),
          id,
          ...payload,
          ...(labels ? { labels } : {}),
          favorited: false
        },
        autoCompletedAncestors: []
      }
    })
    vi.mocked(projectApi.listLabels).mockResolvedValue([
      { id: 1, name: '运维任务' },
      { id: 2, name: '运维问题' }
    ])
  })

  it('opens the label picker inside the labels property row', async () => {
    const view = await mountEditor(createTask())
    try {
      const trigger = view.container.querySelector('.label-trigger') as HTMLButtonElement
      expect(trigger).toBeTruthy()
      trigger.click()
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      const labelsRow = view.container.querySelector('.prop-row-labels') as HTMLElement
      const suggestionList = labelsRow.querySelector('.label-option-list') as HTMLUListElement
      expect(suggestionList).toBeTruthy()
      expect(labelsRow.contains(suggestionList)).toBe(true)
      expect(labelsRow.querySelector('.task-label-combobox')?.contains(suggestionList)).toBe(true)
    } finally {
      view.unmount()
    }
  })

  it('closes suggestions when focus moves to another control in the sidebar', async () => {
    const view = await mountEditor(createTask())
    try {
      const trigger = view.container.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      const dueDateTrigger = view.container.querySelector('#task-due') as HTMLElement
      expect(dueDateTrigger).toBeTruthy()
      dueDateTrigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      dueDateTrigger.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.container.querySelector('.label-popover')).toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('batches label edits while open and skips saving when selection returns to the original state', async () => {
    const view = await mountEditor(createTask())
    const outsideButton = document.createElement('button')
    document.body.appendChild(outsideButton)
    try {
      const trigger = view.container.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      const option = Array.from(view.container.querySelectorAll('[role="option"]')).find((row) =>
        row.textContent?.includes('运维问题')
      )
      const suggestion = option as HTMLButtonElement
      expect(suggestion).toBeTruthy()
      suggestion.click()
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维任务')
      expect(document.body.textContent).toContain('运维问题')
      expect(view.container.querySelector('.label-popover')).toBeTruthy()
      expect(taskApi.update).not.toHaveBeenCalled()

      suggestion.click()
      await nextTick()
      await flushPromises()
      expect(taskApi.update).not.toHaveBeenCalled()

      outsideButton.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await new Promise((resolve) => setTimeout(resolve, 650))
      expect(view.container.querySelector('.label-popover')).toBeNull()
      expect(taskApi.update).not.toHaveBeenCalled()
    } finally {
      outsideButton.remove()
      view.unmount()
    }
  })

  it('saves one committed label change without scheduling a save loop from server write-back', async () => {
    const view = await mountEditor(createTask())
    const outsideButton = document.createElement('button')
    document.body.appendChild(outsideButton)
    try {
      const trigger = view.container.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      const suggestion = Array.from(view.container.querySelectorAll('[role="option"]')).find((row) =>
        row.textContent?.includes('运维问题')
      ) as HTMLButtonElement
      suggestion.click()
      await nextTick()
      outsideButton.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))

      await new Promise((resolve) => setTimeout(resolve, 750))
      expect(taskApi.update).toHaveBeenCalledTimes(1)
      await new Promise((resolve) => setTimeout(resolve, 1_000))
      expect(taskApi.update).toHaveBeenCalledTimes(1)
    } finally {
      outsideButton.remove()
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
      const trigger = view.container.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      expect(document.body.textContent).toContain('运维问题')

      outsideButton.focus()
      await nextTick()
      await flushPromises()

      const suggestionList = document.body.querySelector('.label-popover')
      expect(suggestionList).toBeNull()
    } finally {
      outsideButton.remove()
      view.unmount()
    }
  })
})
