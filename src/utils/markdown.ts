import { marked, type Tokens } from 'marked'
import DOMPurify from 'dompurify'
import { normalizeMermaidDiagramSource } from './mermaidSource'

function escMermaidSource(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

marked.use({
  renderer: {
    code({ text, lang }: Tokens.Code): string | false {
      const l = (lang ?? '').trim().toLowerCase()
      if (l === 'mermaid') {
        const body = normalizeMermaidDiagramSource(text.replace(/\n$/, ''))
        return `<div class="mermaid">${escMermaidSource(body)}</div>\n`
      }
      return false
    },
  },
})

const ALLOWED_TAGS = [
  'p',
  'br',
  'strong',
  'em',
  'u',
  's',
  'a',
  'code',
  'pre',
  'ul',
  'ol',
  'li',
  'h1',
  'h2',
  'h3',
  'blockquote',
  'hr',
  /** Turndown / 评论中的 `![](url)` */
  'img',
  /** ```mermaid``` 经自定义 renderer 输出，供 mermaid.run 消费 */
  'div',
]

const ALLOWED_ATTR = ['href', 'title', 'src', 'alt', 'class', 'target', 'rel']

/**
 * 将 Markdown 转为安全 HTML，用于描述等富文本展示。
 */
export function renderMarkdown(md: string): string {
  if (!md || !md.trim()) return ''
  const raw = marked.parse(md.trim()) as string
  return DOMPurify.sanitize(raw, { ALLOWED_TAGS, ALLOWED_ATTR })
}
