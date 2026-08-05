import DOMPurify from 'dompurify'
import { parseBlockNoteStoredBlocks } from './blockNoteDescription'
import { normalizeMermaidDiagramSource } from './mermaidSource'

// ─── Types (minimal subset of BlockNote's block shape) ───────────────────────

type InlineStyle = {
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strike?: boolean
  code?: boolean
}

type InlineContent =
  | { type: 'text'; text: string; styles: InlineStyle }
  | { type: 'mention'; props: { label: string } }
  | { type: 'link'; href: string; content: InlineContent[] }

type Block = {
  id: string
  type: string
  props: Record<string, unknown>
  content: InlineContent[]
  children: Block[]
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function asArr<T>(v: T[] | null | undefined): T[] {
  return Array.isArray(v) ? v : []
}

function inlineToHtml(node: InlineContent): string {
  if (node.type === 'mention') {
    return `<span class="bn-mention">@${esc(node.props.label ?? '')}</span>`
  }
  if (node.type === 'link') {
    const inner = asArr(node.content).map(inlineToHtml).join('')
    return `<a href="${esc(node.href)}" target="_blank" rel="noopener noreferrer">${inner}</a>`
  }
  // text node
  let t = esc(node.text ?? '')
  if (!t) return ''
  const s = node.styles ?? {}
  if (s.code) t = `<code>${t}</code>`
  if (s.bold) t = `<strong>${t}</strong>`
  if (s.italic) t = `<em>${t}</em>`
  if (s.underline) t = `<u>${t}</u>`
  if (s.strike) t = `<s>${t}</s>`
  return t
}

function inlinesToHtml(content: InlineContent[]): string {
  return asArr(content).map(inlineToHtml).join('')
}

function inlinesToPlainText(content: InlineContent[]): string {
  return asArr(content)
    .map((node) => {
      if (node.type === 'mention') return `@${node.props.label ?? ''}`
      if (node.type === 'link') return inlinesToPlainText(asArr(node.content))
      if (node.type === 'text') return node.text ?? ''
      return ''
    })
    .join('')
}

function listItemHtml(block: Block): string {
  const liContent = inlinesToHtml(block.content)
  const children = asArr(block.children)
  const liNested = children.length ? blocksToHtml(children) : ''
  return `<li>${liContent}${liNested}</li>`
}

function blocksToHtml(blocks: Block[]): string {
  let html = ''
  let i = 0
  const list = asArr(blocks)

  while (i < list.length) {
    const block = list[i]!
    const type = block.type
    const content = inlinesToHtml(block.content)
    const childList = asArr(block.children)
    const nestedHtml = childList.length ? blocksToHtml(childList) : ''

    if (type === 'bulletListItem') {
      const items: string[] = []
      while (i < list.length && list[i]!.type === 'bulletListItem') {
        items.push(listItemHtml(list[i]!))
        i++
      }
      html += `<ul>${items.join('')}</ul>`
      continue
    }

    if (type === 'numberedListItem') {
      const items: string[] = []
      while (i < list.length && list[i]!.type === 'numberedListItem') {
        items.push(listItemHtml(list[i]!))
        i++
      }
      html += `<ol>${items.join('')}</ol>`
      continue
    }

    switch (type) {
      case 'paragraph':
        html += `<p>${content}${nestedHtml}</p>`
        break
      case 'heading': {
        const lvl = Math.min(Math.max(Number(block.props.level ?? 1), 1), 3)
        html += `<h${lvl}>${content}</h${lvl}>${nestedHtml}`
        break
      }
      case 'checkListItem': {
        const checked = block.props.checked ? ' checked' : ''
        html += `<p><input type="checkbox" disabled${checked}> ${content}${nestedHtml}</p>`
        break
      }
      case 'codeBlock': {
        const langRaw = String(block.props.language ?? '').trim().toLowerCase()
        if (langRaw === 'mermaid') {
          const src = normalizeMermaidDiagramSource(inlinesToPlainText(block.content))
          html += `<div class="mermaid">${esc(src)}</div>`
        } else {
          const lang = esc(String(block.props.language ?? ''))
          html += `<pre><code class="language-${lang}">${content}</code></pre>`
        }
        break
      }
      case 'quote':
        html += `<blockquote>${content}${nestedHtml}</blockquote>`
        break
      case 'image': {
        const imageUrl = block.props.url
        if (typeof imageUrl === 'string' && imageUrl.trim()) {
          const caption = typeof block.props.caption === 'string' ? block.props.caption : ''
          // 评论正文持久化为 BlockNote JSON；图片块必须在回显转换中保留，提交后才能继续显示。
          html += `<img src="${esc(imageUrl)}" alt="${esc(caption)}">`
        }
        break
      }
      default:
        if (content) html += `<p>${content}</p>`
        if (nestedHtml) html += nestedHtml
    }

    i++
  }

  return html
}

// ─── Public API ──────────────────────────────────────────────────────────────

const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'em', 'u', 's', 'a', 'code', 'pre',
  'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'blockquote', 'input', 'span', 'div',
  'img',
]

const ALLOWED_ATTR = ['href', 'target', 'rel', 'class', 'type', 'disabled', 'checked', 'src', 'alt']

/**
 * Extracts plain text from a body string that may be BlockNote JSON or legacy Markdown.
 * Used for notification previews and other plain-text contexts.
 */
export function bodyToPlainText(body: string): string {
  if (!body?.trim()) return ''
  const blocks = parseBlockNoteStoredBlocks(body.trim())
  if (blocks === undefined) {
    // Legacy Markdown: strip common syntax characters
    return body.replace(/[#*_`>\-\[\]!]/g, '').replace(/\s+/g, ' ').trim()
  }

  function extractText(b: Block): string {
    const inline = asArr(b.content)
      .map((node) => {
        if (node.type === 'text') return node.text
        if (node.type === 'mention') return `@${node.props.label}`
        if (node.type === 'link') return asArr(node.content).map((n) => (n.type === 'text' ? n.text : '')).join('')
        return ''
      })
      .join('')
    const childText = asArr(b.children).map(extractText).join(' ')
    return [inline, childText].filter(Boolean).join(' ')
  }

  return asArr(blocks as Block[]).map(extractText).filter(Boolean).join(' ').trim()
}

/**
 * Renders a body string that may be either BlockNote JSON or legacy Markdown.
 * Returns safe HTML suitable for v-html.
 */
export function renderBody(body: string, renderMarkdown: (s: string) => string): string {
  if (!body?.trim()) return ''

  const blocks = parseBlockNoteStoredBlocks(body.trim())
  if (blocks === undefined) {
    return renderMarkdown(body)
  }

  const raw = blocksToHtml(blocks as Block[])
  return DOMPurify.sanitize(raw, { ALLOWED_TAGS, ALLOWED_ATTR })
}

function collectMermaidSourcesFromBlocks(blocks: Block[], out: string[]) {
  for (const b of asArr(blocks)) {
    if (b.type === 'codeBlock' && String(b.props.language ?? '').trim().toLowerCase() === 'mermaid') {
      const normalized = normalizeMermaidDiagramSource(inlinesToPlainText(b.content))
      if (normalized) out.push(normalized)
    }
    collectMermaidSourcesFromBlocks(asArr(b.children), out)
  }
}

function mermaidFencesFromLegacyMarkdown(md: string): string[] {
  const re = /```mermaid\s*\n([\s\S]*?)```/gi
  const out: string[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(md)) !== null) {
    const inner = normalizeMermaidDiagramSource(m[1] ?? '')
    if (inner) out.push(inner)
  }
  return out
}

/**
 * 从任务描述（BlockNote JSON 或旧 Markdown）中抽出 Mermaid 图，生成占位 HTML，供预览区 v-html + mermaid.run 使用。
 */
export function mermaidPreviewHtmlFromDescriptionBody(body: string): string {
  if (!body?.trim()) return ''
  const trimmed = body.trim()
  const blocks = parseBlockNoteStoredBlocks(trimmed)
  if (blocks === undefined) {
    return mermaidFencesFromLegacyMarkdown(trimmed).map((s) => `<div class="mermaid">${esc(s)}</div>`).join('')
  }
  const sources: string[] = []
  collectMermaidSourcesFromBlocks(blocks as Block[], sources)
  return sources.map((s) => `<div class="mermaid">${esc(s)}</div>`).join('')
}
