import { marked } from 'marked'
import TurndownService from 'turndown'

const markdownTaskListPattern = /(^|\n)\s*[-*+]\s+\[(?: |x|X)\]\s+/m

export function looksLikeMarkdownTaskList(text: string): boolean {
  return markdownTaskListPattern.test((text ?? '').trim())
}

function isTaskCheckboxInput(node: Element | null): node is HTMLInputElement {
  return (
    node instanceof HTMLInputElement &&
    node.type === 'checkbox'
  )
}

function normalizeTaskListHtml(html: string): string {
  if (typeof DOMParser === 'undefined' || typeof document === 'undefined') return html

  const doc = new DOMParser().parseFromString(html, 'text/html')
  const lists = Array.from(doc.querySelectorAll('ul'))

  for (const list of lists) {
    const items = Array.from(list.children)
    if (items.length === 0) continue
    if (!items.every((item) => item instanceof HTMLLIElement)) continue

    const liItems = items as HTMLLIElement[]
    const isTaskList = liItems.every((item) => {
      const firstElement = item.firstElementChild
      return isTaskCheckboxInput(firstElement)
    })

    if (!isTaskList) continue

    list.setAttribute('data-type', 'taskList')

    for (const item of liItems) {
      const checkbox = item.firstElementChild as HTMLInputElement
      const checked = checkbox.checked
      checkbox.remove()

      item.setAttribute('data-type', 'taskItem')
      item.setAttribute('data-checked', checked ? 'true' : 'false')

      const label = doc.createElement('label')
      const renderedCheckbox = doc.createElement('input')
      renderedCheckbox.type = 'checkbox'
      if (checked) renderedCheckbox.setAttribute('checked', 'checked')
      const indicator = doc.createElement('span')
      label.append(renderedCheckbox, indicator)

      const content = doc.createElement('div')
      const paragraph = doc.createElement('p')

      while (item.firstChild) {
        paragraph.append(item.firstChild)
      }

      if (
        paragraph.childNodes.length === 0 ||
        (paragraph.textContent?.trim() === '' && !paragraph.querySelector('*'))
      ) {
        paragraph.textContent = ''
      }

      content.append(paragraph)
      item.append(label, content)
    }
  }

  return doc.body.innerHTML || '<p></p>'
}

/**
 * Markdown 转 HTML，供 Tiptap 解析。
 * 空、仅空白或解析异常时返回 '<p></p>'。
 */
export function mdToHtml(md: string): string {
  const trimmed = (md ?? '').trim()
  if (!trimmed) return '<p></p>'
  try {
    return normalizeTaskListHtml(marked.parse(trimmed) as string)
  } catch {
    return '<p></p>'
  }
}

const turndown = new TurndownService({
  codeBlockStyle: 'fenced',
  headingStyle: 'atx',
  bulletListMarker: '-',
})

turndown.addRule('tiptapTaskItem', {
  filter: (node) =>
    node.nodeName === 'LI' &&
    (node as HTMLElement).getAttribute('data-type') === 'taskItem',
  replacement: (content, node) => {
    const checked = (node as HTMLElement).getAttribute('data-checked') === 'true'
    const text = content.replace(/\n+/g, ' ').trim()
    return `\n- [${checked ? 'x' : ' '}] ${text}\n`
  },
})

turndown.addRule('tiptapMention', {
  filter: (node) =>
    node.nodeName === 'SPAN' && (node as HTMLElement).getAttribute('data-type') === 'mention',
  replacement: (_content, node) => {
    const el = node as HTMLElement
    const label = el.getAttribute('data-label') ?? el.textContent?.trim() ?? ''
    return label ? `@${label}` : ''
  },
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
