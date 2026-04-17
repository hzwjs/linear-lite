type RawBlock = Record<string, unknown>

function isBlockEmpty(block: RawBlock): boolean {
  const content = block.content
  if (!Array.isArray(content) || content.length === 0) return true
  // Single empty text node counts as empty
  if (content.length === 1) {
    const node = content[0] as Record<string, unknown>
    if (node.type === 'text' && (node.text === '' || node.text == null)) return true
  }
  return false
}

function stripTrailingEmptyBlocks(blocks: RawBlock[]): RawBlock[] {
  let end = blocks.length
  while (end > 0 && isBlockEmpty(blocks[end - 1])) end--
  // Always keep at least one block so the editor isn't completely empty
  return end === 0 ? blocks.slice(0, 1) : blocks.slice(0, end)
}

/**
 * 判断 `raw` 是否为 BlockNote 持久化的 Block[] JSON（而非以 `[` 开头的 Markdown）。
 * 同时去掉末尾连续的空块（如空 heading），避免加载后显示多余的占位行。
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
  return stripTrailingEmptyBlocks(parsed as RawBlock[])
}
