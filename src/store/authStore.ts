import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '../services/api/auth'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'
import type { User } from '../types/domain'
import type { RegisterRequest } from '../services/api/types'

function getStoredUser(): Pick<User, 'id' | 'username'> | null {
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY)
    if (!raw) return null
    const o = JSON.parse(raw) as { id: number; username: string }
    return o?.id != null && o?.username ? { id: o.id, username: o.username } : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('authStore', () => {
  const jwtToken = ref<string | null>(localStorage.getItem(JWT_STORAGE_KEY))
  const currentUser = ref<Pick<User, 'id' | 'username'> | null>(getStoredUser())

  const isLoggedIn = computed(() => !!jwtToken.value)

  function setSession(token: string, userId: number, username: string) {
    jwtToken.value = token
    currentUser.value = { id: userId, username }
    localStorage.setItem(JWT_STORAGE_KEY, token)
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: userId, username }))
  }

  function clearSession() {
    jwtToken.value = null
    currentUser.value = null
    localStorage.removeItem(JWT_STORAGE_KEY)
    localStorage.removeItem(USER_STORAGE_KEY)
  }

  async function login(credentials: { identity: string; password: string }) {
    const data = await authApi.login(credentials)
    setSession(data.token, data.userId, data.username)
    return data
  }

  async function register(payload: RegisterRequest) {
    const data = await authApi.register(payload)
    setSession(data.token, data.userId, data.username)
    return data
  }

  async function sendRegisterCode(email: string) {
    return authApi.sendRegisterCode({ email })
  }

  function logout() {
    clearSession()
  }

  return {
    jwtToken,
    currentUser,
    isLoggedIn,
    login,
    register,
    sendRegisterCode,
    logout,
    setSession,
    clearSession
  }
})
