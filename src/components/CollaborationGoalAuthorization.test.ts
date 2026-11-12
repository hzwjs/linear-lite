import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CollaborationGoalAuthorization from './CollaborationGoalAuthorization.vue'
import { collaborationBatchApi, type GoalAuthorizationRequest } from '../services/api/collaborationBatch'

vi.mock('../services/api/collaborationBatch', () => ({
  collaborationBatchApi: { authorizeGoal: vi.fn() },
}))

const messages = {
  collaborationBatch: {
    goalTitle: '新增目标', goalImpact: '影响', goalAuthorize: '授权执行',
    goalConfirm: '授权目标 {goal}？影响 {impact}', goalAuthorizing: '授权中',
    goalFailed: '授权失败', goalViewBatch: '查看新批次',
  },
}
const request = { batchId: 'old-batch', requestOperationId: 'request-1', goal: '补充业务能力',
  impact: '需要追加设计', authorizedBatchId: null }
const receipt = { batchId: 'new-batch', lifecycle: 'queued' as const, desiredIntent: 'RUN' as const,
  intentRevision: 1, displayState: 'WAITING_ACCEPT' }

async function settle() {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

async function mountComponent() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const state = reactive<{ projectId: number; requests: GoalAuthorizationRequest[] }>({ projectId: 12, requests: [{ ...request }] })
  const authorized = vi.fn(() => {
    state.requests = state.requests.map(item => ({ ...item, authorizedBatchId: receipt.batchId }))
  })
  const app = createApp({ render: () => h(CollaborationGoalAuthorization, {
    projectId: state.projectId, requests: state.requests, onAuthorized: authorized,
  }) })
  app.component('router-link', {
    props: ['to'],
    render() { return h('a', { 'data-batch': this.to.query.batch }, this.$slots.default?.()) },
  })
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': messages } }))
  app.mount(host)
  await settle()
  return { host, state, authorized, close() { app.unmount(); host.remove() } }
}

describe('additional goal authorization', () => {
  beforeEach(() => { document.body.innerHTML = ''; vi.clearAllMocks() })
  afterEach(() => { vi.restoreAllMocks() })

  it('shows the goal and impact, confirms the action, then links to the returned batch', async () => {
    let resolve!: (result: typeof receipt) => void
    vi.mocked(collaborationBatchApi.authorizeGoal).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const view = await mountComponent()
    try {
      expect(view.host.textContent).toContain('补充业务能力')
      expect(view.host.textContent).toContain('需要追加设计')
      expect(view.host.textContent).not.toContain('request-1')
      const button = view.host.querySelector('button')!
      button.click()
      button.click()
      await nextTick()
      expect(confirmation).toHaveBeenCalledTimes(1)
      expect(confirmation.mock.calls[0]![0]).toContain('需要追加设计')
      expect(collaborationBatchApi.authorizeGoal).toHaveBeenCalledWith(12, 'old-batch', 'request-1')
      expect(collaborationBatchApi.authorizeGoal).toHaveBeenCalledTimes(1)
      expect(button.disabled).toBe(true)
      resolve(receipt)
      await settle()
      expect(view.authorized).toHaveBeenCalledTimes(1)
      expect(view.host.querySelector('a')?.getAttribute('data-batch')).toBe('new-batch')
      expect(view.host.querySelector('button')).toBeNull()
    } finally { view.close() }
  })

  it('keeps the goal available after cancellation and after an error', async () => {
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
    vi.mocked(collaborationBatchApi.authorizeGoal).mockRejectedValueOnce(new Error('服务暂不可用')).mockResolvedValueOnce(receipt)
    const view = await mountComponent()
    try {
      view.host.querySelector('button')!.click()
      expect(collaborationBatchApi.authorizeGoal).not.toHaveBeenCalled()
      view.host.querySelector('button')!.click()
      await settle()
      expect(view.host.querySelector('[role="alert"]')?.textContent).toBe('服务暂不可用')
      view.host.querySelector('button')!.click()
      await settle()
      expect(confirmation).toHaveBeenCalledTimes(3)
      expect(collaborationBatchApi.authorizeGoal).toHaveBeenCalledTimes(2)
      expect(view.host.querySelector('a')?.getAttribute('data-batch')).toBe('new-batch')
    } finally { view.close() }
  })

  it('shows a server-confirmed batch after remount without authorizing again', async () => {
    const view = await mountComponent()
    try {
      view.state.requests = [{ ...request, authorizedBatchId: 'already-created' }]
      await nextTick()
      expect(view.host.querySelector('a')?.getAttribute('data-batch')).toBe('already-created')
      expect(view.host.querySelector('button')).toBeNull()
      expect(collaborationBatchApi.authorizeGoal).not.toHaveBeenCalled()
    } finally { view.close() }
  })

  it('ignores a response from an earlier project without leaving the action disabled', async () => {
    let resolve!: (result: typeof receipt) => void
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(collaborationBatchApi.authorizeGoal).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const view = await mountComponent()
    try {
      view.host.querySelector('button')!.click()
      view.state.projectId = 13
      await nextTick()
      view.state.projectId = 12
      await nextTick()
      resolve(receipt)
      await settle()
      expect(view.authorized).not.toHaveBeenCalled()
      expect(view.host.querySelector('a')).toBeNull()
      expect(view.host.querySelector('button')?.disabled).toBe(false)
    } finally { view.close() }
  })
})
