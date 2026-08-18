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
import {
  agentApi,
  type RuntimeDisplayBlock,
  type SessionSnapshot
} from '../services/api/agent'
import { useTaskStore } from '../store/taskStore'
import { useAuthStore } from '../store/authStore'

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
    methods: {
      getMentionedUserIdsFromDoc() {
        return this.modelValue.includes('@Pi') ? [42] : []
      }
    },
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

vi.mock('./ui/AssigneeSelect.vue', () => ({
  default: defineComponent({
    name: 'AssigneeSelectStub',
    props: {
      modelValue: { type: [String, Number], default: '' }
    },
    emits: ['update:modelValue'],
    template:
      '<button type="button" class="assignee-select-stub" @click="$emit(\'update:modelValue\', 42)">{{ modelValue }}</button>'
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

vi.mock('../services/api/agent', () => ({
  agentApi: {
    prepareLocalPi: vi.fn(),
    submitTurn: vi.fn(),
    getTaskStatus: vi.fn(),
    requestSessionSnapshot: vi.fn(),
    openSessionStream: vi.fn(),
    cancelTask: vi.fn()
  }
}))

vi.mock('../services/piBridge', () => ({
  attachPiBridge: vi.fn().mockResolvedValue(undefined),
  getPiBridgeHealth: vi.fn().mockResolvedValue({ configured: true, status: 'online' }),
  isPiBridgeAvailable: vi.fn().mockResolvedValue(true)
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

async function mountEditor(task: Task, ownerId?: number) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const pinia = createPinia()
  setActivePinia(pinia)
  if (ownerId != null) useAuthStore().setSession('test-token', ownerId, 'Owner')
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
    vi.mocked(agentApi.getTaskStatus).mockResolvedValue({
      executionId: null,
      jobId: null,
      sessionStatus: null,
      jobStatus: null,
      sourceType: null,
      errorMessage: null,
      updatedAt: null
    })
    vi.mocked(agentApi.requestSessionSnapshot).mockResolvedValue('snapshot-request-1')
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
      await new Promise((resolve) => setTimeout(resolve, 0))
      await nextTick()

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

  it('does not trigger local Pi from an ordinary comment mention', async () => {
    vi.mocked(projectApi.listMembers).mockResolvedValue([
      { id: 42, username: 'Pi', principalType: 'agent', agentKey: 'pi' }
    ])
    vi.mocked(agentApi.getTaskStatus)
      .mockResolvedValueOnce({
        executionId: 'execution-1',
        jobId: 1,
        sessionStatus: 'active',
        jobStatus: 'succeeded',
        sourceType: 'assignment',
        errorMessage: null,
        updatedAt: '2026-08-12T00:00:00.000Z'
      })
      .mockResolvedValue({
        executionId: 'execution-1',
        jobId: 2,
        sessionStatus: 'active',
        jobStatus: 'queued',
        sourceType: 'comment',
        errorMessage: null,
        updatedAt: '2026-08-12T00:01:00.000Z'
      })
    const view = await mountEditor(createTask({ assigneeId: 42 }))
    try {
      const editor = view.host.querySelector('.comment-compose [data-testid="tiptap-editor-stub"]') as HTMLTextAreaElement
      editor.value = '@Pi 查询南京明天的天气'
      editor.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      view.host.querySelector<HTMLButtonElement>('.comment-compose [aria-label="Send"]')?.click()
      await nextTick()
      await flushPromises()
      await new Promise((resolve) => setTimeout(resolve, 0))
      await nextTick()

      expect(taskCommentsApi.create).toHaveBeenCalledWith('ENG-1', {
        body: '@Pi 查询南京明天的天气',
        mentionedUserIds: [42],
        parentId: null
      })
      expect(agentApi.getTaskStatus).toHaveBeenCalledTimes(2)
      expect(agentApi.openSessionStream).not.toHaveBeenCalled()
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

  it('renders assistant and tool blocks in place for the task owner', async () => {
    vi.mocked(agentApi.getTaskStatus).mockResolvedValue({
      executionId: 'execution-1',
      jobId: 1,
      sessionStatus: 'active',
      jobStatus: 'running',
      sourceType: 'assignment',
      errorMessage: null,
      updatedAt: '2026-08-12T00:00:00.000Z'
    })
    vi.mocked(agentApi.prepareLocalPi).mockResolvedValue({
      status: {
        executionId: 'execution-1',
        jobId: 1,
        sessionStatus: 'active',
        jobStatus: 'running',
        sourceType: 'turn',
        errorMessage: null,
        updatedAt: '2026-08-12T00:00:00.000Z'
      },
      attachmentCode: 'attachment-code'
    })
    let onSnapshot: ((snapshot: SessionSnapshot) => void) | undefined
    let onRuntime: ((block: RuntimeDisplayBlock) => void) | undefined
    const close = vi.fn()
    vi.mocked(agentApi.openSessionStream).mockImplementation((
      _taskKey, _executionId, snapshotHandler, runtimeHandler, _readErrorHandler, onOpen
    ) => {
      onSnapshot = snapshotHandler
      onRuntime = runtimeHandler
      onOpen?.()
      return { close } as unknown as EventSource
    })

    const view = await mountEditor(createTask({ assigneeId: 42 }), 42)
    try {
      view.host.querySelector<HTMLButtonElement>('.agent-launch-button')?.click()
      await nextTick()
      await flushPromises()
      await new Promise((resolve) => setTimeout(resolve, 0))
      await nextTick()
      expect(agentApi.openSessionStream).toHaveBeenCalledWith(
        'ENG-1', 'execution-1',
        expect.any(Function), expect.any(Function), expect.any(Function),
        expect.any(Function), expect.any(Function)
      )
      expect(agentApi.requestSessionSnapshot).toHaveBeenCalledWith('ENG-1', 'execution-1')
      expect(view.host.querySelector('.editor-props')).not.toBeNull()
      expect(view.host.querySelector('.props-card--horizontal')).toBeNull()

      onRuntime?.({
        executionId: 'execution-1',
        jobId: 1,
        blockId: 'assistant:1',
        revision: 1,
        kind: 'assistant',
        phase: 'streaming',
        content: { text: '我先检查任务入口', thinking: '' },
        tool: null,
        createdAt: '2026-08-12T00:00:00.000Z'
      })
      await nextTick()
      expect(document.body.querySelectorAll('.pi-assistant-message')).toHaveLength(1)
      expect(document.body.textContent).toContain('我先检查任务入口')
      expect(document.body.querySelector<HTMLButtonElement>('.agent-submit-button')?.ariaLabel)
        .toBe('停止本地 Pi')

      onRuntime?.({
        executionId: 'execution-1',
        jobId: 1,
        blockId: 'tool:1',
        revision: 1,
        kind: 'tool',
        phase: 'streaming',
        content: null,
        tool: {
          name: 'bash',
          arguments: { command: 'pwd' },
          content: [{ type: 'text', text: 'partial output' }],
          isError: false,
          truncation: null
        },
        createdAt: '2026-08-12T00:00:01.000Z'
      })
      onRuntime?.({
        executionId: 'execution-1',
        jobId: 1,
        blockId: 'tool:1',
        revision: 2,
        kind: 'tool',
        phase: 'error',
        content: null,
        tool: {
          name: 'bash',
          arguments: { command: 'pwd' },
          content: [{ type: 'text', text: 'permission denied' }],
          isError: true,
          truncation: null
        },
        createdAt: '2026-08-12T00:00:02.000Z'
      })
      await nextTick()

      expect(document.body.querySelectorAll('.pi-tool')).toHaveLength(1)
      expect(document.body.textContent).toContain('$ pwd')
      expect(document.body.textContent).toContain('permission denied')
      expect(document.body.textContent).not.toContain('partial output')
      expect(document.body.textContent).not.toContain('工具执行完成')

      onSnapshot?.({
        executionId: 'execution-1',
        piSessionId: 'pi-session-1',
        leafId: 'entry-1',
        blocks: [{
          blockId: 'entry-1:0',
          entryId: 'entry-1',
          runtimeBlockId: 'assistant:1',
          order: 1,
          kind: 'assistant',
          phase: 'final',
          content: { text: '问题出在权限配置', thinking: '' },
          tool: null,
          createdAt: '2026-08-12T00:00:03.000Z'
        }, {
          blockId: 'tool:1',
          entryId: 'entry-tool-1',
          runtimeBlockId: 'tool:1',
          order: 2,
          kind: 'tool',
          phase: 'error',
          content: null,
          tool: {
            name: 'bash',
            arguments: { command: 'pwd' },
            content: [{ type: 'text', text: 'permission denied' }],
            isError: true,
            truncation: null
          },
          createdAt: '2026-08-12T00:00:02.000Z'
        }]
      })
      await nextTick()

      expect(document.body.querySelectorAll('.pi-assistant-message')).toHaveLength(1)
      expect(document.body.querySelectorAll('.pi-tool')).toHaveLength(1)
      expect(document.body.textContent).toContain('问题出在权限配置')
      expect(document.body.textContent).not.toContain('我先检查任务入口')
      expect(document.body.textContent).toContain('permission denied')
    } finally {
      view.unmount()
      expect(close).toHaveBeenCalled()
    }
  })

  it('shows the backend business message when preparing local Pi is rejected', async () => {
    vi.mocked(agentApi.prepareLocalPi).mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '执行上下文已结束' } }
    })

    const view = await mountEditor(createTask({ assigneeId: 42, status: 'done' }), 42)
    try {
      view.host.querySelector<HTMLButtonElement>('.agent-launch-button')?.click()
      await nextTick()
      await flushPromises()
      await new Promise((resolve) => setTimeout(resolve, 0))
      await nextTick()

      expect(document.body.querySelector('.pi-conversation-notice--error')?.textContent)
        .toContain('执行上下文已结束')
    } finally {
      view.unmount()
    }
  })

  it('does not reconnect to a completed execution after refresh', async () => {
    vi.mocked(agentApi.getTaskStatus).mockResolvedValue({
      executionId: 'execution-1',
      jobId: 1,
      sessionStatus: 'active',
      jobStatus: 'succeeded',
      sourceType: 'assignment',
      errorMessage: null,
      updatedAt: '2026-08-12T00:00:00.000Z'
    })

    const view = await mountEditor(createTask())
    try {
      expect(document.body.querySelector('.pi-conversation-panel')).toBeNull()
      expect(view.host.querySelector('.editor-props')).not.toBeNull()
      expect(view.host.querySelector('.props-card--horizontal')).toBeNull()
      expect(agentApi.openSessionStream).not.toHaveBeenCalled()
    } finally {
      view.unmount()
    }
  })

  it('does not expose local Pi execution to a non-owner after assignee changes', async () => {
    vi.mocked(taskApi.update).mockResolvedValue({
      task: createTask({ assigneeId: 42 }),
      autoCompletedAncestors: []
    })
    vi.mocked(agentApi.getTaskStatus)
      .mockResolvedValueOnce({
        executionId: null,
        jobId: null,
        sessionStatus: null,
        jobStatus: null,
        sourceType: null,
        errorMessage: null,
        updatedAt: null
      })
      .mockResolvedValue({
        executionId: 'execution-after-assignee-change',
        jobId: 2,
        sessionStatus: 'active',
        jobStatus: 'queued',
        sourceType: 'assignment',
        errorMessage: null,
        updatedAt: '2026-08-12T00:00:00.000Z'
      })

    const view = await mountEditor(createTask())
    try {
      view.host.querySelector<HTMLButtonElement>('.assignee-select-stub')?.click()
      await new Promise((resolve) => setTimeout(resolve, 700))
      await nextTick()
      await flushPromises()

      expect(taskApi.update).toHaveBeenCalled()
      expect(agentApi.getTaskStatus).toHaveBeenCalledTimes(2)
      expect(document.body.querySelector('.pi-conversation-panel')).toBeNull()
    } finally {
      view.unmount()
    }
  })
})
