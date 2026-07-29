import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { analyticsApi } from '../services/api/analytics'
import { useAnalyticsStore } from './analyticsStore'

vi.mock('../services/api/analytics', () => ({
  analyticsApi: {
    getSummary: vi.fn(),
    getTasks: vi.fn()
  }
}))

describe('analyticsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(analyticsApi.getSummary).mockReset()
    vi.mocked(analyticsApi.getTasks).mockReset()
  })

  it('starts in the weekly review mode', () => {
    expect(useAnalyticsStore().granularity).toBe('week')
  })

  it('sends the selected metric as the only task-list scope', async () => {
    vi.mocked(analyticsApi.getTasks).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 50 })
    const store = useAnalyticsStore()

    await store.fetchTasks(7, '2026-07-27T00:00:00', '2026-08-02T23:59:59', 1, 'overdue')

    expect(analyticsApi.getTasks).toHaveBeenCalledWith({
      projectId: 7,
      granularity: 'week',
      from: '2026-07-27T00:00:00',
      to: '2026-08-02T23:59:59',
      page: 1,
      pageSize: 50,
      taskListScope: 'overdue'
    })
  })
})
