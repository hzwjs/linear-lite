import axios, { CanceledError, type AxiosInstance, type AxiosRequestHeaders, isAxiosError } from 'axios'
import { getActivePinia } from 'pinia'
import { useAuthStore } from '../../store/authStore'
import type { ApiResponse } from './types'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from './constants'

// 开发时 Vite 代理 /api -> 后端，生产可配 VITE_API_BASE_URL
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export const api: AxiosInstance = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' }
})

const publicAuthPaths = new Set([
  '/auth/login', '/auth/register', '/auth/register/send-code',
  '/auth/password-reset/send-code', '/auth/password-reset', '/auth/logout'
])

// 请求使用当前页面的会话；共享存储发生变化时，禁止携带另一账号的令牌。
api.interceptors.request.use((config) => {
  if (publicAuthPaths.has(config.url ?? '')) {
    config.headers.delete('Authorization')
    return config
  }
  const pinia = getActivePinia()
  if (pinia == null) {
    if (localStorage.getItem(JWT_STORAGE_KEY) != null || localStorage.getItem(USER_STORAGE_KEY) != null) {
      throw new CanceledError('Authentication session is unavailable')
    }
    config.headers.delete('Authorization')
    return config
  }
  const auth = useAuthStore(pinia)
  const token = auth.getConsistentAccessToken()
  if (auth.sessionInvalidated) throw new CanceledError('Authentication session changed')
  if (token) config.headers.Authorization = `Bearer ${token}`
  else config.headers.delete('Authorization')
  return config
}, (error) => { throw error }, { synchronous: true })

function sentBearerToken(headers: AxiosRequestHeaders | undefined): string | null {
  const authorization = headers?.get('Authorization')
  return typeof authorization === 'string' && authorization.startsWith('Bearer ')
    ? authorization.slice('Bearer '.length) : null
}

// 旧请求的响应不得进入新账号的会话。
api.interceptors.response.use(
  (res) => {
    const sentToken = sentBearerToken(res.config.headers)
    if (sentToken != null) {
      const pinia = getActivePinia()
      const auth = pinia == null ? null : useAuthStore(pinia)
      if (auth?.getConsistentAccessToken() !== sentToken) throw new CanceledError('Authentication session changed')
    }
    return res
  },
  (err) => {
    if (err.response?.status === 401) {
      const sentToken = sentBearerToken(err.config?.headers)
      const pinia = getActivePinia()
      const auth = pinia == null ? null : useAuthStore(pinia)
      if (sentToken != null && auth?.getConsistentAccessToken() === sentToken) {
        auth.logout()
        void import('../../router').then(({ default: router }) => {
          const current = router.currentRoute.value
          if (current.name !== 'login') {
            router.push({ path: '/login', query: { redirect: current.fullPath } })
          }
        })
      }
    }
    return Promise.reject(err)
  }
)

/** 解包后端 ApiResponse.data */
export function unwrap<T>(res: { data: ApiResponse<T> }): T {
  const body = res.data
  if (body.code !== 200) {
    throw new Error(body.message ?? 'Request failed')
  }
  return body.data
}

/** 将 Axios 4xx/5xx 响应中的 ApiResponse.message 转为 Error，便于 alert 展示后端文案 */
export function toApiError(e: unknown): Error {
  if (isAxiosError(e) && e.code === 'ECONNABORTED') {
    return new Error('请求超时，请检查网络后重试')
  }
  if (isAxiosError(e) && e.response?.data != null && typeof e.response.data === 'object') {
    const msg = (e.response.data as { message?: string }).message
    if (typeof msg === 'string' && msg.length > 0) {
      return new Error(msg)
    }
  }
  if (e instanceof Error) return e
  return new Error(String(e))
}

export { JWT_STORAGE_KEY }
export * from './types'
