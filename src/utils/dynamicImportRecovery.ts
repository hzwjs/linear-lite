const RELOAD_KEY_PREFIX = 'linear-lite:dynamic-import-reload-once:'

function errorText(error: unknown): string {
  if (error instanceof Error) return error.message
  return String(error ?? '')
}

export function isDynamicImportFailure(error: unknown): boolean {
  const text = errorText(error)
  return (
    text.includes('Failed to fetch dynamically imported module') ||
    text.includes('Importing a module script failed') ||
    text.includes('Outdated Optimize Dep') ||
    text.includes('ERR_ABORTED 504')
  )
}

export function tryRecoverDynamicImport(error: unknown): boolean {
  if (!isDynamicImportFailure(error)) return false
  if (typeof window === 'undefined') return false
  // 按失败模块 URL 分桶：开发服务器重启后 URL 的时间戳会改变，应允许再次恢复；
  // 同一个持续失败模块仍只重载一次，避免死循环。
  const reloadKey = RELOAD_KEY_PREFIX + encodeURIComponent(errorText(error)).slice(0, 512)
  if (window.sessionStorage.getItem(reloadKey) === '1') return false
  window.sessionStorage.setItem(reloadKey, '1')
  window.location.reload()
  return true
}

export function clearDynamicImportRecoveryFlag() {
  if (typeof window === 'undefined') return
  for (let index = window.sessionStorage.length - 1; index >= 0; index--) {
    const key = window.sessionStorage.key(index)
    if (key?.startsWith(RELOAD_KEY_PREFIX)) window.sessionStorage.removeItem(key)
  }
}
