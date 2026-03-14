import { marked } from 'marked'
import DOMPurify from 'dompurify'

const ALLOWED_TAGS = ['p', 'br', 'strong', 'em', 'u', 's', 'a', 'code', 'pre', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'blockquote', 'hr']

/**
 * 将 Markdown 转为安全 HTML，用于描述等富文本展示。
 */
export function renderMarkdown(md: string): string {
  if (!md || !md.trim()) return ''
  const raw = marked.parse(md.trim()) as string
  return DOMPurify.sanitize(raw, { ALLOWED_TAGS })
}
