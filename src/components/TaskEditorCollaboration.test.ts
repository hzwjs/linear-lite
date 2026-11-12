import { createApp, defineComponent, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { useTaskStore } from '../store/taskStore'
import type { Task } from '../types/domain'
import TaskEditor from './TaskEditor.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('./BlockNoteEditorWrapper.vue', () => ({ default: defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" />',
}) }))
vi.mock('./TaskCollaborationSettings.vue', () => ({ default: defineComponent({
  props: ['taskKey', 'projectId', 'inputRevision', 'captureObservation'], emits: ['observed'],
  setup(props, { emit }) {
    let observation: unknown = null
    return () => h('div', [
      h('button', { 'data-testid': 'capture-state', onClick: () => { observation = props.captureObservation() } }, 'capture'),
      h('button', { 'data-testid': 'deliver-state', onClick: () => emit('observed', props.taskKey, observation,
        { inputRevision: 5, status: 'done', progressPercent: 100, completedAt: '2026-11-10T08:00:00', updatedAt: '2026-11-10T08:00:00' }) }, 'deliver'),
    ])
  },
}) }))
vi.mock('./ui/CustomSelect.vue', () => ({ default: defineComponent({
  props: ['modelValue'], template: '<span :data-state="modelValue" />',
}) }))
vi.mock('./pi/PiConversationPanel.vue', () => ({ default: defineComponent({ template: '<div />' }) }))
vi.mock('../services/api/project', () => ({ projectApi: {
  list: vi.fn().mockResolvedValue([]), listMembers: vi.fn().mockResolvedValue([]),
  listAssignees: vi.fn().mockResolvedValue([]), listLabels: vi.fn().mockResolvedValue([]),
} }))
vi.mock('../services/api/user', () => ({ userApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('../services/api/documents', () => ({ documentApi: { listTree: vi.fn().mockResolvedValue([]) } }))
vi.mock('../services/api/activity', () => ({ activityApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('../services/api/attachments', () => ({ attachmentsApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('../services/api/taskComments', () => ({ taskCommentsApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('../services/api/task', () => ({ taskApi: { list: vi.fn().mockResolvedValue([]), update: vi.fn().mockResolvedValue({
  task: { id: 'LL-1', numericId: 1, projectId: 6, title: '尚未保存的标题', status: 'todo', priority: 'medium', createdAt: 1, updatedAt: 2, labels: [] },
  autoCompletedAncestors: [],
}) } }))
vi.mock('../services/api/agent', () => ({ agentApi: { getTaskStatus: vi.fn().mockResolvedValue(null) } }))

describe('TaskEditor collaboration input updates', () => {
  it('shows a newer execution delivery while preserving a title draft', async () => {
    const task: Task = { id: 'LL-1', numericId: 1, projectId: 6, title: '任务', status: 'todo', progressPercent: 0,
      priority: 'medium', createdAt: 1, updatedAt: 1, labels: [], inputRevision: 4 }
    const pinia = createPinia(); setActivePinia(pinia)
    const store = useTaskStore(pinia); store.tasks = [task]; store.currentTaskId = task.id
    const container = document.createElement('div'); document.body.appendChild(container)
    const app = createApp({ render: () => h(TaskEditor, { mode: 'edit', task: store.currentTask }) })
    app.use(pinia); app.use(i18n); app.mount(container)
    try {
      await nextTick()
      const title = container.querySelector('.title-input') as HTMLInputElement
      title.value = '草稿标题'; title.dispatchEvent(new Event('input')); await nextTick()
      ;(container.querySelector('[data-testid="capture-state"]') as HTMLButtonElement).click()
      ;(container.querySelector('[data-testid="deliver-state"]') as HTMLButtonElement).click()
      await nextTick(); await nextTick()
      expect(store.currentTask).toMatchObject({ inputRevision: 5, status: 'done', progressPercent: 100 })
      expect(container.querySelector('#task-status-sidebar')?.getAttribute('data-state')).toBe('done')
      expect((container.querySelector('#task-progress-sidebar') as HTMLInputElement).value).toBe('100')
      expect(title.value).toBe('草稿标题')
    } finally { app.unmount(); container.remove() }
  })

  it('discards a captured observation when a normal edit changes the task', async () => {
    const task: Task = { id: 'LL-1', numericId: 1, projectId: 6, title: '任务', status: 'todo', progressPercent: 0,
      priority: 'medium', createdAt: 1, updatedAt: 1, labels: [], inputRevision: 4 }
    const pinia = createPinia(); setActivePinia(pinia)
    const store = useTaskStore(pinia); store.tasks = [task]; store.currentTaskId = task.id
    const container = document.createElement('div'); document.body.appendChild(container)
    const app = createApp({ render: () => h(TaskEditor, { mode: 'edit', task: store.currentTask }) })
    app.use(pinia); app.use(i18n); app.mount(container)
    try {
      await nextTick()
      ;(container.querySelector('[data-testid="capture-state"]') as HTMLButtonElement).click()
      store.applyLocalTaskPatch(task.id, { title: '新编辑' })
      ;(container.querySelector('[data-testid="deliver-state"]') as HTMLButtonElement).click()
      await nextTick(); await nextTick()
      expect(store.currentTask).toMatchObject({ title: '新编辑', inputRevision: 4, status: 'todo', progressPercent: 0 })
    } finally { app.unmount(); container.remove() }
  })
})
