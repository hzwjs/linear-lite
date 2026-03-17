import { describe, expect, it, beforeEach } from 'vitest'
import { i18n } from '../i18n'
import loginViewSource from './LoginView.vue?raw'

describe('LoginView i18n', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('wires LoginView text and validation through i18n', () => {
    expect(loginViewSource).toContain("const { t } = useI18n()")
    expect(loginViewSource).toContain("{{ t('auth.tabs.login') }}")
    expect(loginViewSource).toContain(":placeholder=\"t('auth.placeholder.identity')\"")
    expect(loginViewSource).toContain("error.value = t('auth.error.enterCredentials')")
    expect(loginViewSource).toContain("error.value = e instanceof Error ? e.message : t('auth.error.authFailed')")
  })

  it('provides both tab labels and placeholders', () => {
    expect(i18n.global.t('auth.tabs.login')).toBe('Log in')
    expect(i18n.global.t('auth.tabs.register')).toBe('Sign up')
    expect(i18n.global.t('auth.placeholder.identity')).toBe('Email or username')
    expect(i18n.global.t('auth.placeholder.password')).toBe('Password')
  })

  it('switches preset strings when locale changes', () => {
    i18n.global.locale.value = 'zh-CN'
    expect(i18n.global.t('auth.subtitle.login')).toBe('登录以继续')
    expect(i18n.global.t('auth.action.signUp')).toBe('创建账号')
  })

  it('exposes validation and flow errors per locale', () => {
    expect(i18n.global.t('auth.error.enterEmail')).toBe('Please enter email')
    expect(i18n.global.t('auth.error.completeRegistration')).toBe('Please complete all registration fields')
    i18n.global.locale.value = 'zh-CN'
    expect(i18n.global.t('auth.error.enterCredentials')).toBe('请输入邮箱/用户名和密码')
    expect(i18n.global.t('auth.error.authFailed')).toBe('认证失败')
  })
})
