import { marked, Renderer } from 'marked'
import { randomClientId } from '../utils/clientId'

export type PastedImageSource =
  | { kind: 'asset'; assetId: number }
  | { kind: 'file'; file: File }
  | { kind: 'url'; url: string }

export interface PastedImage {
  blockId: string
  caption: string
  source: PastedImageSource
  pathText: string
  previewUrl?: string
  content: any[]
}

const markerPrefix = 'https://linear-lite.invalid/pasted-image/'
const localImageLimit = 6 * 1024 * 1024

/** 用解析器识别图片节点，代码块中的图片语法保持原文。 */
export function prepareImagePaste(input: { html?: string; markdown?: string; filePath?: string }, files: File[]) {
  const sources = new Map<string, { source: PastedImageSource; caption: string; pathText: string }>()
  function marker(source: PastedImageSource, caption: string, pathText: string) {
    const href = markerPrefix + randomClientId()
    sources.set(href, { source, caption, pathText })
    const element = document.createElement('a')
    element.href = href
    element.textContent = caption.length > 0 ? caption : '\uFFFC'
    return element
  }
  let html: string
  if (input.html != null) {
    const parsed = new DOMParser().parseFromString(input.html, 'text/html')
    const roots = Array.from(parsed.querySelectorAll('[data-content-type="documentImage"][data-image-asset-id]'))
    for (const root of roots) {
      const caption = root.getAttribute('data-caption') ?? ''
      const assetId = Number(root.getAttribute('data-image-asset-id'))
      const image = root.querySelector('img[src]')
      const pathText = image == null ? `document-image:${assetId}` : image.getAttribute('src')!
      root.replaceWith(marker({ kind: 'asset', assetId }, caption, pathText))
    }
    for (const image of Array.from(parsed.querySelectorAll('img'))) {
      const assetId = image.getAttribute('data-image-asset-id')
      const pathText = image.getAttribute('src') ?? ''
      image.replaceWith(marker(assetId == null
        ? { kind: 'url', url: pathText }
        : { kind: 'asset', assetId: Number(assetId) }, image.getAttribute('alt') ?? '', pathText))
    }
    html = parsed.body.innerHTML
  } else if (input.markdown != null) {
    const renderer = new Renderer()
    renderer.image = ({ href, text }) => marker({ kind: 'url', url: href }, text, href).outerHTML
    html = marked.parse(input.markdown, { renderer, async: false })
  } else {
    html = files.map(file => {
      const paragraph = document.createElement('p')
      const pathText = files.length === 1 && input.filePath != null ? input.filePath : file.name
      paragraph.append(marker({ kind: 'file', file }, file.name, pathText))
      return paragraph.outerHTML
    }).join('')
  }
  // 剪贴板文件与图片节点有明确关联时采用文件数据；不按多个图片的顺序猜测对应关系。
  for (const entry of sources.values()) {
    if (entry.source.kind !== 'url') continue
    const path = entry.source.url.replace(/\\/g, '/')
    let name = path.slice(path.lastIndexOf('/') + 1)
    try { name = decodeURIComponent(name) } catch { /* 保留源名称。 */ }
    const matches = files.filter(file => file.type.startsWith('image/') && file.name === name)
    if (matches.length === 1) entry.source = { kind: 'file', file: matches[0]! }
    else if (sources.size === 1 && files.length === 1 && files[0]!.type.startsWith('image/')) {
      entry.source = { kind: 'file', file: files[0]! }
    }
  }
  return { html, sources }
}

/** 图片占位使用普通段落；临时 URL 和导入状态不会进入图片块的持久化字段。 */
export function materializeImagePaste(blocks: any[], prepared: ReturnType<typeof prepareImagePaste>, sourceLabel: string) {
  const images: PastedImage[] = []
  function placeholder(entry: { source: PastedImageSource; caption: string; pathText: string }) {
    const id = randomClientId()
    const caption = entry.caption.trim().length > 0 ? entry.caption.trim() : sourceLabel
    const source = entry.source
    const content = source.kind === 'url' && /^https?:\/\//i.test(source.url)
      ? [{ type: 'link', href: source.url, content: [{ type: 'text', text: caption, styles: {} }] }]
      : [{ type: 'text', text: caption, styles: {} }]
    const previewUrl = source.kind === 'url' && /^(https?:|data:image\/|blob:)/i.test(source.url)
      ? source.url : undefined
    images.push({ blockId: id, caption, source, pathText: entry.pathText, previewUrl, content })
    return { id, type: 'paragraph', content, children: [] }
  }
  function rewrite(block: any): any[] {
    const children = (block.children ?? []).flatMap(rewrite)
    if (Array.isArray(block.content) && block.type !== 'codeBlock') {
      const result: any[] = []
      let content: any[] = []
      const flush = () => {
        if (content.length) {
          result.push({ ...block, id: randomClientId(), content, children: [] })
          content = []
        }
      }
      for (const item of block.content) {
        const entry = item.type === 'link' ? prepared.sources.get(item.href) : undefined
        if (entry) { flush(); result.push(placeholder(entry)) }
        else content.push(item)
      }
      if (!result.length) return [{ ...block, children }]
      flush()
      result[result.length - 1].children = children
      return result
    }
    if (block.type === 'table') {
      const tableImages: any[] = []
      const content = structuredClone(block.content)
      // BlockNote 表格单元格不支持图片块；保留单元格说明，图片接在所属表格后。
      for (const row of content.rows) for (const cell of row.cells) {
        const items = Array.isArray(cell) ? cell : cell.content
        for (let i = 0; i < items.length; i++) {
          const entry = items[i]?.type === 'link' ? prepared.sources.get(items[i].href) : undefined
          if (entry) {
            const image = placeholder(entry)
            tableImages.push(image)
            items.splice(i, 1, ...image.content)
          }
        }
      }
      return [{ ...block, content, children }, ...tableImages]
    }
    return [{ ...block, children }]
  }
  return { blocks: blocks.flatMap(rewrite), images }
}

export async function readLocalPastedImage(source: PastedImageSource, signal: AbortSignal): Promise<File> {
  if (source.kind === 'file') {
    if (source.file.size === 0 || source.file.size > localImageLimit || !source.file.type.startsWith('image/')) {
      throw new Error('图片内容无效或超过 6 MB 限制')
    }
    return source.file
  }
  if (source.kind !== 'url' || source.url.trim().length === 0) throw new Error('图片路径为空')
  if (source.url.startsWith('data:') && source.url.length > localImageLimit * 1.5) {
    throw new Error('图片超过 6 MB 限制')
  }
  // 本地文件访问遵循浏览器权限；读取失败交给粘贴控制器写入原路径。
  const url = /^[a-z]:[\\/]/i.test(source.url)
    ? `file:///${source.url.replace(/\\/g, '/')}` : source.url
  const scheme = /^[a-z][a-z\d+.-]*:/i.exec(url)?.[0]
  if (scheme != null && !/^(https?:|file:|blob:|data:)$/i.test(scheme)) throw new Error('图片路径协议不支持')
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error('图片路径读取失败')
  const blob = await response.blob()
  if (!blob.type.startsWith('image/') || blob.size > localImageLimit || blob.size === 0) {
    throw new Error('图片内容无效或超过 6 MB 限制')
  }
  return new File([blob], '粘贴图片', { type: blob.type })
}
