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

/** 去掉尾部无持久化内容的块；勿单用 isBlockEmpty，否则 image 等会被误删。 */
function stripTrailingEmptyBlocks(blocks: RawBlock[]): RawBlock[] {
  let end = blocks.length
  while (end > 0 && !blockHasPersistableContent(blocks[end - 1]!)) end--
  // Always keep at least one block so the editor isn't completely empty
  return end === 0 ? blocks.slice(0, 1) : blocks.slice(0, end)
}

function blockHasPersistableContent(block: RawBlock): boolean {
  const type = typeof block.type === 'string' ? block.type : ''

  if (type === 'image' || type === 'video' || type === 'audio' || type === 'file') {
    const props = block.props as Record<string, unknown> | undefined
    const url = props?.url
    return typeof url === 'string' && url.trim().length > 0
  }

  if (type === 'horizontalRule') return true

  if (!isBlockEmpty(block)) return true

  const children = block.children
  if (Array.isArray(children) && children.length > 0) {
    return (children as RawBlock[]).some((c) => blockHasPersistableContent(c))
  }

  return false
}

/** Block[] 是否含可持久化内容（含仅 URL 的图片/媒体块），供保存前判空。 */
export function blockNoteDocHasPersistableContent(blocks: unknown[]): boolean {
  return (blocks as RawBlock[]).some((b) => blockHasPersistableContent(b))
}

/** 解析 Block[] JSON；去掉尾部无持久化内容的块。 */
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
