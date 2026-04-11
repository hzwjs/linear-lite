<script setup lang="ts">
import { computed, ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore'

const router = useRouter()
const authStore = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const identity = ref('')
const email = ref('')
const verificationCode = ref('')
const username = ref('')
const password = ref('')
const error = ref<string | null>(null)
const loading = ref(false)
const sendingCode = ref(false)
const resendCountdown = ref(0)
let resendTimer: number | null = null

const { t } = useI18n()
const isLoginMode = computed(() => mode.value === 'login')

function resetError() {
  error.value = null
}

function switchMode(nextMode: 'login' | 'register') {
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
    await authStore.sendRegisterCode(email.value.trim())
    startResendCountdown()
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('auth.error.sendCodeFailed')
  } finally {
    sendingCode.value = false
  }
}

async function onSubmit() {
  resetError()
  loading.value = true
  try {
    if (isLoginMode.value) {
      if (!identity.value.trim() || !password.value) {
        error.value = t('auth.error.enterCredentials')
        return
      }
      await authStore.login({ identity: identity.value.trim(), password: password.value })
    } else {
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
    }
    router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('auth.error.authFailed')
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
        {{ isLoginMode ? t('auth.subtitle.login') : t('auth.subtitle.register') }}
      </p>
      <div class="login-tabs">
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
        <input
          v-if="isLoginMode"
          v-model="identity"
          type="text"
          :placeholder="t('auth.placeholder.identity')"
          class="login-input"
          autocomplete="username"
          :disabled="loading"
        />
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
          <input
            v-model="username"
            type="text"
            :placeholder="t('auth.placeholder.username')"
            class="login-input"
            autocomplete="username"
            :disabled="loading"
          />
        </template>
        <input
          v-model="password"
          type="password"
          :placeholder="t('auth.placeholder.password')"
          class="login-input"
          autocomplete="current-password"
          :disabled="loading"
        />
        <p v-if="error" class="login-error">{{ error }}</p>
        <button type="submit" class="login-submit" :disabled="loading">
          {{
            loading
              ? isLoginMode ? t('auth.loading.login') : t('auth.loading.register')
              : isLoginMode ? t('auth.action.signIn') : t('auth.action.signUp')
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
