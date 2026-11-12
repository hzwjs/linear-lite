import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import zhCN from '../i18n/messages/zh-CN'
import CollaborationAttemptDetails from './CollaborationAttemptDetails.vue'
import type { CollaborationAttempt } from '../services/api/collaborationBatch'

describe('collaboration attempt details', () => {
  it('renders checkpoints and delivery evidence as business fields', async () => {
    const attempt: CollaborationAttempt = { id: 1, taskId: 2, bindingId: 3, batchId: 'b', hostInstanceId: 'host',
      agentInstanceId: 'agent', inputRevision: 4, prerequisiteVersions: [], status: 'succeeded',
      leaseUntil: null, checkpoints: [{ operationId: 'op', occurredAt: '2026-11-10T08:00:00',
        finding: '发现接口问题', blockedReason: '等待输入', artifactRefs: ['docs/work.md'] },
      { operationId: 'review', occurredAt: '2026-11-10T08:01:00', kind: 'PREREQUISITE_REVIEW' }],
      resultEvidence: { conditions: [{ condition: '接口可用', result: '通过', evidenceRefs: ['docs/test.md'] }],
        changedPaths: ['src/example.ts'], artifactRefs: ['docs/result.md'], projectCheckpoint: 'git:commit' },
      stopReason: null, stopRequestedAt: null, stopConfirmedAt: '2026-11-10T08:02:00', stopConfirmation: {},
      createdAt: '', updatedAt: '', finishedAt: null }
    const host = document.createElement('div'); document.body.appendChild(host)
    const app = createApp(CollaborationAttemptDetails, { attempt, serverTime: '2026-11-10T08:02:00Z' })
    app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
    app.mount(host); await nextTick()
    try {
      for (const value of ['交付成功', '发现接口问题', '等待输入', 'docs/work.md', '接口可用', 'docs/test.md', 'src/example.ts', 'docs/result.md'])
        expect(host.textContent).toContain(value)
      expect(host.querySelector('pre')).toBeNull()
      expect(host.textContent).not.toContain('projectCheckpoint')
      expect(host.textContent).not.toContain('agent')
      expect(host.textContent).not.toContain('输入版本')
    } finally { app.unmount(); host.remove() }
  })
})
