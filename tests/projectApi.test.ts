import { beforeEach, describe, expect, it, vi } from 'vitest'
import { projectApi } from '../src/services/api/project'

const mockApiResponse = <T>(data: T) => ({
  data: { code: 200, data }
})

vi.mock('../src/services/api/index', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api } = await import('../src/services/api/index')

beforeEach(() => {
  vi.mocked(api.get).mockReset()
  vi.mocked(api.post).mockReset()
  vi.mocked(api.put).mockReset()
  vi.mocked(api.delete).mockReset()
})

describe('projectApi', () => {
  it('invite posts email to project invitation route', async () => {
    vi.mocked(api.post).mockResolvedValue(mockApiResponse(undefined))

    await projectApi.invite(3, { email: 'new@example.com' })

    expect(api.post).toHaveBeenCalledWith('/projects/3/invitations', {
      email: 'new@example.com'
    })
  })
})
