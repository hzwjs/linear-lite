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
      "<textarea data-testid=\"tiptap-editor-stub\" :placeholder=\"placeholder\" :value=\"modelValue\" @input=\"$emit('update:modelValue', $event.target.value)\" />"
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
      deletable: false,
      parentId: null,
      rootId: null,
      depth: 0
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

      expect(taskCommentsApi.create).toHaveBeenNthCalledWith(1, 'ENG-1', {
        body: 'Hello',
        mentionedUserIds: [],
        parentId: null
      })
      expect(state.commentBody).toBe('')

      state.commentBody = 'World'
      await nextTick()

      compose.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true })
      )
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenNthCalledWith(2, 'ENG-1', {
        body: 'World',
        mentionedUserIds: [],
        parentId: null
      })
    } finally {
      view.unmount()
    }
  })

  it('shows inline reply editor after clicking reply', async () => {
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
      expect(view.host.textContent).toContain('Reply')
      const beforeCount = view.host.querySelectorAll('[data-testid="tiptap-editor-stub"]').length

      const replyBtn = [...view.host.querySelectorAll('button')].find(
        (btn) => btn.textContent?.trim() === 'Reply'
      )
      expect(replyBtn).toBeTruthy()
      replyBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()

      expect(view.host.querySelectorAll('[data-testid="tiptap-editor-stub"]')).toHaveLength(beforeCount + 1)
      const inlineEditor = view.host.querySelector('.task-comment-reply-compose [data-testid="tiptap-editor-stub"]')
      expect(inlineEditor?.getAttribute('placeholder')).toBe('Write a reply...')
    } finally {
      view.unmount()
    }
  })

  it('submits reply with root parentId', async () => {
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
      const replyBtn = [...view.host.querySelectorAll('button')].find(
        (btn) => btn.textContent?.trim() === 'Reply'
      )
      expect(replyBtn).toBeTruthy()
      replyBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()

      const replyInput = view.host.querySelector(
        '.task-comment-reply-compose [data-testid="tiptap-editor-stub"]'
      ) as HTMLTextAreaElement | null
      expect(replyInput).toBeTruthy()
      if (!replyInput) throw new Error('Reply input not found')
      replyInput.value = 'Nested reply'
      replyInput.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      const sendReplyBtn = view.host.querySelector('.task-comment-reply-compose .comment-send-btn')
      expect(sendReplyBtn).toBeTruthy()
      sendReplyBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', {
        body: 'Nested reply',
        mentionedUserIds: [],
        parentId: 10
      })
    } finally {
      view.unmount()
    }
  })

  it('shows view-more toggle when replies exceed 3 and can expand or collapse', async () => {
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
      },
      {
        id: 11,
        body: 'Reply 1',
        authorName: 'Bob',
        authorId: 3,
        createdAt: '2026-04-10T00:01:00.000Z',
        deletable: false,
        parentId: 10,
        rootId: 10,
        depth: 1
      },
      {
        id: 12,
        body: 'Reply 2',
        authorName: 'Bob',
        authorId: 3,
        createdAt: '2026-04-10T00:02:00.000Z',
        deletable: false,
        parentId: 10,
        rootId: 10,
        depth: 1
      },
      {
        id: 13,
        body: 'Reply 3',
        authorName: 'Bob',
        authorId: 3,
        createdAt: '2026-04-10T00:03:00.000Z',
        deletable: false,
        parentId: 10,
        rootId: 10,
        depth: 1
      },
      {
        id: 14,
        body: 'Reply 4',
        authorName: 'Bob',
        authorId: 3,
        createdAt: '2026-04-10T00:04:00.000Z',
        deletable: false,
        parentId: 10,
        rootId: 10,
        depth: 1
      }
    ])
    const view = await mountEditor(createTask())
    try {
      expect(view.host.textContent).toContain('View 1 more replies')
      expect(view.host.textContent).not.toContain('Reply 4')

      const toggleBtn = [...view.host.querySelectorAll('button')].find((btn) =>
        btn.textContent?.includes('View 1 more replies')
      )
      expect(toggleBtn).toBeTruthy()
      toggleBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()

      expect(view.host.textContent).toContain('Reply 4')
      expect(view.host.textContent).toContain('Hide replies')

      const hideBtn = [...view.host.querySelectorAll('button')].find((btn) =>
        btn.textContent?.includes('Hide replies')
      )
      expect(hideBtn).toBeTruthy()
      hideBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()

      expect(view.host.textContent).toContain('View 1 more replies')
      expect(view.host.textContent).not.toContain('Reply 4')
    } finally {
      view.unmount()
    }
  })

  it('shows delete button for deletable comment and triggers delete on click', async () => {
    vi.mocked(taskCommentsApi.list).mockResolvedValue([
      {
        id: 10,
        body: 'Root deletable comment',
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
      const deleteBtn = view.host.querySelector('.task-comment-delete') as HTMLButtonElement | null
      expect(deleteBtn).toBeTruthy()
      deleteBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(taskCommentsApi.delete).toHaveBeenCalledWith('ENG-1', 10)
    } finally {
      view.unmount()
    }
  })
})
