import { marked } from 'marked'
import TurndownService from 'turndown'

/**
 * Markdown 转 HTML，供 Tiptap 解析。
 * 空或仅空白时返回 '<p></p>'。
 */
export function mdToHtml(md: string): string {
  const trimmed = (md ?? '').trim()
  if (!trimmed) return '<p></p>'
  return marked.parse(trimmed) as string
}

const turndown = new TurndownService({
  codeBlockStyle: 'fenced',
  headingStyle: 'atx',
  bulletListMarker: '-',
})

/**
 * HTML 转 Markdown。
 * 空或仅 '<p></p>' 时返回 ''。
 */
export function htmlToMd(html: string): string {
  const trimmed = (html ?? '').trim()
  if (!trimmed || trimmed === '<p></p>') return ''
  return turndown.turndown(trimmed)
}
