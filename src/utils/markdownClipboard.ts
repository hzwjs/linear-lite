const markdownClipboardTypes = new Set(['text/plain', 'text/markdown'])
const markdownSyntax = [
  /^ {0,3}#{1,6}\s+\S/m,
  /```[\s\S]*```/m,
  /(?:^|\n)\s*[-*+]\s+\S[\s\S]*\n\s*[-*+]\s+/m,
  /(?:^|\n)\s*\d+\.\s+\S[\s\S]*\n\s*\d+\.\s+/m,
  /(?:^|\n)\s*>\s+\S/m,
  /\[[^\]]+\]\(https?:\/\/\S+\)/m,
  /\*\*\S[\s\S]*\S\*\*/m,
]

const asciiTableBorder = /^[\s┌├└┬┴┤┼┐┘─]+$/

function isAsciiTableBorder(line: string): boolean {
  return asciiTableBorder.test(line) && /[┌├└┬┴┤┼┐┘]/.test(line)
}

function splitAsciiTableRow(line: string): string[] | undefined {
  const trimmed = line.trim()
  if (!trimmed.startsWith('│') || !trimmed.endsWith('│')) return undefined
  return trimmed.slice(1, -1).split('│').map((cell) => cell.trim())
}

/** 将终端/LLM 常见的 Unicode 框线表格转换为 GFM，交给 BlockNote 生成原生表格块。 */
export function asciiTableToMarkdown(text: string): string | undefined {
  const rows: string[][] = []
  let currentRow: string[] | undefined
  let borderCount = 0

  for (const line of text.split(/\r?\n/)) {
    if (isAsciiTableBorder(line)) {
      borderCount += 1
      if (currentRow) rows.push(currentRow)
      currentRow = undefined
      continue
    }
    const cells = splitAsciiTableRow(line)
    if (!cells || cells.length < 2) continue
    if (!currentRow) {
      currentRow = cells
      continue
    }
    if (currentRow.length !== cells.length) return undefined
    currentRow = currentRow.map((cell, index) => {
      const nextCell = cells[index] ?? ''
      return [cell, nextCell].filter((value) => value.length > 0).join(' ')
    })
  }
  if (currentRow) rows.push(currentRow)

  if (borderCount < 2 || rows.length < 2) return undefined
  const columnCount = rows[0]?.length ?? 0
  if (columnCount < 2 || rows.some((row) => row.length !== columnCount)) return undefined

  const escapeCell = (cell: string) => cell.replace(/\|/g, '\\|').replace(/\n/g, ' ')
  const header = `| ${rows[0]!.map(escapeCell).join(' | ')} |`
  const divider = `| ${rows[0]!.map(() => '---').join(' | ')} |`
  const body = rows.slice(1).map((row) => `| ${row.map(escapeCell).join(' | ')} |`)
  return [header, divider, ...body].join('\n')
}

function splitPipeTableRow(line: string): string[] | undefined {
  const trimmed = line.trim()
  if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return undefined
  const cells = trimmed.slice(1, -1).split('|').map((cell) => cell.trim())
  return cells.length >= 2 ? cells : undefined
}

function isGfmTable(text: string): boolean {
  const lines = text.split(/\r?\n/)
  for (let index = 0; index < lines.length - 2; index += 1) {
    const header = splitPipeTableRow(lines[index] ?? '')
    const divider = splitPipeTableRow(lines[index + 1] ?? '')
    const data = splitPipeTableRow(lines[index + 2] ?? '')
    if (!header || !divider || !data || header.length !== divider.length) continue
    if (divider.every((cell) => /^:?-{3,}:?$/.test(cell))) return true
  }
  return false
}

/** 原始 Markdown 剪贴板不应被 BlockNote 当成普通文本插入。 */
export function shouldPasteClipboardAsMarkdown(types: readonly string[], text: string): boolean {
  if (!text.trim()) return false
  if (!types.some((type) => markdownClipboardTypes.has(type))) return false
  if (types.includes('text/markdown')) return true
  if (!types.includes('blocknote/html') && !types.includes('text/html')) return true
  return isGfmTable(text) || markdownSyntax.some((pattern) => pattern.test(text))
}
