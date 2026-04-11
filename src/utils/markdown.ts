import { marked } from 'marked'
import DOMPurify from 'dompurify'

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
  /** 评论/编辑器经 Turndown 存成 `![](url)`，marked 会生成 img；须放行否则评论内图片被整标签剔除 */
  'img',
]

/**
 * 将 Markdown 转为安全 HTML，用于描述等富文本展示。
 */
export function renderMarkdown(md: string): string {
  if (!md || !md.trim()) return ''
  const raw = marked.parse(md.trim()) as string
  return DOMPurify.sanitize(raw, { ALLOWED_TAGS })
}
