/**
 * 用户在 BlockNote 代码块里有时会整段粘贴 ```mermaid ... ```，会导致 mermaid 解析失败。
 * 在渲染前去掉外层围栏，仅保留图语法正文。
 */
export function normalizeMermaidDiagramSource(raw: string): string {
  let s = raw.replace(/\r\n/g, '\n').trim()
  if (!s) return ''

  const wholeFence = /^```(?:mermaid|mmd)\s*\n([\s\S]*?)\n```\s*$/i
  const whole = s.match(wholeFence)
  if (whole?.[1] != null) return whole[1].trim()

  if (/^```(?:mermaid|mmd)\b/i.test(s)) {
    s = s.replace(/^```(?:mermaid|mmd)\s*\n?/i, '')
    s = s.replace(/\n```\s*$/,'')
    return s.trim()
  }

  return s
}
