import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import zhCN from '../i18n/messages/zh-CN'
import { collaborationBatchApi } from '../services/api/collaborationBatch'
import { taskCollaborationApi, type TaskCollaborationState } from '../services/api/taskCollaboration'
import TaskCollaborationSettings from './TaskCollaborationSettings.vue'

vi.mock('../services/api/collaborationBatch', () => ({ collaborationBatchApi: { startSingle: vi.fn() } }))
vi.mock('../services/api/taskCollaboration', () => ({ taskCollaborationApi: { state: vi.fn() } }))
vi.mock('../store/authStore', () => ({ useAuthStore: () => authState }))

const authState = reactive<{ currentUser: { id: number } | null }>({ currentUser: { id: 7 } })
const idle: TaskCollaborationState = { inputRevision: 4, serverTime: '2026-11-10T08:00:00Z', status: 'todo', progressPercent: 0,
  completedAt: null, updatedAt: '2026-11-10T08:00:00', activeOrFrozen: false, attempts: [], attemptBindings: [], bindings: [],
  contextFacts: [], prerequisiteReviewFacts: [], dependencyFacts: [], goalAuthorizationRequests: [] }

async function settle() { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function mountSettings(extraProps: { taskNumericId?: number } = {}) {
  const container = document.createElement('div'); document.body.appendChild(container)
  const props = reactive({ taskKey: 'LL-1', projectId: 6, inputRevision: 4, ...extraProps })
  const marker = { task: { id: 'LL-1' } }
  const observed = vi.fn()
  const app = createApp({ render: () => h(TaskCollaborationSettings, { ...props,
    captureObservation: () => marker, onObserved: observed }) })
  app.component('RouterLink', defineComponent({ template: '<a><slot /></a>' }))
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
  app.mount(container); await settle()
  const open = async () => {
    const details = container.querySelector('details')!
    details.open = true; details.dispatchEvent(new Event('toggle')); await settle()
  }
  return { container, props, marker, observed, open, unmount() { app.unmount(); container.remove() } }
}

describe('TaskCollaborationSettings', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.resetAllMocks()
    authState.currentUser = { id: 7 }
    vi.mocked(taskCollaborationApi.state).mockResolvedValue(idle)
  })
  afterEach(() => vi.restoreAllMocks())

  it('reads status on opening and has no collaboration configuration inputs', async () => {
    const view = await mountSettings()
    try {
      expect(taskCollaborationApi.state).not.toHaveBeenCalled()
      await view.open()
      expect(taskCollaborationApi.state).toHaveBeenCalledWith('LL-1')
      expect(view.observed).toHaveBeenCalledWith('LL-1', view.marker, idle)
      expect(view.container.querySelectorAll('input, select')).toHaveLength(0)
      expect(view.container.textContent).not.toContain('写入范围')
      expect(view.container.textContent).not.toContain('加载服务器版本')
    } finally { view.unmount() }
  })

  it('shows server facts and review status without internal identifiers', async () => {
    const attempt = { id: 1, taskId: 1, bindingId: 3, batchId: 'batch-secret-id', hostInstanceId: 'host-uuid',
      agentInstanceId: 'agent-uuid', inputRevision: 3, prerequisiteVersions: [], status: 'running',
      leaseUntil: '2026-11-10T07:59:59Z', checkpoints: [
        { operationId: 'checkpoint-1', agentInstanceId: 'agent-uuid', occurredAt: '2026-11-10T07:59:00',
          finding: '发现缺失接口', artifactRefs: ['docs/result.md'] },
        { operationId: 'review-1', agentInstanceId: 'agent-uuid', occurredAt: '2026-11-10T07:59:00', kind: 'PREREQUISITE_REVIEW' },
      ], resultEvidence: { conditions: [], changedPaths: ['src/example.ts'], artifactRefs: ['docs/result.md'], projectCheckpoint: 'snapshot' },
      stopReason: null, stopRequestedAt: null, stopConfirmedAt: null, stopConfirmation: null,
      createdAt: '2026-11-10T07:00:00', updatedAt: '2026-11-10T07:59:00', finishedAt: null }
    const result = { ...idle, activeOrFrozen: true, attempts: [attempt],
      bindings: [{ bindingId: 3, deviceId: 'device-id', inputsReady: false,
        inputsWaitingReason: '前置交付缺失', prerequisiteResults: [],
        prerequisiteReviewStatuses: [{ sourceTaskId: 2, sourceAttemptId: 8, sourceInputRevision: 1,
          adoptionRequired: true, materialStatus: 'PENDING_CONFIRMATION', reason: '材料待确认' }] }],
      contextFacts: [{ attemptId: 1, refs: [{ ref: { kind: 'DOCUMENT', documentId: 8, documentVersion: 3 },
        documentTitle: '项目约定', status: 'CURRENT' }] }],
      prerequisiteReviewFacts: [{ attemptId: 1, reviews: [{ operationId: 'review-1', agentInstanceId: 'agent-uuid',
        occurredAt: '2026-11-10T07:59:00', outcome: 'REWORK_REQUESTED', review: { sourceTaskId: 2,
          sourceAttemptId: 8, sourceInputRevision: 1, decision: 'REQUEST_REWORK', finding: '产物缺失',
          evidence: '确认未找到', observations: [{ path: 'docs/missing.md', state: 'MISSING' }], goal: null, impact: null } }] }],
      dependencyFacts: [], goalAuthorizationRequests: [] } as TaskCollaborationState
    vi.mocked(taskCollaborationApi.state).mockResolvedValue(result)
    const view = await mountSettings()
    try {
      await view.open()
      for (const value of [zhCN.collaborationBatch.attemptState.recovery, '前置交付缺失', '材料待确认', '发现缺失接口', 'src/example.ts', '项目约定', '产物缺失', '确认未找到', 'docs/missing.md', '成果缺失'])
        expect(view.container.textContent).toContain(value)
      for (const value of ['agent-uuid', 'host-uuid', 'batch-secret-id', '输入版本', '写入范围'])
        expect(view.container.textContent).not.toContain(value)
    } finally { view.unmount() }
  })

  it('rejects a late response after changing the selected task or account', async () => {
    let release!: (value: TaskCollaborationState) => void
    vi.mocked(taskCollaborationApi.state).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
      .mockResolvedValue({ ...idle, inputRevision: 9, bindings: [{ bindingId: 4, deviceId: 'new-device',
        inputsReady: false, inputsWaitingReason: '新任务事实', prerequisiteReviewStatuses: [], prerequisiteResults: [] }] })
    const view = await mountSettings()
    try {
      const details = view.container.querySelector('details')!
      details.open = true; details.dispatchEvent(new Event('toggle')); await nextTick()
      view.props.taskKey = 'LL-2'; authState.currentUser = { id: 8 }; await settle()
      release({ ...idle, bindings: [{ bindingId: 3, deviceId: 'old-device', inputsReady: false,
        inputsWaitingReason: '旧任务事实', prerequisiteReviewStatuses: [], prerequisiteResults: [] }] }); await settle()
      expect(view.container.textContent).toContain('新任务事实')
      expect(view.container.textContent).not.toContain('旧任务事实')
      expect(view.observed.mock.calls.some(call => call[0] === 'LL-1')).toBe(false)
    } finally { view.unmount() }
  })

  it('starts a single-task collaboration batch with one click and refreshes state', async () => {
    vi.mocked(collaborationBatchApi.startSingle).mockResolvedValue({
      batchId: 'batch-single-1',
      lifecycle: 'queued',
      desiredIntent: 'RUN',
      intentRevision: 1,
      displayState: 'WAITING_ACCEPT',
    })
    const view = await mountSettings({ taskNumericId: 42 })
    try {
      await view.open()
      const callsBeforeStart = vi.mocked(taskCollaborationApi.state).mock.calls.length
      const button = view.container.querySelector('.collaboration-single-start') as HTMLButtonElement | null
      expect(button?.textContent).toContain(zhCN.taskCollaboration.startSingle)
      button!.click()
      await settle()
      expect(collaborationBatchApi.startSingle).toHaveBeenCalledWith(expect.objectContaining({
        projectId: 6,
        taskId: 42,
        operationId: expect.any(String),
      }))
      expect(vi.mocked(taskCollaborationApi.state).mock.calls.length).toBe(callsBeforeStart + 1)
      expect(button?.disabled).toBe(false)
      expect(view.container.textContent).toContain(zhCN.taskCollaboration.viewBatch)
    } finally { view.unmount() }
  })
})
