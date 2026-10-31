import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import NotificationCenter from './NotificationCenter.vue'
import MobileNotificationsView from '../mobile/views/MobileNotificationsView.vue'

const notificationData = vi.hoisted(() => ({ items: [] as Array<{
  id: number; type: string; taskKey: string; commentId: number | null; summary: string | null; readAt: string | null; createdAt: string
}> }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../store/notificationStore', () => ({
  useNotificationStore: () => ({
    unreadCount: 0,
    items: notificationData.items,
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
    notificationData.items.splice(0)
    i18n.global.locale.value = 'en'
  })

  it('shows assignment and mention notifications with their own labels on desktop and mobile', async () => {
    notificationData.items.push(
      { id: 1, type: 'assignment', taskKey: 'ENG-1', commentId: null, summary: 'Review <draft>', readAt: null, createdAt: '2026-10-30T10:00:00Z' },
      { id: 2, type: 'mention', taskKey: 'ENG-2', commentId: 4, summary: 'Please review', readAt: null, createdAt: '2026-10-30T10:00:00Z' }
    )
    const desktop = document.createElement('div')
    const mobile = document.createElement('div')
    document.body.append(desktop, mobile)
    const desktopApp = createApp(NotificationCenter).use(i18n)
    const mobileApp = createApp(MobileNotificationsView).use(i18n)
    desktopApp.mount(desktop)
    mobileApp.mount(mobile)
    ;(desktop.querySelector('.notification-bell') as HTMLButtonElement).click()
    await nextTick()

    expect(desktop.textContent).toContain('Assigned ENG-1 to you')
    expect(desktop.textContent).toContain('Mentioned you on ENG-2')
    expect(desktop.textContent).toContain('Review <draft>')
    expect(mobile.textContent).toContain('Assigned ENG-1 to you')
    expect(mobile.textContent).toContain('Mentioned you on ENG-2')
    expect(mobile.textContent).toContain('Review <draft>')
    desktopApp.unmount()
    mobileApp.unmount()
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
