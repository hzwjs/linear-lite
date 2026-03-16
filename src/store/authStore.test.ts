import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './authStore'
import { authApi } from '../services/api/auth'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'

vi.mock('../services/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    sendRegisterCode: vi.fn()
  }
}))

describe('authStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.register).mockReset()
    vi.mocked(authApi.sendRegisterCode).mockReset()
  })

  it('stores session after email login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'jwt-token',
      userId: 3,
      username: 'alice'
    })

    const store = useAuthStore()
    await store.login({ identity: 'alice@example.com', password: 'secret123' })

    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe('jwt-token')
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBe(JSON.stringify({ id: 3, username: 'alice' }))
    expect(store.currentUser).toEqual({ id: 3, username: 'alice' })
  })

  it('stores session after register', async () => {
    vi.mocked(authApi.register).mockResolvedValue({
      token: 'jwt-token',
      userId: 8,
      username: 'new-user'
    })

    const store = useAuthStore()
    await store.register({
      email: 'new@example.com',
      code: '123456',
      username: 'new-user',
      password: 'secret123'
    })

    expect(store.isLoggedIn).toBe(true)
    expect(store.currentUser).toEqual({ id: 8, username: 'new-user' })
  })
})
