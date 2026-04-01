export function formatDateInputValue(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 本地日历「今天」的 YYYY-MM-DD，用作日期选择器默认值 */
export function todayDateInputValue(): string {
  return formatDateInputValue(Date.now())
}

export function parseDateInputValue(value: string): number | undefined {
  if (!value) return undefined
  return new Date(`${value}T00:00:00`).getTime()
}

export function toApiDateTime(ms: number | undefined | null): string | undefined {
  if (ms == null) return undefined
  return `${formatDateInputValue(ms)}T00:00:00`
}
