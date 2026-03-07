const VIEW_PREF_KEY = 'linear-lite-view'

export type ViewType = 'board' | 'list'

export function getStoredView(): ViewType {
  try {
    const v = localStorage.getItem(VIEW_PREF_KEY)
    if (v === 'board' || v === 'list') return v
  } catch (_) {}
  return 'board'
}

export function setStoredView(view: ViewType) {
  try {
    localStorage.setItem(VIEW_PREF_KEY, view)
  } catch (_) {}
}
