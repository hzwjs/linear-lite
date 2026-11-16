import { AxiosError, CanceledError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../../store/authStore'
import { api } from './index'
import { agentApi } from './agent'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from './constants'

vi.mock('./auth', () => ({
  authApi: { startDocumentAssetSession: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined) }
}))

function tokenFor(id: number) { return `header.${btoa(JSON.stringify({ sub: String(id) }))}.signature` }

describe('account-bound requests', () => {
  let pinia: ReturnType<typeof createPinia>
  let oldAdapter: AxiosRequestConfig['adapter']

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    oldAdapter = api.defaults.adapter
  })

  afterEach(() => {
    api.defaults.adapter = oldAdapter
    disposePinia(pinia)
  })

  it('refuses a request when shared storage switches token before the account event', async () => {
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'Alice')
    const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} })
    api.defaults.adapter = adapter
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(2))

    await expect(api.get('/projects')).rejects.toBeInstanceOf(CanceledError)
    expect(adapter).not.toHaveBeenCalled()
    expect(auth.currentUser).toBeNull()
    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe(tokenFor(2))
  })

  it('keeps public authentication requests available after invalidation', async () => {
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'Alice')
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(2))
    auth.getConsistentAccessToken()
    const adapter = vi.fn(async (config) => ({ status: 200, statusText: 'OK', headers: {}, config, data: {} }))
    api.defaults.adapter = adapter
    await api.post('/auth/login', {})
    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0]![0].headers.get('Authorization')).toBeUndefined()
  })

  it('binds a request to its initiating account and rejects its response after another login', async () => {
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'Alice')
    const adapter = vi.fn(async (config) => ({ status: 200, statusText: 'OK', headers: {}, config, data: {} }))
    api.defaults.adapter = adapter
    const request = api.get('/projects')
    auth.setSession(tokenFor(2), 2, 'Bob')
    expect(adapter.mock.calls[0]![0].headers.get('Authorization')).toBe(`Bearer ${tokenFor(1)}`)
    await expect(request).rejects.toBeInstanceOf(CanceledError)
    expect(auth.currentUser?.id).toBe(2)
  })

  it('does not log out a new account when an old request returns 401', async () => {
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'Alice')
    let rejectRequest!: (error: AxiosError) => void
    let sent!: AxiosRequestConfig
    api.defaults.adapter = (config) => {
      sent = config
      return new Promise<AxiosResponse>((_resolve, reject) => { rejectRequest = reject })
    }
    const request = api.get('/projects')
    await vi.waitFor(() => expect(rejectRequest).toBeDefined())
    auth.setSession(tokenFor(2), 2, 'Bob')
    rejectRequest(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', sent as never, undefined,
      { config: sent as never, status: 401, statusText: 'Unauthorized', headers: {}, data: {} }))
    await expect(request).rejects.toBeInstanceOf(AxiosError)
    expect(auth.currentUser?.id).toBe(2)
    expect(localStorage.getItem(JWT_STORAGE_KEY)).toBe(tokenFor(2))
    expect(localStorage.getItem(USER_STORAGE_KEY)).toContain('Bob')
  })

  it('refuses an Agent stream when storage holds another account token', () => {
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'Alice')
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(2))
    const source = vi.fn()
    vi.stubGlobal('EventSource', source)
    expect(() => agentApi.openSessionStream('T-1', 'run-1', vi.fn(), vi.fn(), vi.fn())).toThrow('缺少登录凭证')
    expect(source).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

})
