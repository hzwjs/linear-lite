const forbidden = /(authorization|cookie|token|api[_-]?key|\/Users\/|\/home\/|[A-Za-z]:\\)/i
export function sanitizeEvent(type: string, input: unknown): string | null {
  const text = JSON.stringify(input)
  if (text.length > 8192 || forbidden.test(text)) return null
  return text
}
