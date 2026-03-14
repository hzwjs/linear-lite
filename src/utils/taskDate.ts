export function formatDateInputValue(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function parseDateInputValue(value: string): number | undefined {
  if (!value) return undefined
  return new Date(`${value}T00:00:00`).getTime()
}

export function toApiDateTime(ms: number | undefined | null): string | undefined {
  if (ms == null) return undefined
  return `${formatDateInputValue(ms)}T00:00:00`
}
