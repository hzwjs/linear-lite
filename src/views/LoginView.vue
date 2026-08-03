<script setup lang="ts">
import { computed, ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore'
import { toApiError } from '../services/api'

const router = useRouter()
const authStore = useAuthStore()

const mode = ref<'login' | 'register' | 'reset'>('login')
const identity = ref('')
const email = ref('')
const verificationCode = ref('')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const loading = ref(false)
const sendingCode = ref(false)
const resendCountdown = ref(0)
let resendTimer: number | null = null

const { t } = useI18n()
const isLoginMode = computed(() => mode.value === 'login')
const isRegisterMode = computed(() => mode.value === 'register')
const isResetMode = computed(() => mode.value === 'reset')

function resetError() {
  error.value = null
  notice.value = null
}

function getAuthErrorMessage(errorValue: unknown, fallbackKey: string) {
  const message = toApiError(errorValue).message
  const localizedMessage = {
    'Email is already registered.': t('auth.error.emailAlreadyRegistered'),
    'Email is not registered.': t('auth.error.emailNotRegistered'),
    'Email format is invalid.': t('auth.error.invalidEmail'),
    'Email is required.': t('auth.error.enterEmail'),
    'Incorrect email/username or password.': t('auth.error.invalidCredentials'),
    'Incorrect verification code.': t('auth.error.invalidVerificationCode'),
    'Verification code has expired.': t('auth.error.verificationCodeExpired'),
    'Verification code is required.': t('auth.error.enterVerificationCode'),
    'Password is required.': t('auth.error.enterPassword'),
    'Password must be at least 6 characters.': t('auth.error.passwordTooShort'),
    'Username is required.': t('auth.error.enterUsername'),
    'Username already exists.': t('auth.error.usernameAlreadyExists')
  }[message]

  // 网络异常和 HTTP 状态文本无法指导用户操作，改用当前操作的明确提示。
  if (message === 'Network Error' || message.startsWith('Request failed')) {
    return t(fallbackKey)
  }
  return localizedMessage ?? message
}

function switchMode(nextMode: 'login' | 'register' | 'reset') {
  mode.value = nextMode
  resetError()
}

function startResendCountdown() {
  resendCountdown.value = 60
  if (resendTimer) {
    window.clearInterval(resendTimer)
  }
  resendTimer = window.setInterval(() => {
    if (resendCountdown.value <= 1) {
      resendCountdown.value = 0
      if (resendTimer) {
        window.clearInterval(resendTimer)
        resendTimer = null
      }
      return
    }
    resendCountdown.value -= 1
  }, 1000)
}

onUnmounted(() => {
  if (resendTimer != null) {
    window.clearInterval(resendTimer)
    resendTimer = null
  }
})

async function onSendCode() {
  resetError()
  if (!email.value.trim()) {
    error.value = t('auth.error.enterEmail')
    return
  }

  sendingCode.value = true
  try {
    if (isRegisterMode.value) {
      await authStore.sendRegisterCode(email.value.trim())
    } else {
      await authStore.sendPasswordResetCode(email.value.trim())
    }
    startResendCountdown()
  } catch (e) {
    error.value = getAuthErrorMessage(e, 'auth.error.sendCodeFailed')
  } finally {
    sendingCode.value = false
  }
}

async function onSubmit() {
  resetError()
  const wasResetMode = isResetMode.value
  loading.value = true
  try {
    if (isLoginMode.value) {
      if (!identity.value.trim() || !password.value) {
        error.value = t('auth.error.enterCredentials')
        return
      }
      await authStore.login({ identity: identity.value.trim(), password: password.value })
    } else if (isRegisterMode.value) {
      if (!email.value.trim() || !verificationCode.value.trim() || !username.value.trim() || !password.value) {
        error.value = t('auth.error.completeRegistration')
        return
      }
      await authStore.register({
        email: email.value.trim(),
        code: verificationCode.value.trim(),
        username: username.value.trim(),
        password: password.value
      })
    } else {
      if (!email.value.trim() || !verificationCode.value.trim() || !password.value || !confirmPassword.value) {
        error.value = t('auth.error.completeReset')
        return
      }
      if (password.value !== confirmPassword.value) {
        error.value = t('auth.error.passwordMismatch')
        return
      }
      await authStore.resetPassword({
        email: email.value.trim(),
        code: verificationCode.value.trim(),
        password: password.value
      })
      identity.value = email.value.trim()
      password.value = ''
      confirmPassword.value = ''
      verificationCode.value = ''
      // 重置密码不建立会话，回到登录模式让用户使用新密码登录。
      mode.value = 'login'
      notice.value = t('auth.success.passwordReset')
    }
    if (!wasResetMode) {
      router.push('/')
    }
  } catch (e) {
    const fallbackKey = wasResetMode
      ? 'auth.error.resetFailed'
      : isRegisterMode.value
        ? 'auth.error.registerFailed'
        : 'auth.error.loginFailed'
    error.value = getAuthErrorMessage(e, fallbackKey)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-view">
    <div class="login-card">
      <h1 class="login-title">{{ t('app.name') }}</h1>
      <p class="login-subtitle">
        {{ isLoginMode ? t('auth.subtitle.login') : isRegisterMode ? t('auth.subtitle.register') : t('auth.subtitle.reset') }}
      </p>
      <div v-if="!isResetMode" class="login-tabs">
        <button
          type="button"
          class="login-tab"
          :class="{ active: isLoginMode }"
          :disabled="loading || sendingCode"
          @click="switchMode('login')"
        >
          {{ t('auth.tabs.login') }}
        </button>
        <button
          type="button"
          class="login-tab"
          :class="{ active: !isLoginMode }"
          :disabled="loading || sendingCode"
          @click="switchMode('register')"
        >
          {{ t('auth.tabs.register') }}
        </button>
      </div>
      <form class="login-form" @submit.prevent="onSubmit">
        <template v-if="isLoginMode">
          <input
            v-model="identity"
            type="text"
            :placeholder="t('auth.placeholder.identity')"
            class="login-input"
            autocomplete="username"
            :disabled="loading"
          />
        </template>
        <template v-else>
          <input
            v-model="email"
            type="email"
            :placeholder="t('auth.placeholder.email')"
            class="login-input"
            autocomplete="email"
            :disabled="loading || sendingCode"
          />
          <div class="verification-row">
            <input
              v-model="verificationCode"
              type="text"
              :placeholder="t('auth.placeholder.verificationCode')"
              class="login-input verification-input"
              :disabled="loading"
            />
            <button
              type="button"
              class="verification-button"
              :disabled="loading || sendingCode || resendCountdown > 0"
              @click="onSendCode"
            >
              {{
                sendingCode
                  ? t('auth.sending')
                  : resendCountdown > 0
                    ? `${resendCountdown}s`
                    : t('auth.sendCode')
              }}
            </button>
          </div>
          <template v-if="isRegisterMode">
            <input
              v-model="username"
              type="text"
              :placeholder="t('auth.placeholder.username')"
              class="login-input"
              autocomplete="username"
              :disabled="loading"
            />
          </template>
        </template>
        <input
          v-if="isLoginMode || isRegisterMode"
          v-model="password"
          type="password"
          :placeholder="t('auth.placeholder.password')"
          class="login-input"
          :autocomplete="isLoginMode ? 'current-password' : 'new-password'"
          :disabled="loading"
        />
        <template v-if="isResetMode">
          <input
            v-model="password"
            type="password"
            :placeholder="t('auth.placeholder.newPassword')"
            class="login-input"
            autocomplete="new-password"
            :disabled="loading"
          />
          <input
            v-model="confirmPassword"
            type="password"
            :placeholder="t('auth.placeholder.confirmPassword')"
            class="login-input"
            autocomplete="new-password"
            :disabled="loading"
          />
        </template>
        <button v-if="isLoginMode" type="button" class="login-link" :disabled="loading" @click="switchMode('reset')">
          {{ t('auth.action.forgotPassword') }}
        </button>
        <button v-if="isResetMode" type="button" class="login-link" :disabled="loading || sendingCode" @click="switchMode('login')">
          {{ t('auth.action.backToLogin') }}
        </button>
        <p v-if="notice" class="login-notice">{{ notice }}</p>
        <p v-if="error" class="login-error">{{ error }}</p>
        <button type="submit" class="login-submit" :disabled="loading">
          {{
            loading
              ? isLoginMode ? t('auth.loading.login') : isRegisterMode ? t('auth.loading.register') : t('auth.loading.reset')
              : isLoginMode ? t('auth.action.signIn') : isRegisterMode ? t('auth.action.signUp') : t('auth.action.resetPassword')
          }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-view {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-primary);
}
.login-card {
  width: 100%;
  max-width: 320px;
  padding: 32px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-lg);
}
.login-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.login-subtitle {
  margin: 0 0 24px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.login-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.login-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.login-tab {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  background: var(--color-bg-secondary);
  color: var(--color-text-secondary);
}
.login-tab.active {
  border-color: var(--color-accent);
  color: var(--color-text-primary);
}
.login-input {
  width: 100%;
  padding: 10px 12px;
  background: var(--color-bg-secondary);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
  font-size: 14px;
}
.login-input::placeholder {
  color: var(--color-text-secondary);
}
.login-input:focus {
  outline: none;
  border-color: var(--color-accent);
}
.login-error {
  margin: 0;
  font-size: 13px;
  color: var(--color-danger);
}
.login-notice {
  margin: 0;
  font-size: 13px;
  color: var(--color-success);
}
.login-link {
  align-self: flex-start;
  min-height: 44px;
  padding: 8px 0;
  color: var(--color-accent);
  font-size: 13px;
  text-align: left;
}
.login-link:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.verification-row {
  display: flex;
  gap: 8px;
}
.verification-input {
  flex: 1;
}
.verification-button {
  white-space: nowrap;
  padding: 0 12px;
  border-radius: var(--border-radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-bg-secondary);
  color: var(--color-text-primary);
}
.login-submit {
  margin-top: 8px;
  padding: 10px 16px;
  background: var(--color-accent);
  color: white;
  border-radius: var(--border-radius-sm);
  font-size: 14px;
  font-weight: 500;
  transition: background var(--transition-fast);
}
.login-submit:hover:not(:disabled) {
  background: var(--color-accent-hover);
}
.login-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
