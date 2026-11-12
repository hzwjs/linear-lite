import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { taskCollaborationApi } from './taskCollaboration'

describe('task collaboration state API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('reads current execution facts using only the task key', async () => {
    const state = { serverTime: '2026-11-10T00:00:00Z', inputRevision: 5, status: 'todo',
      progressPercent: 0, completedAt: null, updatedAt: '2026-11-10T00:00:00',
      activeOrFrozen: false, attempts: [], attemptBindings: [], bindings: [], contextFacts: [],
      prerequisiteReviewFacts: [], dependencyFacts: [], goalAuthorizationRequests: [] }
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { code: 200, data: state } } as never)
    await expect(taskCollaborationApi.state('LL-1')).resolves.toEqual(state)
    expect(post).toHaveBeenCalledWith('/collaboration/tasks/state', { taskKey: 'LL-1' })
    expect(Object.keys(taskCollaborationApi)).toEqual(['state'])
  })

  it('reports access errors from the service', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ isAxiosError: true, response: {
      status: 403, data: { message: '无任务访问权限' },
    } })
    await expect(taskCollaborationApi.state('LL-1')).rejects.toThrow('无任务访问权限')
  })
})
