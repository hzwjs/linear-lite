import { beforeEach, describe, expect, it, vi } from 'vitest'
import { activityApi } from '../src/services/api/activity'

const mockApiResponse = <T>(data: T) => ({
  data: { code: 200, data }
})

vi.mock('../src/services/api/index', () => ({
  api: {
    get: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api } = await import('../src/services/api/index')

beforeEach(() => {
  vi.mocked(api.get).mockReset()
})

describe('activityApi', () => {
  it('list: GET /tasks/ENG-1/activities', async () => {
    vi.mocked(api.get).mockResolvedValue(mockApiResponse([
      {
        id: 1,
        actionType: 'changed',
        fieldName: 'status',
        oldValue: 'todo',
        newValue: 'in_progress',
        actorName: 'alice',
        createdAt: '2026-03-14T10:00:00'
      }
    ]))

    const activities = await activityApi.list('ENG-1')

    expect(api.get).toHaveBeenCalledWith('/tasks/ENG-1/activities', {
      params: { limit: 50 }
    })
    expect(activities[0]).toMatchObject({
      actionType: 'changed',
      fieldName: 'status',
      actorName: 'alice'
    })
    expect(activities[0]?.createdAt).toBe(new Date('2026-03-14T10:00:00').getTime())
  })
})
