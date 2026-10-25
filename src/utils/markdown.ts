import { marked, type Tokens } from 'marked'
import DOMPurify from 'dompurify'
import { normalizeMermaidDiagramSource } from './mermaidSource'
import type { CommentImageDto } from '../types/comment'

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
  'h4',
  'h5',
  'h6',
  'blockquote',
  'hr',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
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

/** Renders only image refs bound to this comment; raw remote image destinations stay hidden. */
export function renderTaskCommentMarkdown(md: string, commentId: number, images: CommentImageDto[]): string {
  if (!md || !md.trim()) return ''
  const byRef = new Map(images.map((image) => [image.ref, image]))
  const renderer = new marked.Renderer()
  renderer.image = ({ href, title, text }: Tokens.Image): string => {
    if (!href?.startsWith('mcp-image:')) return ''
    const image = byRef.get(href.slice('mcp-image:'.length))
    return image ? taskCommentImageHtml(commentId, image.id, text ?? '', title) : ''
  }
  const raw = marked.parse(md.trim(), { renderer }) as string
  return DOMPurify.sanitize(raw, { ALLOWED_TAGS, ALLOWED_ATTR })
}

export function taskCommentImageHtml(commentId: number, imageId: number, altText: string, title?: string | null): string {
  const escape = (value: string) => value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
  const safeTitle = title ? ` title="${escape(title)}"` : ''
  return `<img src="/api/document-assets/task-comments/${commentId}/${imageId}" alt="${escape(altText)}"${safeTitle}>`
}
