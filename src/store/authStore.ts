import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '../services/api/auth'
import { JWT_STORAGE_KEY } from '../services/api/constants'
import type { User } from '../types/domain'

export const useAuthStore = defineStore('authStore', () => {
  const jwtToken = ref<string | null>(localStorage.getItem(JWT_STORAGE_KEY))
  const currentUser = ref<Pick<User, 'id' | 'username'> | null>(null)

  const isLoggedIn = computed(() => !!jwtToken.value)

  function setSession(token: string, userId: number, username: string) {
    jwtToken.value = token
    currentUser.value = { id: userId, username }
    localStorage.setItem(JWT_STORAGE_KEY, token)
  }

  function clearSession() {
    jwtToken.value = null
    currentUser.value = null
    localStorage.removeItem(JWT_STORAGE_KEY)
  }

  async function login(credentials: { username: string; password: string }) {
    const data = await authApi.login(credentials)
    setSession(data.token, data.userId, data.username)
    return data
  }

  function logout() {
    clearSession()
  }

  return {
    jwtToken,
    currentUser,
    isLoggedIn,
    login,
    logout,
    setSession,
    clearSession
  }
})
