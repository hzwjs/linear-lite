/**
 * 判断 `raw` 是否为 BlockNote 持久化的 Block[] JSON（而非以 `[` 开头的 Markdown）。
 */
export function parseBlockNoteStoredBlocks(raw: string): unknown[] | undefined {
  const t = raw.trim()
  if (!t.startsWith('[')) return undefined
  let parsed: unknown
  try {
    parsed = JSON.parse(t)
  } catch {
    return undefined
  }
  if (!Array.isArray(parsed)) return undefined
  if (parsed.length === 0) return parsed
  for (const item of parsed) {
    if (typeof item !== 'object' || item === null) return undefined
    const o = item as Record<string, unknown>
    if (typeof o.id !== 'string' || typeof o.type !== 'string') return undefined
  }
  return parsed
}
