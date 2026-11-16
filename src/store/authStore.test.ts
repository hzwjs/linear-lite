import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, disposePinia, getActivePinia, setActivePinia } from 'pinia'
import { useAuthStore } from './authStore'
import { authApi } from '../services/api/auth'
import { AUTH_SESSION_CHANGED_EVENT, JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'

vi.mock('../services/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    sendRegisterCode: vi.fn(),
    sendPasswordResetCode: vi.fn(),
    resetPassword: vi.fn(),
    startDocumentAssetSession: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined)
  }
}))

function tokenFor(id: number) { return `header.${btoa(JSON.stringify({ sub: String(id) }))}.signature` }

describe('authStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.register).mockReset()
    vi.mocked(authApi.sendRegisterCode).mockReset()
    vi.mocked(authApi.sendPasswordResetCode).mockReset()
    vi.mocked(authApi.resetPassword).mockReset()
  })

  afterEach(() => {
    const pinia = getActivePinia()
    if (pinia) disposePinia(pinia)
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

  it('sends password reset code and resets password without creating a session', async () => {
    const store = useAuthStore()

    await store.sendPasswordResetCode('alice@example.com')
    await store.resetPassword({
      email: 'alice@example.com',
      code: '654321',
      password: 'new-secret'
    })

    expect(authApi.sendPasswordResetCode).toHaveBeenCalledWith({ email: 'alice@example.com' })
    expect(authApi.resetPassword).toHaveBeenCalledWith({
      email: 'alice@example.com',
      code: '654321',
      password: 'new-secret'
    })
    expect(store.isLoggedIn).toBe(false)
  })

  it('invalidates the current page when another tab changes the account and preserves the other session', () => {
    const store = useAuthStore()
    const firstToken = tokenFor(3), secondToken = tokenFor(4)
    store.setSession(firstToken, 3, 'alice')
    const changed = vi.fn()
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, changed)
    localStorage.setItem(JWT_STORAGE_KEY, secondToken)
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: 4, username: 'bob' }))
    window.dispatchEvent(new StorageEvent('storage', { key: JWT_STORAGE_KEY, newValue: secondToken }))

    expect(store.currentUser).toBeNull()
    expect(store.jwtToken).toBeNull()
    expect(store.sessionInvalidated).toBe(true)
    expect(changed).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe(secondToken)
    store.logout()
    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe(secondToken)
    expect(vi.mocked(authApi.logout)).not.toHaveBeenCalled()
    window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, changed)
  })

  it('rejects a half-written account transition before its storage event arrives', () => {
    const store = useAuthStore()
    store.setSession(tokenFor(3), 3, 'alice')
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(4))

    expect(store.getConsistentAccessToken()).toBeNull()
    expect(store.currentUser).toBeNull()
    expect(store.sessionInvalidated).toBe(true)
    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe(tokenFor(4))
  })

  it('rejects a token and user pair belonging to different accounts on startup', () => {
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(4))
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: 3, username: 'alice' }))
    const store = useAuthStore()
    expect(store.getConsistentAccessToken()).toBeNull()
    expect(store.sessionInvalidated).toBe(true)
  })

  it('permits a new login after the old page session was invalidated', async () => {
    const store = useAuthStore()
    store.setSession(tokenFor(3), 3, 'alice')
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(4))
    store.getConsistentAccessToken()
    vi.mocked(authApi.login).mockResolvedValue({ token: tokenFor(4), userId: 4, username: 'bob' })

    await store.login({ identity: 'bob', password: 'example' })
    expect(store.getConsistentAccessToken()).toBe(tokenFor(4))
    expect(store.sessionInvalidated).toBe(false)
  })
})
