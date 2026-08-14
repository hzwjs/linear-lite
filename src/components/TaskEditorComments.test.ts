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
import { agentApi } from '../services/api/agent'
import { useTaskStore } from '../store/taskStore'
import { formatAgentEventDetail } from '../utils/agentEventDisplay'

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
    getTaskStatus: vi.fn(),
    openEventStream: vi.fn(),
    cancelTask: vi.fn()
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
    vi.mocked(agentApi.getTaskStatus).mockResolvedValue({
      executionId: null,
      jobId: null,
      sessionStatus: null,
      jobStatus: null,
      sourceType: null,
      errorMessage: null,
      updatedAt: null
    })
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

  it('switches the Pi event stream to a comment job immediately after mentioning Pi', async () => {
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
    const firstStreamClose = vi.fn()
    const secondStreamClose = vi.fn()
    vi.mocked(agentApi.openEventStream)
      .mockReturnValueOnce({ close: firstStreamClose } as unknown as EventSource)
      .mockReturnValue({ close: secondStreamClose } as unknown as EventSource)

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
      expect(firstStreamClose).toHaveBeenCalledOnce()
      expect(agentApi.openEventStream).toHaveBeenLastCalledWith(
        'ENG-1',
        'execution-1',
        2,
        expect.any(Function),
        expect.any(Function),
        expect.any(Function),
        expect.any(Function)
      )
      expect(view.host.querySelector('.agent-status-badge')?.textContent).toContain('queued')
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

  it('renders live Pi events and closes the stream after completion', async () => {
    vi.mocked(agentApi.getTaskStatus).mockResolvedValue({
      executionId: 'execution-1',
      jobId: 1,
      sessionStatus: 'active',
      jobStatus: 'running',
      sourceType: 'assignment',
      errorMessage: null,
      updatedAt: '2026-08-12T00:00:00.000Z'
    })
    let onEvent: ((event: Parameters<typeof formatAgentEventDetail>[0]) => void) | undefined
    const close = vi.fn()
    vi.mocked(agentApi.openEventStream).mockImplementation((_taskKey, _executionId, _jobId, eventHandler, onOpen) => {
      onEvent = eventHandler
      onOpen?.()
      return { close } as unknown as EventSource
    })

    const view = await mountEditor(createTask())
    try {
        expect(agentApi.openEventStream).toHaveBeenCalledWith(
          'ENG-1',
          'execution-1',
          1,
          expect.any(Function),
          expect.any(Function),
          expect.any(Function),
          expect.any(Function)
        )
      expect(view.host.querySelector('.agent-event-area')).not.toBeNull()
      expect(view.host.querySelector('.editor-props')).toBeNull()
      expect(view.host.querySelector('.props-card--horizontal')).not.toBeNull()

      onEvent?.({
        id: 0,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 0,
        eventType: 'started',
        summary: 'Pi 已开始处理任务',
        payload: '{}',
        createdAt: '2026-08-12T00:00:00.000Z'
      })
      await nextTick()
      expect(view.host.querySelector('.agent-status-badge')?.textContent).toContain('running')

      onEvent?.({
        id: 1,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 1,
        eventType: 'tool_call',
        summary: '正在调用工具：bash',
        payload: JSON.stringify({ args: { command: 'pwd', path: '/tmp' } }),
        createdAt: '2026-08-12T00:00:01.000Z'
      })
      onEvent?.({
        id: 2,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 2,
        eventType: 'tool_result',
        summary: '工具调用完成：bash',
        payload: JSON.stringify({ result: { content: [{ type: 'text', text: '/tmp' }] } }),
        createdAt: '2026-08-12T00:00:02.000Z'
      })
      onEvent?.({
        id: 3,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 3,
        eventType: 'progress',
        summary: 'Pi：广州明天天气',
        payload: JSON.stringify({
          role: 'assistant',
          content: '广州明天：\n\n- **气温：** 27～37℃\n- **天气：** 多云有阵雨'
        }),
        createdAt: '2026-08-12T00:00:03.000Z'
      })
      await nextTick()

      expect(view.host.querySelector('.agent-tool-call code')?.textContent).toContain('$ pwd')
      expect(view.host.querySelector('.agent-tool-result')?.hasAttribute('open')).toBe(false)
      expect(view.host.querySelector('.agent-tool-result pre')?.textContent).toContain('/tmp')
      expect(view.host.querySelector('.agent-event-markdown strong')?.textContent).toBe('气温：')
      expect(view.host.querySelectorAll('.agent-event-markdown li')).toHaveLength(2)
      expect(view.host.querySelector('.agent-event-markdown')?.textContent).not.toContain('**')

      onEvent?.({
        id: 31,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 31,
        eventType: 'tool_call',
        summary: '正在调用工具：read',
        payload: JSON.stringify({
          toolCallId: 'skill-1',
          toolName: 'read',
          args: { path: '/Users/example/.agents/skills/ego-browser/SKILL.md' }
        }),
        createdAt: '2026-08-12T00:00:03.100Z'
      })
      onEvent?.({
        id: 32,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 32,
        eventType: 'tool_result',
        summary: '工具调用完成：read',
        payload: JSON.stringify({
          toolCallId: 'skill-1',
          toolName: 'read',
          result: '# ego-browser\nLong skill instructions'
        }),
        createdAt: '2026-08-12T00:00:03.200Z'
      })
      await nextTick()
      expect(view.host.querySelector('.agent-tool-heading--skill')?.textContent).toContain('[skill] ego-browser')
      expect(view.host.textContent).not.toContain('Long skill instructions')

      onEvent?.({
        id: 4,
        executionId: 'execution-1',
        jobId: 1,
        sequenceNo: 4,
        eventType: 'completed',
        summary: 'Pi 执行完成',
        payload: '{}',
        createdAt: '2026-08-12T00:00:04.000Z'
      })
      await nextTick()

      expect(close).toHaveBeenCalledOnce()
      expect(view.host.querySelector('.agent-status-badge')?.textContent).toContain('succeeded')
      expect(view.host.querySelector('.agent-event-area')).not.toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('hides a completed execution when no event content is available', async () => {
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
      expect(view.host.querySelector('.agent-status-panel')).toBeNull()
      expect(view.host.querySelector('.agent-event-area')).toBeNull()
      expect(view.host.querySelector('.editor-props')).not.toBeNull()
      expect(view.host.querySelector('.props-card--horizontal')).toBeNull()
      expect(agentApi.openEventStream).toHaveBeenCalledWith(
        'ENG-1',
        'execution-1',
        1,
        expect.any(Function),
        expect.any(Function),
        expect.any(Function),
        expect.any(Function)
      )
    } finally {
      view.unmount()
    }
  })

  it('formats Pi event payloads as readable text instead of JSON', () => {
    expect(formatAgentEventDetail({
      id: 1,
      executionId: 'execution-1',
      jobId: 1,
      sequenceNo: 1,
      eventType: 'tool_result',
      summary: '工具调用完成：bash',
      payload: JSON.stringify({ result: { content: [{ type: 'text', text: 'permission denied' }] } }),
      createdAt: '2026-08-12T00:00:00.000Z'
    })).toContain('permission denied')
    expect(formatAgentEventDetail({
      id: 2,
      executionId: 'execution-1',
      jobId: 1,
      sequenceNo: 2,
      eventType: 'tool_call',
      summary: '正在调用工具：bash',
      payload: JSON.stringify({ args: { command: 'pwd', path: '/tmp' } }),
      createdAt: '2026-08-12T00:00:00.000Z'
    })).toBe('命令：pwd\n路径：/tmp')
    expect(formatAgentEventDetail({
      id: 3,
      executionId: 'execution-1',
      jobId: 1,
      sequenceNo: 3,
      eventType: 'tool_result',
      summary: '工具调用完成：bash',
      payload: JSON.stringify({
        result: {
          content: [{
            type: 'text',
            text: '{"timezone":"Asia/Shanghai","daily":{"time":["2026-08-13"],"temperature_2m_max":[34.6]}}'
          }]
        }
      }),
      createdAt: '2026-08-12T00:00:00.000Z'
    })).toBe([
      'timezone：Asia/Shanghai',
      'daily：',
      '  time：',
      '    1. 2026-08-13',
      '  temperature_2m_max：',
      '    1. 34.6'
    ].join('\n'))
  })

  it('reloads Pi execution status after changing the assignee in the detail view', async () => {
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
      expect(view.host.querySelector('.agent-status-panel')).not.toBeNull()
      expect(view.host.querySelector('.agent-status-badge')?.textContent).toContain('queued')
    } finally {
      view.unmount()
    }
  })
})
