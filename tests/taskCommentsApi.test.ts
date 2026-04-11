import { beforeEach, describe, expect, it, vi } from 'vitest'
import { taskCommentsApi } from '../src/services/api/taskComments'

vi.mock('../src/services/api/index', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api, unwrap } = await import('../src/services/api/index')

function makeComment(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    authorId: 1,
    authorName: 'Alice',
    body: 'Hello',
    parentId: null,
    rootId: null,
    depth: 0,
    createdAt: '2026-04-10T00:00:00.000Z',
    deletable: true,
    ...overrides
  }
}

describe('taskCommentsApi.list', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('supports array data shape and normalizes parentId/rootId/depth', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: {
        code: 200,
        data: [
          makeComment({
            id: 10,
            parentId: 'x',
            rootId: undefined,
            depth: undefined
          })
        ]
      }
    })

    const list = await taskCommentsApi.list('ENG-1')

    expect(api.get).toHaveBeenCalledWith('/tasks/ENG-1/comments')
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({
      id: 10,
      parentId: null,
      rootId: null,
      depth: 0
    })
  })

  it('supports object data shape { taskComments } and normalizes parentId/rootId/depth', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: {
        code: 200,
        data: {
          taskComments: [
            makeComment({
              id: 20,
              parentId: 10,
              rootId: 'bad',
              depth: NaN
            })
          ]
        }
      }
    })

    const list = await taskCommentsApi.list('ENG-1')

    expect(unwrap).toHaveBeenCalled()
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({
      id: 20,
      parentId: 10,
      rootId: null,
      depth: 0
    })
  })
})
