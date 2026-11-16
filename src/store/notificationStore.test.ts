import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'
import { useAuthStore } from './authStore'
import { useNotificationStore } from './notificationStore'

vi.mock('../services/api/notifications', () => ({
  notificationsApi: {
    unreadCount: vi.fn().mockResolvedValue(0),
    list: vi.fn().mockResolvedValue([])
  }
}))
vi.mock('../services/api/auth', () => ({
  authApi: { startDocumentAssetSession: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined) }
}))

function tokenFor(id: number) { return `header.${btoa(JSON.stringify({ sub: String(id) }))}.signature` }

const SSE_RECONNECT_INITIAL_MS = 1000

let instances: MockEventSource[] = []

class MockEventSource {
  url: string
  onerror: ((ev: Event) => void) | null = null
  addEventListener(_type: string, cb: () => void) {
    if (_type === 'open') {
      queueMicrotask(() => cb())
    }
  }
  close = vi.fn()
  constructor(url: string) {
    this.url = url
    instances.push(this)
  }
}

describe('notificationStore SSE', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(1))
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: 1, username: 'tester' }))
    vi.useFakeTimers({ shouldAdvanceTime: true })
    instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('schedules reconnect after EventSource error', async () => {
    useNotificationStore()
    await Promise.resolve()
    await Promise.resolve()
    expect(instances.length).toBe(1)

    instances[0]!.onerror?.(new Event('error'))
    await vi.advanceTimersByTimeAsync(SSE_RECONNECT_INITIAL_MS)
    expect(instances.length).toBe(2)
  })

  it('cancels pending reconnect on logout', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.setSession(tokenFor(1), 1, 'tester')
    useNotificationStore()
    await Promise.resolve()
    await Promise.resolve()
    expect(instances.length).toBe(1)

    instances[0]!.onerror?.(new Event('error'))
    auth.logout()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(instances.length).toBe(1)
  })

  it('does not reconnect using another account token before the storage event', async () => {
    const notifications = useNotificationStore()
    await Promise.resolve()
    expect(instances).toHaveLength(1)
    localStorage.setItem(JWT_STORAGE_KEY, tokenFor(2))
    notifications.connectStream()
    expect(instances).toHaveLength(1)
    expect(instances[0]!.close).toHaveBeenCalled()
    expect(useAuthStore().currentUser).toBeNull()
  })
})
