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

/** 原始 Markdown 剪贴板不应被 BlockNote 当成普通文本插入。 */
export function shouldPasteClipboardAsMarkdown(types: readonly string[], text: string): boolean {
  if (!text.trim()) return false
  if (!types.some((type) => markdownClipboardTypes.has(type))) return false
  if (types.includes('text/markdown')) return true
  if (!types.includes('blocknote/html') && !types.includes('text/html')) return true
  return markdownSyntax.some((pattern) => pattern.test(text))
}
