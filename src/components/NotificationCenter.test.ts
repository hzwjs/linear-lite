import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import NotificationCenter from './NotificationCenter.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../store/notificationStore', () => ({
  useNotificationStore: () => ({
    unreadCount: 0,
    items: [],
    loading: false,
    fetchList: vi.fn().mockResolvedValue(undefined),
    markRead: vi.fn().mockResolvedValue(undefined),
    markAllRead: vi.fn().mockResolvedValue(undefined)
  })
}))

describe('NotificationCenter', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('removes document click listener when unmounted while open', async () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(NotificationCenter)
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const bell = host.querySelector('.notification-bell') as HTMLButtonElement
    bell.click()
    await nextTick()

    app.unmount()
    host.remove()

    expect(removeSpy).toHaveBeenCalledWith('click', expect.any(Function), true)
  })
})
