import DOMPurify from 'dompurify'
import { parseBlockNoteStoredBlocks } from './blockNoteDescription'

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

function inlineToHtml(node: InlineContent): string {
  if (node.type === 'mention') {
    return `<span class="bn-mention">@${esc(node.props.label ?? '')}</span>`
  }
  if (node.type === 'link') {
    const inner = node.content.map(inlineToHtml).join('')
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
  return content.map(inlineToHtml).join('')
}

function listItemHtml(block: Block): string {
  const liContent = inlinesToHtml(block.content)
  const liNested = block.children.length ? blocksToHtml(block.children) : ''
  return `<li>${liContent}${liNested}</li>`
}

function blocksToHtml(blocks: Block[]): string {
  let html = ''
  let i = 0

  while (i < blocks.length) {
    const block = blocks[i]!
    const type = block.type
    const content = inlinesToHtml(block.content)
    const nestedHtml = block.children.length ? blocksToHtml(block.children) : ''

    if (type === 'bulletListItem') {
      const items: string[] = []
      while (i < blocks.length && blocks[i]!.type === 'bulletListItem') {
        items.push(listItemHtml(blocks[i]!))
        i++
      }
      html += `<ul>${items.join('')}</ul>`
      continue
    }

    if (type === 'numberedListItem') {
      const items: string[] = []
      while (i < blocks.length && blocks[i]!.type === 'numberedListItem') {
        items.push(listItemHtml(blocks[i]!))
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
        const lang = esc(String(block.props.language ?? ''))
        html += `<pre><code class="language-${lang}">${content}</code></pre>`
        break
      }
      case 'quote':
        html += `<blockquote>${content}${nestedHtml}</blockquote>`
        break
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
  'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'blockquote', 'input', 'span',
]

const ALLOWED_ATTR = ['href', 'target', 'rel', 'class', 'type', 'disabled', 'checked']

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
    const inline = b.content
      .map((node) => {
        if (node.type === 'text') return node.text
        if (node.type === 'mention') return `@${node.props.label}`
        if (node.type === 'link') return node.content.map((n) => (n.type === 'text' ? n.text : '')).join('')
        return ''
      })
      .join('')
    const childText = b.children.map(extractText).join(' ')
    return [inline, childText].filter(Boolean).join(' ')
  }

  return (blocks as Block[]).map(extractText).filter(Boolean).join(' ').trim()
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
