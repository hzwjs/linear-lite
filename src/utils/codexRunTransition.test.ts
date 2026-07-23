import { describe, expect, it } from 'vitest'
import { shouldRefreshCodexResultComment } from './codexRunTransition'

describe('shouldRefreshCodexResultComment', () => {
  it('仅在活动执行进入 completed 时刷新结果评论', () => {
    expect(shouldRefreshCodexResultComment('running', 'completed')).toBe(true)
    expect(shouldRefreshCodexResultComment('completed', 'completed')).toBe(false)
    expect(shouldRefreshCodexResultComment('running', 'failed')).toBe(false)
    expect(shouldRefreshCodexResultComment(null, 'completed')).toBe(false)
  })
})
