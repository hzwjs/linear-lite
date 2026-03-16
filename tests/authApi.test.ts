import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from '../src/services/api/auth'

const mockApiResponse = <T>(data: T) => ({
  data: { code: 200, data }
})

vi.mock('../src/services/api/index', () => ({
  api: {
    post: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api } = await import('../src/services/api/index')

beforeEach(() => {
  vi.mocked(api.post).mockReset()
})

describe('authApi', () => {
  it('login posts identity and password', async () => {
    vi.mocked(api.post).mockResolvedValue(mockApiResponse({
      token: 'jwt',
      userId: 1,
      username: 'alice'
    }))

    const result = await authApi.login({ identity: 'alice@example.com', password: 'secret123' })

    expect(api.post).toHaveBeenCalledWith('/auth/login', {
      identity: 'alice@example.com',
      password: 'secret123'
    })
    expect(result.username).toBe('alice')
  })

  it('sendRegisterCode posts email', async () => {
    vi.mocked(api.post).mockResolvedValue(mockApiResponse(undefined))

    await authApi.sendRegisterCode({ email: 'new@example.com' })

    expect(api.post).toHaveBeenCalledWith('/auth/register/send-code', {
      email: 'new@example.com'
    })
  })

  it('register posts email, code, username and password', async () => {
    vi.mocked(api.post).mockResolvedValue(mockApiResponse({
      token: 'jwt',
      userId: 5,
      username: 'new-user'
    }))

    const result = await authApi.register({
      email: 'new@example.com',
      code: '123456',
      username: 'new-user',
      password: 'secret123'
    })

    expect(api.post).toHaveBeenCalledWith('/auth/register', {
      email: 'new@example.com',
      code: '123456',
      username: 'new-user',
      password: 'secret123'
    })
    expect(result.userId).toBe(5)
  })
})
