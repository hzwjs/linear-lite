import axios, { type AxiosInstance, isAxiosError } from 'axios'
import type { ApiResponse } from './types'
import { JWT_STORAGE_KEY } from './constants'

// 开发时 Vite 代理 /api -> 后端，生产可配 VITE_API_BASE_URL
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export const api: AxiosInstance = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' }
})

// 请求头附加 JWT（与 authStore 共用 localStorage key）
api.interceptors.request.use((config) => {
  const token = localStorage.getItem(JWT_STORAGE_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 401：清除 Token、触发登出、跳转登录页（与 authStore 共用 JWT_STORAGE_KEY）
api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem(JWT_STORAGE_KEY)
      const afterLogout = import('pinia').then(({ getActivePinia }) => {
        const pinia = getActivePinia()
        if (pinia) {
          return import('../../store/authStore').then(({ useAuthStore }) => {
            useAuthStore(pinia).logout()
          })
        }
        return Promise.resolve()
      })
      afterLogout.then(() => import('../../router')).then(({ default: router }) => {
        router.push('/login')
      })
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
