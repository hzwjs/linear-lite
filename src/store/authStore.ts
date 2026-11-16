import { defineStore } from 'pinia'
import { ref, computed, onScopeDispose } from 'vue'
import { authApi } from '../services/api/auth'
import { AUTH_SESSION_CHANGED_EVENT, JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'
import type { User } from '../types/domain'
import type { RegisterRequest, ResetPasswordRequest } from '../services/api/types'

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

function tokenBelongsToUser(token: string, userId: number): boolean {
  try {
    if (!Number.isSafeInteger(userId) || userId <= 0) return false
    const parts = token.split('.')
    if (parts.length !== 3) return false
    const payload = parts[1]!.replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(payload)) as { sub?: unknown }
    return typeof claims.sub === 'string' && claims.sub === String(userId)
  } catch {
    return false
  }
}

export const useAuthStore = defineStore('authStore', () => {
  const jwtToken = ref<string | null>(localStorage.getItem(JWT_STORAGE_KEY))
  const currentUser = ref<Pick<User, 'id' | 'username'> | null>(getStoredUser())
  const sessionInvalidated = ref(false)
  let documentAssetSessionRequested = false

  const isLoggedIn = computed(() => !!jwtToken.value)

  function setSession(token: string, userId: number, username: string) {
    if (currentUser.value?.id !== userId || jwtToken.value !== token) {
      documentAssetSessionRequested = false
      window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
    }
    localStorage.setItem(JWT_STORAGE_KEY, token)
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: userId, username }))
    jwtToken.value = token
    currentUser.value = { id: userId, username }
    sessionInvalidated.value = false
    void ensureDocumentAssetSession()
  }

  function matchesStoredSession(): boolean {
    const storedToken = localStorage.getItem(JWT_STORAGE_KEY)
    const storedUser = getStoredUser()
    const token = jwtToken.value
    const user = currentUser.value
    if (token == null && user == null) return storedToken == null && storedUser == null
    return token != null && user != null && storedToken === token && storedUser?.id === user.id &&
      storedUser.username === user.username && tokenBelongsToUser(token, user.id)
  }

  function invalidateLocalSession() {
    if (sessionInvalidated.value && jwtToken.value == null && currentUser.value == null) return
    sessionInvalidated.value = true
    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
    currentUser.value = null
    jwtToken.value = null
    documentAssetSessionRequested = false
  }

  function getConsistentAccessToken(): string | null {
    if (!matchesStoredSession()) {
      invalidateLocalSession()
      return null
    }
    return sessionInvalidated.value ? null : jwtToken.value
  }

  function onStorage(event: StorageEvent) {
    if ((event.key === null || event.key === JWT_STORAGE_KEY || event.key === USER_STORAGE_KEY) && !matchesStoredSession()) {
      invalidateLocalSession()
    }
  }
  window.addEventListener('storage', onStorage)
  onScopeDispose(() => window.removeEventListener('storage', onStorage))
  /** 为已登录会话补发图片资源 Cookie；失败时允许下次重试。 */
  async function ensureDocumentAssetSession() {
    if (documentAssetSessionRequested || !jwtToken.value) return
    documentAssetSessionRequested = true
    try {
      await authApi.startDocumentAssetSession()
    } catch {
      documentAssetSessionRequested = false
    }
  }

  function clearSession() {
    const ownsStoredSession = matchesStoredSession()
    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
    jwtToken.value = null
    currentUser.value = null
    sessionInvalidated.value = !ownsStoredSession
    if (ownsStoredSession) {
      localStorage.removeItem(JWT_STORAGE_KEY)
      localStorage.removeItem(USER_STORAGE_KEY)
    }
    return ownsStoredSession
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

  async function sendPasswordResetCode(email: string) {
    return authApi.sendPasswordResetCode({ email })
  }

  async function resetPassword(payload: ResetPasswordRequest) {
    return authApi.resetPassword(payload)
  }

  function logout() {
    const ownsStoredSession = clearSession()
    documentAssetSessionRequested = false
    // 图片资源端点使用路径级 Cookie；本地登出后同步清理，避免下一用户复用旧会话。
    if (ownsStoredSession) void authApi.logout().catch(() => undefined)
  }

  return {
    jwtToken,
    currentUser,
    isLoggedIn,
    sessionInvalidated,
    getConsistentAccessToken,
    login,
    register,
    sendRegisterCode,
    sendPasswordResetCode,
    resetPassword,
    logout,
    setSession,
    clearSession,
    ensureDocumentAssetSession
  }
})
