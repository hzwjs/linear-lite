import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import zhCN from '../i18n/messages/zh-CN'
import CollaborationExecutionView from './CollaborationExecutionView.vue'
import { collaborationBatchApi } from '../services/api/collaborationBatch'
import { collaborationHostApi } from '../services/api/collaborationHost'
import { projectDeviceBindingApi } from '../services/api/projectDeviceBinding'
import { taskApi } from '../services/api/task'

const navigation = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
const route = reactive<{ params: { projectId: string }; query: Record<string, string | string[]> }>({
  params: { projectId: '12' }, query: { tasks: ['TEST-1', 'TEST-2'] },
})
const auth = reactive({ currentUser: { id: 7, username: 'Alice' } })
const projectStore = reactive({ projects: [{ id: 12, name: 'Linear-Lite' }], activeProjectId: 12, setActiveProject: vi.fn() })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => navigation }))
vi.mock('../store/authStore', () => ({ useAuthStore: () => auth }))
vi.mock('../store/projectStore', () => ({ useProjectStore: () => projectStore }))
vi.mock('../services/api/task', () => ({ taskApi: { list: vi.fn() } }))
vi.mock('../services/api/collaborationHost', () => ({ collaborationHostApi: { list: vi.fn() } }))
vi.mock('../services/api/projectDeviceBinding', () => ({ projectDeviceBindingApi: { list: vi.fn() } }))
vi.mock('../services/api/collaborationBatch', () => ({ collaborationBatchApi: { start: vi.fn(), stop: vi.fn(), list: vi.fn(), get: vi.fn(), authorizeGoal: vi.fn() } }))

const tasks = [
  { id: 'TEST-1', numericId: 9, projectId: 12, title: 'Producer' },
  { id: 'TEST-2', numericId: 7, projectId: 12, title: 'Consumer' },
]
const binding = { id: 4, userId: 7, projectId: 12, deviceId: 'device-a', projectDirectory: '/work/project', revision: 3, createdAt: '', updatedAt: '' }
const grant = { id: 8, userId: 7, deviceId: 'device-a', deviceName: 'Mac', authorized: true, online: true, lastHeartbeatAt: null, createdAt: '' }
const batch = { batchId: 'b-1', projectId: 12, userId: 7, bindingId: 4, bindingRevision: 3,
  hostAuthorizationId: 8, maxSlots: 1, maxDurationMs: 3600000, maxAttempts: 5, desiredIntent: 'RUN' as const,
  lifecycle: 'accepted' as const, intentRevision: 1, hostInstanceId: 'host-1', runEpoch: 1,
  runLeaseUntil: '2026-11-10T10:01:00Z', startedAt: '2026-11-10T10:00:00Z', deadlineAt: '2026-11-10T11:00:00Z',
  endedAt: null, endReason: null, createdAt: '2026-11-10T10:00:00Z', attemptsUsed: 1, displayState: 'DEPENDENCY_WAIT' }
const task = { taskId: 7, taskKey: 'TEST-2', title: 'Consumer', status: 'todo',
  inputRevision: 2, inputsReady: false,
  inputsWaitingReason: '前置任务缺少成功成果', activeOrFrozen: false, dependencyFacts: [], contextFacts: [], goalAuthorizationRequests: [],
  prerequisiteReviewFacts: [], prerequisiteReviewStatuses: [], prerequisiteResults: [], attempts: [] }

function flush() { return new Promise(resolve => setTimeout(resolve, 0)) }
async function settle() { await nextTick(); await flush(); await nextTick() }
async function mountView() {
  const host = document.createElement('div'); document.body.appendChild(host)
  const app = createApp(CollaborationExecutionView)
  app.component('router-link', { props: ['to'], render() { return h('a', {}, this.$slots.default?.()) } })
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
  app.mount(host); await settle()
  return { host, close() { app.unmount(); host.remove() } }
}

describe('collaboration execution', () => {
  beforeEach(() => {
    document.body.innerHTML = ''; localStorage.clear(); vi.clearAllMocks()
    route.params.projectId = '12'; route.query = { tasks: ['TEST-1', 'TEST-2'] }; auth.currentUser.id = 7
    navigation.replace.mockImplementation(async (location: { query: Record<string, string | string[]> }) => { route.query = location.query })
    vi.mocked(taskApi.list).mockResolvedValue(tasks as never)
    vi.mocked(projectDeviceBindingApi.list).mockResolvedValue([binding])
    vi.mocked(collaborationHostApi.list).mockResolvedValue([grant])
    vi.mocked(collaborationBatchApi.list).mockResolvedValue({ items: [], hasMore: false, nextCursor: null })
    vi.mocked(collaborationBatchApi.get).mockResolvedValue({ serverTime: '2026-11-10T08:00:00Z', batch, tasks: [task] })
    vi.mocked(collaborationBatchApi.start).mockResolvedValue({ batchId: 'b-1', lifecycle: 'queued', desiredIntent: 'RUN', intentRevision: 1, displayState: 'WAITING_ACCEPT' })
    vi.mocked(collaborationBatchApi.stop).mockResolvedValue({ batchId: 'b-1', lifecycle: 'accepted', desiredIntent: 'STOP', intentRevision: 2, displayState: 'STOPPING' })
  })
  afterEach(() => vi.restoreAllMocks())

  it('retries the same start request after refresh without starting on mount', async () => {
    vi.mocked(collaborationBatchApi.start).mockRejectedValueOnce(new Error('网络中断'))
    let view = await mountView()
    try {
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      expect(view.host.textContent).toContain('网络中断')
      const original = vi.mocked(collaborationBatchApi.start).mock.calls[0]![0]
      expect(original.taskIds).toEqual([9, 7]); expect(original.bindingRevision).toBe(3)
      expect(Object.keys(original).sort()).toEqual(['bindingId', 'bindingRevision', 'maxDurationMs', 'operationId', 'projectId', 'taskIds'])
      expect(view.host.querySelector('#batch-slots')).toBeNull()
      expect(view.host.querySelector('#batch-attempts')).toBeNull()
      expect(view.host.textContent).not.toContain('写入范围')
      expect(view.host.textContent).not.toContain('任务未授权协作执行')
      view.close()
      view = await mountView()
      expect(collaborationBatchApi.start).toHaveBeenCalledTimes(1)
      expect(view.host.textContent).toContain('上次启动结果待确认')
      ;(view.host.querySelector('[data-testid="batch-retry"]') as HTMLButtonElement).click(); await settle()
      expect(collaborationBatchApi.start).toHaveBeenCalledTimes(2)
      expect(vi.mocked(collaborationBatchApi.start).mock.calls[1]![0]).toEqual(original)
      expect(localStorage.getItem('linear-lite.batch-start.7.12')).toBeNull()
      expect(navigation.replace).toHaveBeenCalledWith(expect.objectContaining({ query: { batch: 'b-1' } }))
    } finally { view.close() }
  })

  it('does not send a start if pending request cannot be persisted', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('storage denied') })
    const view = await mountView()
    try {
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      expect(collaborationBatchApi.start).not.toHaveBeenCalled()
      expect(view.host.textContent).toContain('无法保存启动请求')
    } finally { view.close() }
  })

  it('loads a pending start saved by another page before issuing a new request', async () => {
    const view = await mountView()
    try {
      const request = { projectId: 12, operationId: 'pending-elsewhere', bindingId: 4,
        bindingRevision: 3, taskIds: [9, 7], maxDurationMs: 3600000 }
      localStorage.setItem('linear-lite.batch-start.7.12', JSON.stringify(request))
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      expect(collaborationBatchApi.start).not.toHaveBeenCalled()
      expect(view.host.textContent).toContain('上次启动结果待确认')
      ;(view.host.querySelector('[data-testid="batch-retry"]') as HTMLButtonElement).click(); await settle()
      expect(collaborationBatchApi.start).toHaveBeenCalledWith(request)
    } finally { view.close() }
  })

  it('closes the start panel without sending a start or stop request', async () => {
    const view = await mountView()
    try {
      const cancel = [...view.host.querySelectorAll('button')].find(button => button.textContent === '取消')!
      cancel.click(); await settle()
      expect(navigation.replace).toHaveBeenCalledWith(expect.objectContaining({ query: {} }))
      expect(collaborationBatchApi.start).not.toHaveBeenCalled()
      expect(collaborationBatchApi.stop).not.toHaveBeenCalled()
    } finally { view.close() }
  })

  it('retains the same request if navigation fails after the server accepts it', async () => {
    navigation.replace.mockRejectedValueOnce(new Error('导航失败'))
    const view = await mountView()
    try {
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      const original = JSON.parse(localStorage.getItem('linear-lite.batch-start.7.12')!)
      expect(view.host.textContent).toContain('导航失败')
      ;(view.host.querySelector('[data-testid="batch-retry"]') as HTMLButtonElement).click(); await settle()
      expect(vi.mocked(collaborationBatchApi.start).mock.calls[1]![0]).toEqual(original)
      expect(localStorage.getItem('linear-lite.batch-start.7.12')).toBeNull()
    } finally { view.close() }
  })

  it('keeps a device load error after history succeeds', async () => {
    vi.mocked(collaborationHostApi.list).mockRejectedValueOnce(new Error('设备权限不足'))
    const view = await mountView()
    try { expect(view.host.textContent).toContain('设备权限不足') }
    finally { view.close() }
  })

  it('keeps batch history available when the selected detail is forbidden', async () => {
    route.query = { batch: 'b-1' }
    vi.mocked(collaborationBatchApi.list).mockResolvedValue({ items: [batch], hasMore: false, nextCursor: null })
    vi.mocked(collaborationBatchApi.get).mockRejectedValueOnce(new Error('无权查看批次详情'))
    const view = await mountView()
    try {
      expect(view.host.textContent).toContain('无权查看批次详情')
      expect(view.host.querySelector('.batch-row')).toBeTruthy()
    } finally { view.close() }
  })

  it('does not present a changed binding directory as the original batch directory', async () => {
    route.query = { batch: 'b-1' }
    vi.mocked(projectDeviceBindingApi.list).mockResolvedValue([{ ...binding, revision: 4, projectDirectory: '/work/new-project' }])
    const view = await mountView()
    try {
      expect(view.host.textContent).toContain('目录绑定发生变化')
      expect(view.host.textContent).not.toContain('/work/new-project')
    } finally { view.close() }
  })

  it('refreshes offline devices without resetting the selected duration', async () => {
    vi.mocked(collaborationHostApi.list).mockResolvedValueOnce([{ ...grant, online: false }]).mockResolvedValueOnce([grant])
    const view = await mountView()
    try {
      const button = view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement
      expect(button.disabled).toBe(true)
      const duration = view.host.querySelector('#batch-hours') as HTMLInputElement
      duration.value = '2'; duration.dispatchEvent(new Event('input')); await nextTick()
      ;(view.host.querySelector('[data-testid="batch-refresh"]') as HTMLButtonElement).click(); await settle()
      expect(duration.value).toBe('2')
      expect(button.disabled).toBe(false)
    } finally { view.close() }
  })

  it('does not let an earlier user context clear a later pending start', async () => {
    let resolveFirst!: (value: Awaited<ReturnType<typeof collaborationBatchApi.start>>) => void
    let resolveSecond!: (value: Awaited<ReturnType<typeof collaborationBatchApi.start>>) => void
    vi.mocked(collaborationBatchApi.start).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const view = await mountView()
    const result = { batchId: 'b-1', lifecycle: 'queued' as const, desiredIntent: 'RUN' as const, intentRevision: 1, displayState: 'WAITING_ACCEPT' }
    try {
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      const first = JSON.parse(localStorage.getItem('linear-lite.batch-start.7.12')!)
      auth.currentUser.id = 8; await settle()
      auth.currentUser.id = 7; await settle()
      const abandon = [...view.host.querySelectorAll('button')].find(button => button.textContent?.includes('放弃重试'))!
      abandon.click(); await settle()
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      const second = JSON.parse(localStorage.getItem('linear-lite.batch-start.7.12')!)
      expect(second.operationId).not.toBe(first.operationId)
      resolveFirst(result); await settle()
      expect(JSON.parse(localStorage.getItem('linear-lite.batch-start.7.12')!).operationId).toBe(second.operationId)
      resolveSecond({ ...result, batchId: 'b-2' }); await settle()
      expect(localStorage.getItem('linear-lite.batch-start.7.12')).toBeNull()
    } finally { view.close() }
  })

  it('keeps the pending request when the user opens another batch during start', async () => {
    let resolveStart!: (value: Awaited<ReturnType<typeof collaborationBatchApi.start>>) => void
    vi.mocked(collaborationBatchApi.start).mockImplementationOnce(() => new Promise(resolve => { resolveStart = resolve }))
    const view = await mountView()
    try {
      ;(view.host.querySelector('[data-testid="batch-start"]') as HTMLButtonElement).click(); await settle()
      route.query = { batch: 'other-batch' }; await settle()
      resolveStart({ batchId: 'b-1', lifecycle: 'queued', desiredIntent: 'RUN', intentRevision: 1, displayState: 'WAITING_ACCEPT' })
      await settle()
      expect(navigation.replace).not.toHaveBeenCalled()
      expect(localStorage.getItem('linear-lite.batch-start.7.12')).not.toBeNull()
    } finally { view.close() }
  })

  it('shows dependency waiting and uses server facts after stop', async () => {
    route.query = { batch: 'b-1' }
    vi.mocked(collaborationBatchApi.list).mockResolvedValue({ items: [batch], hasMore: false, nextCursor: null })
    const view = await mountView()
    try {
      expect(view.host.textContent).toContain('等待依赖')
      expect(view.host.textContent).not.toContain('TEST-1 Producer')
      expect(view.host.textContent).toContain('前置任务缺少成功成果')
      expect(view.host.textContent).not.toContain('已结束')
      vi.spyOn(window, 'confirm').mockReturnValue(true)
      vi.mocked(collaborationBatchApi.get).mockResolvedValue({ serverTime: '2026-11-10T08:00:00Z', batch: { ...batch, desiredIntent: 'STOP', displayState: 'STOPPING' }, tasks: [task] })
      ;(view.host.querySelector('[data-testid="batch-stop"]') as HTMLButtonElement).click(); await settle()
      expect(collaborationBatchApi.stop).toHaveBeenCalledWith(12, 'b-1', expect.any(String))
      expect(view.host.textContent).toContain('停止中')
      expect(view.host.textContent).not.toContain('已结束')
      view.close()
      expect(collaborationBatchApi.stop).toHaveBeenCalledTimes(1)
    } finally { view.close() }
  })

  it('shows server collaboration facts and budget without exposing internal configuration', async () => {
    route.query = { batch: 'b-1' }
    vi.mocked(collaborationBatchApi.get).mockResolvedValue({ serverTime: '2026-11-10T08:00:00Z', batch, tasks: [{ ...task,
      dependencyFacts: [{ operationId: 'fact-1', accepted: true, changed: true, outcome: 'RECORDED',
        change: { action: 'ADD', missing: '缺少接口说明', reason: '实现需要接口', evidence: '当前目录缺少说明' } }],
      prerequisiteReviewStatuses: [{ sourceTaskId: 9, sourceAttemptId: 3, sourceInputRevision: 1,
        adoptionRequired: true, materialStatus: 'REVIEW_REQUIRED', reason: '采用材料已变化' }],
    }] } as never)
    const view = await mountView()
    try {
      expect(view.host.textContent).toContain('缺少接口说明')
      expect(view.host.textContent).toContain('当前目录缺少说明')
      expect(view.host.textContent).toContain('采用材料已变化')
      expect(view.host.textContent).toContain('1 / 5')
      expect(view.host.textContent).not.toContain('写入范围')
      expect(view.host.textContent).not.toContain('前置任务编号')
    } finally { view.close() }
  })

  it('authorizes an additional goal from batch facts and retains a link to the new batch', async () => {
    route.query = { batch: 'b-1' }
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const goalRequest = { batchId: 'b-1', requestOperationId: 'request-1',
      goal: '新增客户管理', impact: '需要调整接口', authorizedBatchId: null }
    vi.mocked(collaborationBatchApi.get).mockResolvedValue({ serverTime: '2026-11-10T08:00:00Z', batch, tasks: [{ ...task,
      goalAuthorizationRequests: [goalRequest],
    }] } as never)
    vi.mocked(collaborationBatchApi.authorizeGoal).mockImplementation(async () => {
      vi.mocked(collaborationBatchApi.get).mockResolvedValue({ serverTime: '2026-11-10T08:00:00Z', batch, tasks: [{ ...task,
        goalAuthorizationRequests: [{ ...goalRequest, authorizedBatchId: 'b-2' }],
      }] } as never)
      return { batchId: 'b-2', lifecycle: 'queued', desiredIntent: 'RUN', intentRevision: 1, displayState: 'WAITING_ACCEPT' }
    })
    const view = await mountView()
    try {
      expect(view.host.textContent).toContain('新增客户管理')
      expect(view.host.textContent).toContain('需要调整接口')
      ;(view.host.querySelector('.goal-authorize') as HTMLButtonElement).click()
      await settle()
      expect(confirmation).toHaveBeenCalledOnce()
      expect(collaborationBatchApi.authorizeGoal).toHaveBeenCalledWith(12, 'b-1', 'request-1')
      expect(view.host.querySelector('.goal-authorization a')).toBeTruthy()
      expect(navigation.replace).not.toHaveBeenCalled()
    } finally { view.close() }
  })
})
