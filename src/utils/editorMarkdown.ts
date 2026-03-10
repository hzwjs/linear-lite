import { marked } from 'marked'
import TurndownService from 'turndown'

/**
 * Markdown 转 HTML，供 Tiptap 解析。
 * 空、仅空白或解析异常时返回 '<p></p>'。
 */
export function mdToHtml(md: string): string {
  const trimmed = (md ?? '').trim()
  if (!trimmed) return '<p></p>'
  try {
    return marked.parse(trimmed) as string
  } catch {
    return '<p></p>'
  }
}

const turndown = new TurndownService({
  codeBlockStyle: 'fenced',
  headingStyle: 'atx',
  bulletListMarker: '-',
})

/**
 * 从 HTML 提取纯文本，作为单段 Markdown 回退（序列化失败时用）。
 */
function htmlToPlainText(html: string): string {
  if (typeof document === 'undefined') return ''
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const text = doc.documentElement.textContent ?? ''
  return text.replace(/\s+/g, ' ').trim()
}

/**
 * HTML 转 Markdown。
 * 空或仅 '<p></p>' 时返回 ''。异常时回退为纯文本，避免静默丢内容。
 */
export function htmlToMd(html: string): string {
  const trimmed = (html ?? '').trim()
  if (!trimmed || trimmed === '<p></p>') return ''
  try {
    return turndown.turndown(trimmed)
  } catch {
    return htmlToPlainText(trimmed)
  }
}
