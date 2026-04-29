const RELOAD_KEY = 'linear-lite:dynamic-import-reload-once'

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
  if (window.sessionStorage.getItem(RELOAD_KEY) === '1') return false
  window.sessionStorage.setItem(RELOAD_KEY, '1')
  window.location.reload()
  return true
}

export function clearDynamicImportRecoveryFlag() {
  if (typeof window === 'undefined') return
  window.sessionStorage.removeItem(RELOAD_KEY)
}
