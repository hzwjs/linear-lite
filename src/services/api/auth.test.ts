import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { authApi } from './auth'

vi.mock('./index', () => ({
  api: {
    post: vi.fn()
  },
  unwrap: (res: { data: { data: unknown } }) => res.data.data
}))

describe('authApi password reset', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset()
  })

  it('sends a password reset code', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, data: null } } as any)

    await authApi.sendPasswordResetCode({ email: 'alice@example.com' })

    expect(api.post).toHaveBeenCalledWith('/auth/password-reset/send-code', { email: 'alice@example.com' })
  })

  it('resets the password with the verified code', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, data: null } } as any)

    await authApi.resetPassword({
      email: 'alice@example.com',
      code: '654321',
      password: 'new-secret'
    })

    expect(api.post).toHaveBeenCalledWith('/auth/password-reset', {
      email: 'alice@example.com',
      code: '654321',
      password: 'new-secret'
    })
  })
})
