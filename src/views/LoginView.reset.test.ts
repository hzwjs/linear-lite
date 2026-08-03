import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import LoginView from './LoginView.vue'

const authMocks = vi.hoisted(() => ({
  router: { push: vi.fn() },
  store: {
    sendRegisterCode: vi.fn(),
    sendPasswordResetCode: vi.fn().mockResolvedValue(undefined),
    login: vi.fn(),
    register: vi.fn(),
    resetPassword: vi.fn().mockResolvedValue(undefined)
  }
}))

vi.mock('vue-router', () => ({
  useRouter: () => authMocks.router
}))

vi.mock('../store/authStore', () => ({
  useAuthStore: () => authMocks.store
}))

function setInput(host: HTMLElement, selector: string, value: string) {
  const input = host.querySelector(selector) as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('LoginView password reset', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('sends a reset code and submits the new password without logging in automatically', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(LoginView)
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('.login-link') as HTMLButtonElement).click()
    await nextTick()
    setInput(host, 'input[placeholder="Email"]', 'alice@example.com')
    await nextTick()
    ;(host.querySelector('.verification-button') as HTMLButtonElement).click()
    await nextTick()

    expect(authMocks.store.sendPasswordResetCode).toHaveBeenCalledWith('alice@example.com')

    setInput(host, 'input[placeholder="Verification code"]', '654321')
    setInput(host, 'input[placeholder="New password"]', 'new-secret')
    setInput(host, 'input[placeholder="Confirm new password"]', 'new-secret')
    await nextTick()
    ;(host.querySelector('.login-submit') as HTMLButtonElement).click()
    await nextTick()
    await nextTick()

    expect(authMocks.store.resetPassword).toHaveBeenCalledWith({
      email: 'alice@example.com',
      code: '654321',
      password: 'new-secret'
    })
    expect(host.textContent).toContain('Password reset. You can now log in.')
    expect(authMocks.router.push).not.toHaveBeenCalled()

    app.unmount()
  })

  it('replaces a raw HTTP error with an actionable reset message', async () => {
    i18n.global.locale.value = 'zh-CN'
    authMocks.store.resetPassword.mockRejectedValueOnce(new Error('Request failed with status code 401'))

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(LoginView)
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('.login-link') as HTMLButtonElement).click()
    await nextTick()
    setInput(host, 'input[placeholder="邮箱"]', 'alice@example.com')
    setInput(host, 'input[placeholder="验证码"]', '654321')
    setInput(host, 'input[placeholder="新密码"]', 'new-secret')
    setInput(host, 'input[placeholder="确认新密码"]', 'new-secret')
    await nextTick()

    ;(host.querySelector('.login-submit') as HTMLButtonElement).click()
    await nextTick()
    await nextTick()
    await nextTick()

    expect(host.textContent).toContain('密码重置失败，请检查验证码和新密码后重试。')
    expect(host.textContent).not.toContain('Request failed with status code 401')

    app.unmount()
  })
})
