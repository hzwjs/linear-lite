<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref<string | null>(null)
const loading = ref(false)

async function onSubmit() {
  error.value = null
  if (!username.value.trim() || !password.value) {
    error.value = 'Please enter username and password'
    return
  }
  loading.value = true
  try {
    await authStore.login({ username: username.value.trim(), password: password.value })
    router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Login failed'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-view">
    <div class="login-card">
      <h1 class="login-title">Linear Lite</h1>
      <p class="login-subtitle">Sign in to continue</p>
      <form class="login-form" @submit.prevent="onSubmit">
        <input
          v-model="username"
          type="text"
          placeholder="Username"
          class="login-input"
          autocomplete="username"
          :disabled="loading"
        />
        <input
          v-model="password"
          type="password"
          placeholder="Password"
          class="login-input"
          autocomplete="current-password"
          :disabled="loading"
        />
        <p v-if="error" class="login-error">{{ error }}</p>
        <button type="submit" class="login-submit" :disabled="loading">
          {{ loading ? 'Signing in...' : 'Sign in' }}
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
