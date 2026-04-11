import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import LoginView from './LoginView.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../store/authStore', () => ({
  useAuthStore: () => ({
    sendRegisterCode: vi.fn().mockResolvedValue(undefined),
    login: vi.fn().mockResolvedValue(undefined),
    register: vi.fn().mockResolvedValue(undefined)
  })
}))

describe('LoginView unmount', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.useFakeTimers()
    i18n.global.locale.value = 'en'
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('clears resend countdown interval on unmount', async () => {
    const clearSpy = vi.spyOn(window, 'clearInterval')
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(LoginView)
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.login-tab')
    ;(tabs[1] as HTMLButtonElement).click()
    await nextTick()

    const email = host.querySelector('input[autocomplete="email"]') as HTMLInputElement
    email.value = 'a@b.com'
    email.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    const btn = host.querySelector('.verification-button') as HTMLButtonElement
    btn.click()
    await vi.runAllTimersAsync()
    await nextTick()

    app.unmount()
    host.remove()

    expect(clearSpy).toHaveBeenCalled()
  })
})
