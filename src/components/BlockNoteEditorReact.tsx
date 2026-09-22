/** @jsxImportSource react */
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import '@blocknote/mantine/style.css'
import PhotoSwipe from 'photoswipe'
import { BlockNoteView } from '@blocknote/mantine'
import {
  SuggestionMenuController,
  createReactBlockSpec,
  createReactInlineContentSpec,
  useCreateBlockNote,
  type DefaultReactSuggestionItem,
  type SuggestionMenuProps,
} from '@blocknote/react'
import { getDefaultReactSlashMenuItems } from '@blocknote/react'
import {
  BlockNoteSchema,
  defaultBlockSpecs,
  defaultInlineContentSpecs,
  filterSuggestionItems,
  type PartialBlock,
} from '@blocknote/core'
import { createCodeBlockSpec } from '@blocknote/core/blocks'
import type { HighlighterGeneric, LanguageInput } from '@shikijs/types'
import { parseBlockNoteStoredBlocks } from '../utils/blockNoteDescription'
import { asciiTableToMarkdown, shouldPasteClipboardAsMarkdown } from '../utils/markdownClipboard'
import { normalizeMermaidRenderError, renderMermaidSvg } from '../utils/mermaidRenderer'
import type { DocumentImageAsset } from '../types/document'
import {
  clonePastedDocumentImageAssets,
  createDocumentImageUploadResult,
  formatUploadFeedback,
  hasDocumentImageAssetPaste,
  hasExternalImagePaste,
  hasMarkdownImagePaste,
  isPastedImageFile,
  type DocumentImageUploadResult,
} from './documentImageEditorUtils'
import { MentionMemberSuggestionMenu } from './MentionMemberSuggestionMenu'
import {
  StructuredMentionSuggestionMenu,
  type StructuredSuggestionItem,
} from './StructuredMentionSuggestionMenu'

// ─── Code block with language selector ─────────────────────────────────────────

type CodeBlockLanguageLoader = () => Promise<{ default: LanguageInput }>

const supportedCodeBlockLanguages = {
  text:       { name: 'Plain Text' },
  javascript: { name: 'JavaScript', aliases: ['js'] },
  typescript: { name: 'TypeScript', aliases: ['ts'] },
  python:     { name: 'Python', aliases: ['py'] },
  java:       { name: 'Java' },
  go:         { name: 'Go', aliases: ['golang'] },
  rust:       { name: 'Rust' },
  cpp:        { name: 'C++', aliases: ['c++'] },
  c:          { name: 'C' },
  csharp:     { name: 'C#', aliases: ['cs'] },
  php:        { name: 'PHP' },
  ruby:       { name: 'Ruby', aliases: ['rb'] },
  swift:      { name: 'Swift' },
  kotlin:     { name: 'Kotlin' },
  html:       { name: 'HTML' },
  css:        { name: 'CSS' },
  scss:       { name: 'SCSS' },
  sql:        { name: 'SQL' },
  json:       { name: 'JSON' },
  yaml:       { name: 'YAML', aliases: ['yml'] },
  xml:        { name: 'XML' },
  bash:       { name: 'Bash', aliases: ['sh', 'shell'] },
  markdown:   { name: 'Markdown', aliases: ['md'] },
  mermaid:    { name: 'Mermaid', aliases: ['mmd'] },
} satisfies Record<string, { name: string; aliases?: string[] }>

type HighlightedCodeBlockLanguage = Exclude<keyof typeof supportedCodeBlockLanguages, 'text'>

// BlockNote renders suggestion menus through a body-level portal. Keep task-description
// menus above the create-task dialog, which occupies the product modal layer.
const taskDescriptionSuggestionMenuLayer = 1000

// Keep grammar modules out of the editor's initial bundle. BlockNote requests the
// selected grammar through this map only when a code block needs highlighting.
const codeBlockLanguageLoaders: Record<HighlightedCodeBlockLanguage, CodeBlockLanguageLoader> = {
  javascript: () => import('@shikijs/langs/javascript'),
  typescript: () => import('@shikijs/langs/typescript'),
  python: () => import('@shikijs/langs/python'),
  java: () => import('@shikijs/langs/java'),
  go: () => import('@shikijs/langs/go'),
  rust: () => import('@shikijs/langs/rust'),
  cpp: () => import('@shikijs/langs/cpp'),
  c: () => import('@shikijs/langs/c'),
  csharp: () => import('@shikijs/langs/csharp'),
  php: () => import('@shikijs/langs/php'),
  ruby: () => import('@shikijs/langs/ruby'),
  swift: () => import('@shikijs/langs/swift'),
  kotlin: () => import('@shikijs/langs/kotlin'),
  html: () => import('@shikijs/langs/html'),
  css: () => import('@shikijs/langs/css'),
  scss: () => import('@shikijs/langs/scss'),
  sql: () => import('@shikijs/langs/sql'),
  json: () => import('@shikijs/langs/json'),
  yaml: () => import('@shikijs/langs/yaml'),
  xml: () => import('@shikijs/langs/xml'),
  bash: () => import('@shikijs/langs/bash'),
  markdown: () => import('@shikijs/langs/markdown'),
  // Mermaid 仍由独立预览层渲染 SVG，但必须加载 grammar 终止 BlockNote 的懒加载重试。
  mermaid: () => import('@shikijs/langs/mermaid'),
}

export async function createCodeBlockHighlighter() {
  const [core, engine, theme] = await Promise.all([
    import('@shikijs/core'),
    import('@shikijs/engine-javascript'),
    import('@shikijs/themes/github-dark'),
  ])
  const highlighter = await core.createHighlighterCore({
    themes: [theme.default],
    langs: [],
    engine: engine.createJavaScriptRegexEngine(),
  })
  const loadLanguage = highlighter.loadLanguage.bind(highlighter)
  highlighter.loadLanguage = (language) => {
    if (typeof language !== 'string') return loadLanguage(language)
    const loader = codeBlockLanguageLoaders[language as HighlightedCodeBlockLanguage]
    return loader().then(({ default: grammar }) => loadLanguage(grammar))
  }
  return highlighter as unknown as HighlighterGeneric<any, any>
}

const codeBlockOptions = {
  // BlockNote only renders language tokens after a Shiki highlighter is supplied.
  createHighlighter: createCodeBlockHighlighter,
  supportedLanguages: supportedCodeBlockLanguages,
}

const codeBlockWithLanguages = createCodeBlockSpec(codeBlockOptions)

function getBlockContentText(block: any): string {
  const content = block?.content
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .map((item) => {
      if (typeof item === 'string') return item
      if (item && typeof item === 'object' && typeof item.text === 'string') return item.text
      return ''
    })
    .join('')
}

let mermaidHydrationSeq = 0

type MermaidBlockRef = {
  id: string
  source: string
}

type MermaidLayoutState = {
  heights: Map<string, number>
  sourceBlockIds: Set<string>
  style: HTMLStyleElement
}

const mermaidLayoutStates = new WeakMap<HTMLElement, MermaidLayoutState>()

function escapeCssAttributeValue(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function getMermaidLayoutState(layer: HTMLElement): MermaidLayoutState {
  const existing = mermaidLayoutStates.get(layer)
  if (existing) return existing
  const style = document.createElement('style')
  style.className = 'bn-mermaid-layout-styles'
  layer.appendChild(style)
  const created = { heights: new Map<string, number>(), sourceBlockIds: new Set<string>(), style }
  mermaidLayoutStates.set(layer, created)
  return created
}

function renderMermaidLayoutStyles(layer: HTMLElement) {
  const state = getMermaidLayoutState(layer)
  const rules: string[] = []
  for (const [blockId, height] of state.heights) {
    const id = escapeCssAttributeValue(blockId)
    rules.push(
      `.bn-block-outer[data-id="${id}"] .bn-block-content[data-content-type="codeBlock"][data-language="mermaid"] { --bn-mermaid-preview-height: ${height}px; }`
    )
  }
  for (const blockId of state.sourceBlockIds) {
    const id = escapeCssAttributeValue(blockId)
    rules.push(
      `.bn-block-outer[data-id="${id}"] .bn-block-content[data-content-type="codeBlock"][data-language="mermaid"] { height: auto; overflow: visible; visibility: visible; }`
    )
  }
  const css = rules.join('\n')
  if (state.style.textContent !== css) state.style.textContent = css
}

function setMermaidSourceMode(layer: HTMLElement, blockId: string, sourceVisible: boolean) {
  const state = getMermaidLayoutState(layer)
  if (sourceVisible) state.sourceBlockIds.add(blockId)
  else state.sourceBlockIds.delete(blockId)
  renderMermaidLayoutStyles(layer)
}

type ImagePreviewItem = {
  src: string
  width: number
  height: number
  alt?: string
  element: HTMLImageElement
}

export function buildProjectDocumentMentionHref(projectId: number, documentId: number): string {
  return `/projects/${projectId}/documents/${documentId}`
}

function hasNumberedListItem(blocks: readonly any[]): boolean {
  return blocks.some((block) =>
    block?.type === 'numberedListItem' ||
    (Array.isArray(block?.children) && hasNumberedListItem(block.children)),
  )
}

/** BlockNote 0.48 用事务初始化有序列表索引；initialContent 直传时需主动触发一次。 */
export function initializeNumberedListIndices(editor: any): void {
  if (!hasNumberedListItem(editor.document)) return
  editor.replaceBlocks(editor.document, editor.document)
}

export function createProjectDocumentLinkInline(projectId: number, documentId: number, title: string) {
  return {
    type: 'link' as const,
    href: buildProjectDocumentMentionHref(projectId, documentId),
    content: title,
  }
}

export function createMemberMentionInline(memberId: number, label: string) {
  return { type: 'mention' as const, props: { userId: String(memberId), label } }
}

function getPreviewImageSize(img: HTMLImageElement): { width: number; height: number } {
  const width = img.naturalWidth || Math.round(img.getBoundingClientRect().width) || 1600
  const height = img.naturalHeight || Math.round(img.getBoundingClientRect().height) || 1000
  return { width, height }
}

function collectImagePreviewItems(root: HTMLElement): ImagePreviewItem[] {
  return Array.from(root.querySelectorAll<HTMLImageElement>('img'))
    .filter((img) => {
      if (!img.currentSrc && !img.src) return false
      if (img.closest('.pswp')) return false
      return true
    })
    .map((img) => {
      const { width, height } = getPreviewImageSize(img)
      return {
        src: img.currentSrc || img.src,
        width,
        height,
        alt: img.alt || undefined,
        element: img,
      }
    })
}

function ensureImagePreviewButtons(root: HTMLElement) {
  const items = collectImagePreviewItems(root)
  const activeImages = new Set(items.map((item) => item.element))
  for (const image of root.querySelectorAll<HTMLImageElement>('.bn-image-preview-target')) {
    if (!activeImages.has(image)) image.classList.remove('bn-image-preview-target')
  }
  for (const button of root.querySelectorAll<HTMLButtonElement>('.bn-image-preview-button')) {
    const host = button.parentElement
    const img = host?.querySelector<HTMLImageElement>('img')
    if (!img || !activeImages.has(img)) {
      button.remove()
      host?.classList.remove('bn-image-preview-host')
    }
  }
  items.forEach((item, index) => {
    const host =
      item.element.closest<HTMLElement>('.bn-block-content') ??
      item.element.parentElement
    if (!host) return
    item.element.classList.add('bn-image-preview-target')
    host.classList.add('bn-image-preview-host')
    let button = host.querySelector<HTMLButtonElement>(':scope > .bn-image-preview-button')
    if (!button) {
      button = document.createElement('button')
      button.type = 'button'
      button.className = 'bn-image-preview-button'
      button.textContent = '预览'
      button.setAttribute('aria-label', 'Preview image')
      button.contentEditable = 'false'
      host.appendChild(button)
    }
    button.setAttribute('data-preview-index', String(index))
  })
}

function openImagePreview(root: HTMLElement, index: number) {
  const items = collectImagePreviewItems(root)
  const item = items[index]
  if (!item) return
  const pswp = new PhotoSwipe({
    dataSource: items.map((candidate) => ({
      src: candidate.src,
      width: candidate.width,
      height: candidate.height,
      alt: candidate.alt,
      element: candidate.element,
    })),
    index,
    bgOpacity: 0.92,
    wheelToZoom: true,
    maxZoomLevel: 8,
    showHideAnimationType: 'fade',
  })
  pswp.init()
}

function getMermaidSvgSize(svg: SVGSVGElement): { width: number; height: number } {
  const viewBox = svg.viewBox.baseVal
  if (viewBox?.width && viewBox?.height) {
    return { width: Math.ceil(viewBox.width), height: Math.ceil(viewBox.height) }
  }
  const rect = svg.getBoundingClientRect()
  return {
    width: Math.max(800, Math.ceil(rect.width) || 1600),
    height: Math.max(500, Math.ceil(rect.height) || 1000),
  }
}

function getMermaidSvgDataUrl(svg: SVGSVGElement): string {
  const clone = svg.cloneNode(true) as SVGSVGElement
  if (!clone.getAttribute('xmlns')) {
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  }
  const { width, height } = getMermaidSvgSize(svg)
  const background = document.createElementNS('http://www.w3.org/2000/svg', 'rect')
  background.setAttribute('class', 'bn-mermaid-preview-background')
  background.setAttribute('x', '0')
  background.setAttribute('y', '0')
  background.setAttribute('width', String(width))
  background.setAttribute('height', String(height))
  background.setAttribute('fill', '#fff')
  clone.insertBefore(background, clone.firstChild)
  const source = new XMLSerializer().serializeToString(clone)
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(source)}`
}

function openMermaidPreview(layer: HTMLElement, blockId: string) {
  const preview = layer.querySelector<HTMLElement>(
    `.bn-mermaid-preview[data-block-id="${blockId}"]`
  )
  const svg = preview?.querySelector<SVGSVGElement>('svg')
  if (!svg) return
  const { width, height } = getMermaidSvgSize(svg)
  const pswp = new PhotoSwipe({
    dataSource: [
      {
        src: getMermaidSvgDataUrl(svg),
        width,
        height,
        alt: 'Mermaid diagram',
      },
    ],
    index: 0,
    bgOpacity: 0.92,
    wheelToZoom: true,
    maxZoomLevel: 8,
    showHideAnimationType: 'fade',
  })
  pswp.init()
}

function collectMermaidBlocks(blocks: readonly any[]): MermaidBlockRef[] {
  const refs: MermaidBlockRef[] = []
  for (const block of blocks) {
    if (block?.type === 'codeBlock') {
      const language = String(block.props?.language ?? '').trim().toLowerCase()
      if (language === 'mermaid') {
        refs.push({ id: String(block.id), source: getBlockContentText(block) })
      }
    }
    if (Array.isArray(block?.children) && block.children.length > 0) {
      refs.push(...collectMermaidBlocks(block.children))
    }
  }
  return refs
}

function collectMermaidBlocksFromDom(root: HTMLElement): MermaidBlockRef[] {
  const refs: MermaidBlockRef[] = []
  for (const content of root.querySelectorAll<HTMLElement>(
    '.bn-block-content[data-content-type="codeBlock"][data-language="mermaid"]'
  )) {
    const host = content.closest<HTMLElement>('.bn-block-outer[data-id]')
    const id = host?.dataset.id
    if (!id) continue
    refs.push({ id, source: content.querySelector('pre')?.textContent ?? '' })
  }
  return refs
}

function findMermaidBlockHost(root: HTMLElement, blockId: string): HTMLElement | null {
  const selector = `.bn-block-outer[data-id="${blockId}"]`
  const blockOuter =
    root.querySelector<HTMLElement>(selector) ??
    document.querySelector<HTMLElement>(selector)
  if (!blockOuter) return null
  const content = blockOuter.querySelector<HTMLElement>(
    '.bn-block-content[data-content-type="codeBlock"]'
  )
  if (!content) return null
  return blockOuter
}

function removeStaleMermaidOverlays(
  layer: HTMLElement,
  activeBlockIds: Set<string>
) {
  for (const overlay of layer.querySelectorAll<HTMLElement>('.bn-mermaid-preview')) {
    const blockId = overlay.dataset.blockId
    if (blockId && activeBlockIds.has(blockId)) continue
    overlay.remove()
  }
  for (const button of layer.querySelectorAll<HTMLButtonElement>('.bn-mermaid-preview-zoom')) {
    const blockId = button.dataset.blockId
    if (blockId && activeBlockIds.has(blockId)) continue
    button.remove()
  }
  const layoutState = getMermaidLayoutState(layer)
  for (const blockId of layoutState.heights.keys()) {
    if (!activeBlockIds.has(blockId)) layoutState.heights.delete(blockId)
  }
  for (const blockId of layoutState.sourceBlockIds) {
    if (!activeBlockIds.has(blockId)) layoutState.sourceBlockIds.delete(blockId)
  }
  renderMermaidLayoutStyles(layer)
}

function positionMermaidPreview(root: HTMLElement, host: HTMLElement, preview: HTMLElement) {
  const rootRect = root.getBoundingClientRect()
  const hostRect = host.getBoundingClientRect()
  preview.style.left = `${hostRect.left - rootRect.left}px`
  preview.style.top = `${hostRect.top - rootRect.top}px`
  preview.style.width = `${hostRect.width}px`
}

function positionMermaidPreviewZoomButton(root: HTMLElement, host: HTMLElement, button: HTMLElement) {
  const rootRect = root.getBoundingClientRect()
  const hostRect = host.getBoundingClientRect()
  button.style.left = `${hostRect.right - rootRect.left - 54}px`
  button.style.top = `${hostRect.top - rootRect.top + 8}px`
}

export function syncMermaidPreviewHeight(root: HTMLElement, preview: HTMLElement) {
  const blockId = preview.dataset.blockId
  if (!blockId) return
  // 渲染期间保留稳定占位；只在终态提交一次真实高度，避免滚动时连续重排正文。
  if (preview.dataset.renderState !== 'resolved' && preview.dataset.renderState !== 'rejected') return
  const host = findMermaidBlockHost(root, blockId)
  if (!host || preview.classList.contains('bn-mermaid-preview--source')) return
  const layer = preview.parentElement
  if (!layer) return
  const height = Math.max(96, Math.ceil(preview.getBoundingClientRect().height))
  // 高度写入独立预览层，避免 BlockNote 重建受控 DOM 时覆盖尺寸并触发反复重排。
  const layoutState = getMermaidLayoutState(layer)
  if (layoutState.heights.get(blockId) === height) return
  layoutState.heights.set(blockId, height)
  renderMermaidLayoutStyles(layer)
}

function ensureMermaidPreview(
  root: HTMLElement,
  layer: HTMLElement,
  host: HTMLElement,
  blockId: string,
  sourceText: string
): HTMLButtonElement {
  let preview = layer.querySelector<HTMLButtonElement>(
    `.bn-mermaid-preview[data-block-id="${blockId}"]`
  )
  if (preview) return preview

  preview = document.createElement('button')
  preview.type = 'button'
  preview.className = 'bn-mermaid-preview'
  preview.contentEditable = 'false'
  preview.setAttribute('aria-label', 'Edit Mermaid source')
  preview.dataset.blockId = blockId
  layer.appendChild(preview)
  preview.dataset.initialSource = sourceText
  positionMermaidPreview(root, host, preview)
  return preview
}

function ensureMermaidPreviewZoomButton(
  root: HTMLElement,
  layer: HTMLElement,
  host: HTMLElement,
  blockId: string
): HTMLButtonElement {
  let button = layer.querySelector<HTMLButtonElement>(
    `.bn-mermaid-preview-zoom[data-block-id="${blockId}"]`
  )
  if (!button) {
    button = document.createElement('button')
    button.type = 'button'
    button.className = 'bn-mermaid-preview-zoom'
    button.textContent = '预览'
    button.setAttribute('aria-label', 'Preview Mermaid diagram')
    button.contentEditable = 'false'
    button.dataset.blockId = blockId
    button.hidden = true
    layer.appendChild(button)
  }
  positionMermaidPreviewZoomButton(root, host, button)
  return button
}

async function renderMermaidPreview(root: HTMLElement, preview: HTMLElement, sourceText: string) {
  const lastSource = preview.dataset.lastSource

  if (!sourceText.trim()) {
    preview.dataset.renderState = 'waiting'
    preview.classList.add('bn-mermaid-preview--loading')
    preview.textContent = 'Rendering Mermaid diagram...'
    syncMermaidPreviewHeight(root, preview)
    return
  }
  if (preview.dataset.renderState === 'rendering') return
  if (lastSource === sourceText && preview.querySelector('svg')) return

  preview.dataset.lastSource = sourceText
  preview.dataset.renderState = 'rendering'
  preview.classList.remove('bn-mermaid-preview--error')
  preview.classList.add('bn-mermaid-preview--loading')
  preview.textContent = 'Rendering Mermaid diagram...'

  try {
    const { svg, bindFunctions } = await renderMermaidSvg(sourceText, {
      id: `mermaid-${++mermaidHydrationSeq}`,
    })
    if (!preview.isConnected || preview.dataset.lastSource !== sourceText) return
    preview.dataset.renderState = 'resolved'
    preview.classList.remove('bn-mermaid-preview--loading', 'bn-mermaid-preview--error')
    preview.innerHTML = svg
    bindFunctions?.(preview)
    const zoomButton = preview.parentElement?.querySelector<HTMLButtonElement>(
      `.bn-mermaid-preview-zoom[data-block-id="${preview.dataset.blockId ?? ''}"]`
    )
    if (zoomButton) zoomButton.hidden = false
    window.requestAnimationFrame(() => syncMermaidPreviewHeight(root, preview))
  } catch (error) {
    if (!preview.isConnected || preview.dataset.lastSource !== sourceText) return
    preview.dataset.renderState = 'rejected'
    preview.dataset.renderError = normalizeMermaidRenderError(error)
    preview.classList.remove('bn-mermaid-preview--loading')
    preview.classList.add('bn-mermaid-preview--error')
    preview.textContent = normalizeMermaidRenderError(error)
    syncMermaidPreviewHeight(root, preview)
  }
}

function hydrateMermaidPreviewBlocks(root: HTMLElement, layer: HTMLElement, blocks: readonly any[]) {
  const byId = new Map<string, MermaidBlockRef>()
  for (const block of collectMermaidBlocks(blocks)) byId.set(block.id, block)
  for (const block of collectMermaidBlocksFromDom(root)) byId.set(block.id, block)
  const mermaidBlocks = Array.from(byId.values())
  const activeIds = new Set(mermaidBlocks.map((block) => block.id))
  removeStaleMermaidOverlays(layer, activeIds)

  for (const block of mermaidBlocks) {
    const host = findMermaidBlockHost(root, block.id)
    if (!host) continue
    const preview = ensureMermaidPreview(root, layer, host, block.id, block.source)
    const zoomButton = ensureMermaidPreviewZoomButton(root, layer, host, block.id)
    positionMermaidPreview(root, host, preview)
    positionMermaidPreviewZoomButton(root, host, zoomButton)
    zoomButton.hidden =
      preview.classList.contains('bn-mermaid-preview--source') || !preview.querySelector('svg')
    syncMermaidPreviewHeight(root, preview)
    const lastSource = preview.dataset.lastSource

    if (!block.source.trim()) {
      if (preview.dataset.renderState !== 'waiting') {
        preview.dataset.renderState = 'waiting'
        preview.classList.add('bn-mermaid-preview--loading')
        preview.textContent = 'Rendering Mermaid diagram...'
      }
      continue
    }
    if (preview.dataset.renderState === 'rendering') continue
    if (lastSource === block.source && preview.querySelector('svg')) continue

    void renderMermaidPreview(root, preview, block.source)
  }
}

// ─── Mention inline content spec ───────────────────────────────────────────────

const mentionSpec = createReactInlineContentSpec(
  {
    type: 'mention' as const,
    propSchema: {
      userId: { default: '' as string },
      label: { default: '' as string },
    },
    content: 'none' as const,
  },
  {
    render: ({ inlineContent }) => (
      <span className="bn-mention">@{inlineContent.props.label}</span>
    ),
  }
)

// ─── Document image block ──────────────────────────────────────────────────────

type DocumentImageContextValue = {
  resolve: (assetId: number) => DocumentImageAsset | undefined
  onOpenOriginal: (asset: DocumentImageAsset, element: HTMLImageElement) => void
}

const DocumentImageContext = createContext<DocumentImageContextValue>({
  resolve: () => undefined,
  onOpenOriginal: () => {},
})

type DocumentImageBlockProps = { props: { imageAssetId?: unknown; caption?: unknown } }

function DocumentImageBlock({ block }: { block: DocumentImageBlockProps }) {
  const context = useContext(DocumentImageContext)
  const assetId = Number(block.props.imageAssetId ?? 0)
  const asset = assetId > 0 ? context.resolve(assetId) : undefined
  const caption = typeof block.props.caption === 'string' ? block.props.caption : ''
  if (!asset) {
    // 资源清单尚未到达或跨文档资源正在重绑定；不渲染临时展示地址，保持正文只存 assetId。
    return (
      <div
        className="bn-document-image bn-document-image--missing"
        contentEditable={false}
        role="img"
        aria-label={caption}
      />
    )
  }
  const imageSrcSet = asset.thumbnailUrl
    ? `${asset.thumbnailUrl} 512w, ${asset.originalUrl} ${asset.width ?? 1600}w`
    : undefined
  return (
    <div className="bn-document-image" contentEditable={false}>
      <img
        src={asset.originalUrl}
        srcSet={imageSrcSet}
        sizes="(max-width: 768px) 100vw, 1000px"
        data-image-asset-id={String(asset.assetId)}
        data-original-url={asset.originalUrl}
        width={asset.width ?? undefined}
        height={asset.height ?? undefined}
        loading="lazy"
        decoding="async"
        alt={caption}
        onClick={(event) => context.onOpenOriginal(asset, event.currentTarget)}
      />
      {caption ? <span className="bn-document-image__caption">{caption}</span> : null}
    </div>
  )
}

/** 正文图片块只持久化 assetId；地址、尺寸与缩略图由文档图片资源清单解析。 */
const documentImageSpec = createReactBlockSpec(
  {
    type: 'documentImage' as const,
    propSchema: {
      imageAssetId: { default: 0 },
      caption: { default: '' },
    },
    content: 'none' as const,
  },
  {
    render: ({ block }) => (
      <DocumentImageBlock block={block as unknown as DocumentImageBlockProps} />
    ),
    parse: (element) => {
      const raw = element.getAttribute('data-image-asset-id')
      if (raw == null) return undefined
      const imageAssetId = Number(raw)
      if (!Number.isFinite(imageAssetId) || imageAssetId <= 0) return undefined
      return { imageAssetId, caption: element.getAttribute('data-caption') ?? '' }
    },
    toExternalHTML: ({ block }) => {
      const props = block.props as unknown as { imageAssetId?: unknown; caption?: unknown }
      return (
        <img
          data-image-asset-id={String(props.imageAssetId ?? 0)}
          data-caption={typeof props.caption === 'string' ? props.caption : ''}
        />
      )
    },
  }
)()

const defaultDocumentBlockSpecs = Object.fromEntries(
  Object.entries(defaultBlockSpecs).filter(([type]) => type !== 'image'),
) as Omit<typeof defaultBlockSpecs, 'image'>

// Task descriptions retain the URL-backed image block; document bodies use asset IDs only.
const taskSchema = BlockNoteSchema.create({
  blockSpecs: {
    ...defaultBlockSpecs,
    codeBlock: codeBlockWithLanguages,
    documentImage: documentImageSpec,
  },
  inlineContentSpecs: {
    ...defaultInlineContentSpecs,
    mention: mentionSpec,
  },
})

const documentSchema = BlockNoteSchema.create({
  blockSpecs: {
    ...defaultDocumentBlockSpecs,
    codeBlock: codeBlockWithLanguages,
    documentImage: documentImageSpec,
  },
  inlineContentSpecs: {
    ...defaultInlineContentSpecs,
    mention: mentionSpec,
  },
})

// ─── Helpers ────────────────────────────────────────────────────────────────────

type AnyBlock = {
  content?: unknown[]
  children?: AnyBlock[]
}

function extractMentionIdsFromBlocks(blocks: AnyBlock[]): number[] {
  const ids: number[] = []
  for (const block of blocks) {
    if (Array.isArray(block.content)) {
      for (const inline of block.content as Array<{ type?: string; props?: { userId?: string } }>) {
        if (inline.type === 'mention' && inline.props?.userId) {
          const id = parseInt(String(inline.props.userId), 10)
          if (Number.isFinite(id)) ids.push(id)
        }
      }
    }
    if (Array.isArray(block.children) && block.children.length > 0) {
      ids.push(...extractMentionIdsFromBlocks(block.children))
    }
  }
  return [...new Set(ids)]
}

function removeAttachmentLinkFromDocument(editor: any, href: string): boolean {
  const blocksToRemove: string[] = []
  let removed = false

  const visit = (blocks: readonly any[]) => {
    for (const block of blocks) {
      if (Array.isArray(block.content)) {
        const remainingContent = block.content.filter(
          (inline: any) => inline?.type !== 'link' || inline.href !== href,
        )
        if (remainingContent.length !== block.content.length) {
          removed = true
          if (block.type === 'paragraph' && remainingContent.length === 0) {
            blocksToRemove.push(block.id)
          } else {
            editor.updateBlock(block.id, { content: remainingContent })
          }
        }
      }
      if (Array.isArray(block.children) && block.children.length > 0) visit(block.children)
    }
  }

  visit(editor.document)
  if (blocksToRemove.length > 0) editor.removeBlocks(blocksToRemove)
  return removed
}

// ─── Component ──────────────────────────────────────────────────────────────────

export interface EditorApi {
  focus: () => void
  focusAppend: () => void
  getMentionedUserIds: () => number[]
  insertMention: (userId: string, label: string) => void
  removeAttachmentLink: (href: string) => boolean
}

/** Vue/veaury 可能以 `upload-file` 传入，与 React 的 `uploadFile` 并存 */
export type BlockNoteEditorReactProps = {
  /** BlockNote 存库的 JSON（Block[]）或 Markdown 文本（含以 `[` 开头的链接等） */
  initialContent?: string
  placeholder?: string
  editable?: boolean
  mentionMembers?: Array<{
    id: number
    label: string
    principalType?: 'human' | 'agent'
    agentKey?: string | null
  }>
  mentionDocuments?: Array<{ id: number; title: string; projectId: number }>
  /** Generic editor file upload. Task descriptions keep URL-backed image blocks. */
  uploadFile?: (file: File) => Promise<string>
  'upload-file'?: (file: File) => Promise<string>
  /** Document images enter the editor through their resource identity. */
  uploadImageAsset?: (file: File) => Promise<DocumentImageAsset>
  externalImagePasteRejectedText?: string
  documentImageCloneFailedText?: string
  documentImageMenuLabel?: string
  imageUploadTypeUnsupportedText?: string
  /** Current document ID scopes cross-document image cloning. */
  documentId?: number
  'document-id'?: number
  /** 文档图片资源清单：正文图片身份的唯一解析来源。 */
  imageAssets?: DocumentImageAsset[]
  'image-assets'?: DocumentImageAsset[]
  /** 跨文档粘贴图片时把资源复制到当前文档。 */
  cloneImageAsset?: (assetId: number) => Promise<DocumentImageAsset>
  'clone-image-asset'?: (assetId: number) => Promise<DocumentImageAsset>
  /** 文档中的普通附件粘贴后写入链接块，图片粘贴后写入可预览的图片块。 */
  pasteFileAsLink?: boolean
  'paste-file-as-link'?: boolean
  /** 附件上传中的占位文本模板，`{name}` 替换为文件名（由 Vue i18n 传入）。 */
  fileUploadingText?: string
  'file-uploading-text'?: string
  /** 附件上传失败的占位文本模板，`{name}` 替换为文件名（由 Vue i18n 传入）。 */
  fileUploadFailedText?: string
  'file-upload-failed-text'?: string
  /** Called on every document change with serialized JSON and mentioned user IDs */
  onChange?: (jsonString: string, mentionedUserIds: number[]) => void
  'on-change'?: (jsonString: string, mentionedUserIds: number[]) => void
  onBlur?: () => void
  'on-blur'?: () => void
  onFocus?: () => void
  'on-focus'?: () => void
  /** Called once when the editor is mounted, receives imperative API */
  onInit?: (api: EditorApi) => void
  'on-init'?: (api: EditorApi) => void
  /** 任务描述：`/` slash 菜单与表格控制；其它场景保持关闭以减小干扰 */
  blockChrome?: boolean
  'block-chrome'?: boolean
  /** `@` 成员菜单顶栏占位（与清单「搜索成员」一致，由 Vue i18n 传入） */
  mentionMenuSearchPlaceholder?: string
  'mention-menu-search-placeholder'?: string
  mentionMenuNoMatchesText?: string
  'mention-menu-no-matches-text'?: string
  mentionMenuLoadingText?: string
  'mention-menu-loading-text'?: string
  mentionMembersGroupText?: string
  'mention-members-group-text'?: string
  mentionDocumentsGroupText?: string
  'mention-documents-group-text'?: string
  checkPiBridgeOnMention?: boolean
  'check-pi-bridge-on-mention'?: boolean
}

export default function BlockNoteEditorReact(props: BlockNoteEditorReactProps) {
  const {
    initialContent,
    placeholder,
    editable = true,
    mentionMembers,
    mentionDocuments,
    uploadFile,
    uploadImageAsset,
    documentId,
    imageAssets,
    cloneImageAsset,
    onChange,
    onBlur,
    onInit,
    blockChrome = false,
    pasteFileAsLink = false,
  } = props

  const checkPiBridgeOnMention =
    props.checkPiBridgeOnMention === true || props['check-pi-bridge-on-mention'] === true

  const blockChromeOn = blockChrome === true || props['block-chrome'] === true
  const taskDescriptionSuggestionMenuOptions = useMemo(
    () => ({ elementProps: { style: { zIndex: taskDescriptionSuggestionMenuLayer } } }),
    [],
  )

  const mentionSearchPh =
    props.mentionMenuSearchPlaceholder ?? props['mention-menu-search-placeholder'] ?? ''
  const mentionNoMatchPh =
    props.mentionMenuNoMatchesText ?? props['mention-menu-no-matches-text'] ?? ''
  const mentionLoadingPh =
    props.mentionMenuLoadingText ?? props['mention-menu-loading-text'] ?? '…'
  const mentionMembersGroup =
    props.mentionMembersGroupText ?? props['mention-members-group-text'] ?? 'Members'
  const mentionDocumentsGroup =
    props.mentionDocumentsGroupText ?? props['mention-documents-group-text'] ?? 'Documents'

  const uploadFileResolved =
    uploadFile ?? props['upload-file']
  const documentIdResolved = documentId ?? props['document-id']
  const imageAssetsResolved = imageAssets ?? props['image-assets']
  const cloneImageAssetResolved = cloneImageAsset ?? props['clone-image-asset']
  const editorSchema = documentIdResolved != null ? documentSchema : taskSchema

  const editorRootRef = useRef<HTMLDivElement | null>(null)
  const mermaidLayerRef = useRef<HTMLDivElement | null>(null)
  const uploadFileRef = useRef(uploadFileResolved)
  uploadFileRef.current = uploadFileResolved
  const uploadImageAssetRef = useRef(uploadImageAsset)
  uploadImageAssetRef.current = uploadImageAsset

  // 正文图片只存 assetId：注册表由文档清单 + 本次会话新上传/复制的资源合并而成。
  const [assetOverrides, setAssetOverrides] = useState<Map<number, DocumentImageAsset>>(new Map())
  const assetOverridesRef = useRef(assetOverrides)
  const propAssetMap = useMemo(() => {
    const map = new Map<number, DocumentImageAsset>()
    for (const asset of imageAssetsResolved ?? []) map.set(asset.assetId, asset)
    return map
  }, [imageAssetsResolved])
  const resolveAsset = useCallback(
    (assetId: number) => assetOverridesRef.current.get(assetId) ?? propAssetMap.get(assetId),
    [assetOverrides, propAssetMap],
  )
  const resolveAssetRef = useRef(resolveAsset)
  resolveAssetRef.current = resolveAsset
  const cloneImageAssetRef = useRef(cloneImageAssetResolved)
  cloneImageAssetRef.current = cloneImageAssetResolved
  const documentIdRef = useRef(documentIdResolved)
  documentIdRef.current = documentIdResolved
  const registerAsset = useCallback((asset: DocumentImageAsset) => {
    if (assetOverridesRef.current.has(asset.assetId)) return
    const next = new Map(assetOverridesRef.current)
    next.set(asset.assetId, asset)
    assetOverridesRef.current = next
    setAssetOverrides(next)
  }, [])

  /** BlockNote accepts either a URL or replacement block data from its file upload callback. */
  const blockNoteUploadFile = useCallback(async (file: File) => {
    if (isPastedImageFile(file)) {
      const uploadAsset = uploadImageAssetRef.current
      if (uploadAsset) {
        const result = await createDocumentImageUploadResult(file, uploadAsset)
        registerAsset(result.asset)
        return result.block
      }
      if (documentIdRef.current != null) {
        throw new Error('Document image asset upload not configured')
      }
    }
    const fn = uploadFileRef.current
    if (!fn) {
      throw new Error('uploadFile not configured')
    }
    return fn(file)
  }, [registerAsset])

  const pasteFileAsLinkResolved = pasteFileAsLink || props['paste-file-as-link'] === true
  const fileUploadingTextResolved = props.fileUploadingText ?? props['file-uploading-text']
  const fileUploadFailedTextResolved = props.fileUploadFailedText ?? props['file-upload-failed-text']
  const externalImagePasteRejectedTextResolved = props.externalImagePasteRejectedText ??
    'Images pasted as external links cannot be added. Download the image, then upload it to this document.'
  const documentImageCloneFailedTextResolved = props.documentImageCloneFailedText ??
    'A pasted document image could not be copied. Check access to its source document.'
  const imageUploadTypeUnsupportedTextResolved = props.imageUploadTypeUnsupportedText ??
    'Choose an image file to insert into this document.'
  const [imagePasteNotice, setImagePasteNotice] = useState('')
  const editableRef = useRef(editable)
  editableRef.current = editable
  const pasteFileAsLinkRef = useRef(pasteFileAsLinkResolved)
  pasteFileAsLinkRef.current = pasteFileAsLinkResolved
  const externalImagePasteRejectedTextRef = useRef(externalImagePasteRejectedTextResolved)
  externalImagePasteRejectedTextRef.current = externalImagePasteRejectedTextResolved
  const documentImageCloneFailedTextRef = useRef(documentImageCloneFailedTextResolved)
  documentImageCloneFailedTextRef.current = documentImageCloneFailedTextResolved

  const pasteFiles = useCallback(async (files: File[], editorInstance: any) => {
    const upload = uploadFileRef.current
    if (!upload) throw new Error('uploadFile not configured')
    let anchorId = editorInstance.getTextCursorPosition().block.id
    for (const file of files) {
      // 粘贴瞬间先插入“上传中”占位块，图片上传完成后原地替换为图片块，普通文件仍替换为附件链接卡片。
      const placeholder = editorInstance.insertBlocks([
        {
          type: 'paragraph',
          content: [
            {
              type: 'text',
              text: formatUploadFeedback(fileUploadingTextResolved, file.name, `Uploading ${file.name}…`),
              styles: { textColor: 'gray' },
            },
          ],
        },
      ], anchorId, 'after')[0]
      if (placeholder == null) return
      anchorId = placeholder.id
      let uploaded: string | DocumentImageUploadResult
      try {
        const uploadAsset = uploadImageAssetRef.current
        if (isPastedImageFile(file) && uploadAsset) {
          uploaded = await createDocumentImageUploadResult(file, uploadAsset)
          registerAsset(uploaded.asset)
        } else {
          if (isPastedImageFile(file) && documentIdRef.current != null) {
            throw new Error('Document image asset upload not configured')
          }
          uploaded = await upload(file)
        }
      } catch {
        // 上传失败：占位块改写为可见错误提示，不再静默吞掉异常。
        if (editorInstance.getBlock(placeholder.id) != null) {
          editorInstance.updateBlock(placeholder.id, {
            type: 'paragraph',
            content: [
              {
                type: 'text',
                text: formatUploadFeedback(fileUploadFailedTextResolved, file.name, `Uploading ${file.name} failed`),
                styles: { textColor: 'red' },
              },
            ],
          })
        }
        continue
      }
      // 用户可能在上传期间删除了占位块；块已不存在时放弃原地替换，避免抛出异常。
      if (editorInstance.getBlock(placeholder.id) == null) continue
      const inserted = typeof uploaded === 'string'
        ? isPastedImageFile(file)
          ? editorInstance.updateBlock(placeholder.id, {
              type: 'image',
              props: { name: file.name, url: uploaded },
            })
          : editorInstance.updateBlock(placeholder.id, {
              type: 'paragraph',
              content: [{ type: 'link', href: uploaded, content: file.name }],
            })
        : editorInstance.updateBlock(placeholder.id, {
            type: uploaded.block.type,
            props: uploaded.block.props,
          })
      if (inserted == null) continue
      anchorId = inserted.id
    }
  }, [fileUploadingTextResolved, fileUploadFailedTextResolved, registerAsset])

  const mentionMembersRef = useRef(mentionMembers)
  mentionMembersRef.current = mentionMembers
  const mentionDocumentsRef = useRef(mentionDocuments)
  mentionDocumentsRef.current = mentionDocuments

  const onChangeResolved = onChange ?? props['on-change']
  const onChangeRef = useRef(onChangeResolved)
  onChangeRef.current = onChangeResolved

  const onBlurResolved = onBlur ?? props['on-blur']
  const onBlurRef = useRef(onBlurResolved)
  onBlurRef.current = onBlurResolved

  const onFocusResolved = props.onFocus ?? props['on-focus']
  const onFocusRef = useRef(onFocusResolved)
  onFocusRef.current = onFocusResolved

  const onInitResolved = onInit ?? props['on-init']
  const onInitRef = useRef(onInitResolved)
  onInitRef.current = onInitResolved

  // 仅确认的 BlockNote JSON 作为 initialContent；其余整段交给 mount 后 Markdown 解析
  const parsedJsonInitial = useMemo(() => {
    const raw = (initialContent ?? '').trim()
    if (!raw) return undefined
    const parsed = parseBlockNoteStoredBlocks(raw)
    // 空文档交给 BlockNote 创建默认段落，但仍视为已识别的 JSON 存储格式。
    return parsed && parsed.length > 0 ? parsed : undefined
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const editor = useCreateBlockNote(
    {
      schema: editorSchema,
      uploadFile: blockNoteUploadFile,
      pasteHandler: ({ event, editor, defaultPasteHandler }) => {
        const clipboardData = event.clipboardData
        const clipboardTypes = clipboardData ? Array.from(clipboardData.types) : []
        const hasBlockNoteHtml = clipboardTypes.includes('blocknote/html')
        const internalHtml = hasBlockNoteHtml ? clipboardData?.getData('blocknote/html') ?? '' : ''
        const html = hasBlockNoteHtml
          ? internalHtml
          : clipboardTypes.includes('text/html')
            ? clipboardData?.getData('text/html') ?? ''
            : ''
        if (pasteFileAsLinkRef.current && clipboardTypes.includes('Files')) {
          const files = clipboardData == null
            ? []
            : Array.from(clipboardData.items)
              .map((item) => item.getAsFile())
              .filter((file): file is File => file != null)
          if (files.length > 0) {
            void pasteFiles(files, editor).catch(() => undefined)
            return true
          }
        }
        const plainText = clipboardData
          ? clipboardTypes.includes('text/markdown')
            ? clipboardData.getData('text/markdown')
            : clipboardData.getData('text/plain')
          : ''
        const isInCodeBlock = editor.transact(
          (tr) => tr.selection.$from.parent.type.spec.code && tr.selection.$to.parent.type.spec.code,
        )
        if (editableRef.current && documentIdRef.current != null && !isInCodeBlock) {
          if (hasExternalImagePaste(html) || hasMarkdownImagePaste(plainText)) {
            setImagePasteNotice(externalImagePasteRejectedTextRef.current)
            return true
          }
          if (hasDocumentImageAssetPaste(html)) {
            void clonePastedDocumentImageAssets(
              html,
              (assetId) => resolveAssetRef.current(assetId),
              cloneImageAssetRef.current,
            ).then(({ html: localHtml, clonedAssets }) => {
              clonedAssets.forEach(registerAsset)
              editor.pasteHTML(localHtml, internalHtml.length > 0)
            }).catch(() => {
              setImagePasteNotice(documentImageCloneFailedTextRef.current)
            })
            return true
          }
        }

        // 外部文档/LLM 常把表格复制成 Unicode 框线文本；先转换成 GFM，避免按多个段落保存。
        const asciiTableMarkdown = !isInCodeBlock ? asciiTableToMarkdown(plainText) : undefined
        if (asciiTableMarkdown) {
          editor.pasteMarkdown(asciiTableMarkdown)
          return true
        }

        // Raw Markdown copied from a file/editor usually has no HTML MIME entry,
        // so bypass BlockNote's narrow Markdown detector and parse it explicitly.
        if (!isInCodeBlock && shouldPasteClipboardAsMarkdown(clipboardTypes, plainText)) {
          editor.pasteMarkdown(plainText)
          return true
        }
        return defaultPasteHandler()
      },
      initialContent: parsedJsonInitial as PartialBlock<any, any, any>[] | undefined,
      // Use deprecated placeholders until dictionary approach is confirmed stable
      placeholders: placeholder ? { default: placeholder } : undefined,
    },
    [editorSchema]
  )

  const documentImageMenuLabelResolved = props.documentImageMenuLabel ?? 'Image'
  const imageInsertAnchorRef = useRef<string | null>(null)
  const documentImageFileInputRef = useRef<HTMLInputElement | null>(null)
  const openDocumentImagePicker = useCallback(() => {
    if (!editableRef.current) return
    const anchor = editor.getTextCursorPosition().block as any
    imageInsertAnchorRef.current = anchor.id
    const input = documentImageFileInputRef.current
    if (!input) return
    input.value = ''
    input.click()
  }, [editor])

  const handleDocumentImageFileChange = useCallback(async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0]
    event.currentTarget.value = ''
    if (!file) return
    if (!isPastedImageFile(file)) {
      setImagePasteNotice(imageUploadTypeUnsupportedTextResolved)
      return
    }
    const uploadAsset = uploadImageAssetRef.current
    if (!uploadAsset) {
      setImagePasteNotice(formatUploadFeedback(
        fileUploadFailedTextResolved,
        file.name,
        `Uploading ${file.name} failed`,
      ))
      return
    }
    const anchorId = imageInsertAnchorRef.current
    if (!anchorId) return
    try {
      const result = await createDocumentImageUploadResult(file, uploadAsset)
      registerAsset(result.asset)
      const anchor = editor.getBlock(anchorId) as any
      if (!anchor) {
        setImagePasteNotice(formatUploadFeedback(
          fileUploadFailedTextResolved,
          file.name,
          `Uploading ${file.name} failed`,
        ))
        return
      }
      const content = anchor.content
      const anchorIsEmptyParagraph = anchor.type === 'paragraph' && (
        !Array.isArray(content) || content.length === 0 || (
          content.length === 1 && content[0]?.type === 'text' && !content[0]?.text
        )
      )
      if (anchorIsEmptyParagraph) {
        editor.updateBlock(anchorId, {
          type: result.block.type,
          props: result.block.props,
        } as any)
      } else {
        editor.insertBlocks([result.block] as any, anchorId, 'after')
      }
      setImagePasteNotice('')
      editor.focus()
    } catch {
      setImagePasteNotice(formatUploadFeedback(
        fileUploadFailedTextResolved,
        file.name,
        `Uploading ${file.name} failed`,
      ))
    }
  }, [editor, fileUploadFailedTextResolved, imageUploadTypeUnsupportedTextResolved, registerAsset])

  const getDocumentSlashItems = useCallback(async (query: string): Promise<DefaultReactSuggestionItem[]> => {
    const imageItem: DefaultReactSuggestionItem = {
      title: documentImageMenuLabelResolved,
      aliases: ['image', '图片'],
      icon: <span aria-hidden="true">▧</span>,
      onItemClick: openDocumentImagePicker,
    }
    return filterSuggestionItems([...getDefaultReactSlashMenuItems(editor), imageItem], query)
  }, [documentImageMenuLabelResolved, editor, openDocumentImagePicker])

  const openOriginal = useCallback((asset: DocumentImageAsset, element: HTMLImageElement) => {
    const pswp = new PhotoSwipe({
      dataSource: [{ src: asset.originalUrl, width: asset.width ?? 1600, height: asset.height ?? 1000, element }],
      index: 0,
      bgOpacity: 0.92,
      wheelToZoom: true,
      maxZoomLevel: 8,
      showHideAnimationType: 'fade',
    })
    pswp.init()
  }, [])

  const documentImageContext = useMemo(
    () => ({ resolve: resolveAsset, onOpenOriginal: openOriginal }),
    [resolveAsset, openOriginal],
  )

  // 非 BlockNote JSON 的整段内容（含 `[` 开头的 Markdown）在 mount 后解析
  useEffect(() => {
    const raw = (initialContent ?? '').trim()
    if (!raw) return
    if (parseBlockNoteStoredBlocks(raw) !== undefined) return
    try {
      const blocks = editor.tryParseMarkdownToBlocks(raw)
      if (blocks.length > 0) {
        editor.replaceBlocks(editor.document, blocks)
      }
    } catch {
      // Ignore conversion errors; editor will start empty
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Expose imperative API to Vue wrapper via onInit callback
  useEffect(() => {
    const fn = onInitRef.current
    if (!fn) return
    fn({
      focus: () => editor.focus(),
      focusAppend: () => {
        editor.focus()
        const doc = editor.document
        const lastBlock = doc.length > 0 ? doc[doc.length - 1] : undefined
        if (!lastBlock) return
        const content = (lastBlock as unknown as Record<string, unknown>).content
        const isEmptyParagraph =
          lastBlock.type === 'paragraph' &&
          (!Array.isArray(content) ||
            content.length === 0 ||
            (content.length === 1 &&
              (content[0] as Record<string, unknown>).type === 'text' &&
              !(content[0] as Record<string, unknown>).text))
        if (isEmptyParagraph) {
          editor.setTextCursorPosition(lastBlock.id, 'end')
        } else {
          editor.insertBlocks([{ type: 'paragraph', content: [] }], lastBlock.id, 'after')
          const newDoc = editor.document
          const newLast = newDoc.length > 0 ? newDoc[newDoc.length - 1] : undefined
          if (newLast) editor.setTextCursorPosition(newLast.id, 'start')
        }
      },
      getMentionedUserIds: () =>
        extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[]),
      removeAttachmentLink: (href: string) => removeAttachmentLinkFromDocument(editor, href),
      insertMention: (userId: string, label: string) => {
        editor.focus()
        editor.insertInlineContent(
          [{ type: 'mention', props: { userId, label } }, ' '] as Parameters<
            typeof editor.insertInlineContent
          >[0]
        )
      },
    })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const emitDocumentToVue = useCallback(() => {
    if (editorRootRef.current && mermaidLayerRef.current) {
      hydrateMermaidPreviewBlocks(editorRootRef.current, mermaidLayerRef.current, editor.document)
    }
    const jsonString = JSON.stringify(editor.document)
    const mentionedIds = extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[])
    onChangeRef.current?.(jsonString, mentionedIds)
  }, [editor])

  useEffect(() => {
    const root = editorRootRef.current
    const layer = mermaidLayerRef.current
    if (!root || !layer) return
    const hydrate = () => hydrateMermaidPreviewBlocks(root, layer, editor.document)
    let hydrationTimer: ReturnType<typeof window.setTimeout> | undefined
    const queueHydrate = () => {
      if (hydrationTimer != null) window.clearTimeout(hydrationTimer)
      hydrationTimer = window.setTimeout(() => {
        hydrationTimer = undefined
        hydrate()
      }, 80)
    }
    hydrate()
    // BlockNote 后续构建节点并更新 data-language；两类变化都由观察器驱动，禁止定时轮询。
    const observer = new MutationObserver(queueHydrate)
    observer.observe(root, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-id', 'data-content-type', 'data-language'],
    })
    return () => {
      if (hydrationTimer != null) window.clearTimeout(hydrationTimer)
      observer.disconnect()
    }
  }, [editor])

  // 必须在 BlockNoteView 注册 onChange 之前初始化索引，避免这次内部事务被误判为用户编辑并触发自动保存。
  useLayoutEffect(() => {
    initializeNumberedListIndices(editor)
  }, [editor])

  useEffect(() => {
    if (!blockChromeOn) return
    const root = editorRootRef.current
    if (!root) return
    const hydrate = () => ensureImagePreviewButtons(root)
    let hydrationTimer: ReturnType<typeof window.setTimeout> | undefined
    const queueHydrate = () => {
      if (hydrationTimer != null) window.clearTimeout(hydrationTimer)
      hydrationTimer = window.setTimeout(() => {
        hydrationTimer = undefined
        hydrate()
      }, 80)
    }
    hydrate()
    const timers = [
      window.setTimeout(hydrate, 0),
      window.setTimeout(hydrate, 160),
      window.setTimeout(hydrate, 600),
      window.setTimeout(hydrate, 1600),
    ]
    const observer = new MutationObserver(queueHydrate)
    observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] })

    const handleClick = (event: MouseEvent) => {
      const target = event.target instanceof Element ? event.target : null
      const button = target?.closest<HTMLButtonElement>('.bn-image-preview-button')
      if (!button || !root.contains(button)) return
      event.preventDefault()
      event.stopPropagation()
      const index = Number.parseInt(button.dataset.previewIndex ?? '', 10)
      if (!Number.isFinite(index)) return
      openImagePreview(root, index)
    }

    root.addEventListener('click', handleClick, true)
    return () => {
      if (hydrationTimer != null) window.clearTimeout(hydrationTimer)
      observer.disconnect()
      root.removeEventListener('click', handleClick, true)
      for (const timer of timers) window.clearTimeout(timer)
    }
  }, [blockChromeOn])

  useEffect(() => {
    const root = editorRootRef.current
    if (!root) return

    const showSource = (preview: HTMLElement) => {
      const blockId = preview.dataset.blockId
      if (!blockId) return
      const host = findMermaidBlockHost(root, blockId)
      preview.classList.add('bn-mermaid-preview--source')
      if (mermaidLayerRef.current) setMermaidSourceMode(mermaidLayerRef.current, blockId, true)
      host?.querySelector<HTMLElement>('[contenteditable="true"]')?.focus()
    }

    const restorePreviewIfPointerLeft = (event: MouseEvent) => {
      for (const preview of root.querySelectorAll<HTMLElement>('.bn-mermaid-preview--source')) {
        const blockId = preview.dataset.blockId
        if (!blockId) continue
        const host = findMermaidBlockHost(root, blockId)
        if (!host) {
          preview.classList.remove('bn-mermaid-preview--source')
          if (mermaidLayerRef.current) setMermaidSourceMode(mermaidLayerRef.current, blockId, false)
          continue
        }
        const rect = host.getBoundingClientRect()
        const inside =
          event.clientX >= rect.left &&
          event.clientX <= rect.right &&
          event.clientY >= rect.top &&
          event.clientY <= rect.bottom
        if (!inside) {
          preview.classList.remove('bn-mermaid-preview--source')
          if (mermaidLayerRef.current) setMermaidSourceMode(mermaidLayerRef.current, blockId, false)
          syncMermaidPreviewHeight(root, preview)
        }
      }
    }

    const handleClick = (event: MouseEvent) => {
      const target = event.target instanceof Element ? event.target : null
      const zoomButton = target?.closest<HTMLButtonElement>('.bn-mermaid-preview-zoom')
      if (zoomButton && root.contains(zoomButton)) {
        event.preventDefault()
        event.stopPropagation()
        const blockId = zoomButton.dataset.blockId
        if (blockId && mermaidLayerRef.current) openMermaidPreview(mermaidLayerRef.current, blockId)
        return
      }
      const preview = target?.closest<HTMLElement>('.bn-mermaid-preview')
      if (!preview || !root.contains(preview)) return
      event.preventDefault()
      showSource(preview)
    }

    root.addEventListener('click', handleClick, true)
    document.addEventListener('mousemove', restorePreviewIfPointerLeft, true)
    return () => {
      root.removeEventListener('click', handleClick, true)
      document.removeEventListener('mousemove', restorePreviewIfPointerLeft, true)
    }
  }, [])

  /** uploadFile 的 onUploadEnd 早于 updateBlock；微任务里再 emit 一次，避免漏同步图片 URL。 */
  useEffect(() => {
    return editor.onUploadEnd(() => {
      queueMicrotask(() => {
        emitDocumentToVue()
      })
    })
  }, [editor, emitDocumentToVue])

  const handleBlur = useCallback((_e: React.FocusEvent) => {
    onBlurRef.current?.()
  }, [])

  const handleFocus = useCallback((_e: React.FocusEvent) => {
    onFocusRef.current?.()
  }, [])

  const handleMentionPickSubmit = useCallback(
    (item: DefaultReactSuggestionItem) => {
      const members = mentionMembersRef.current ?? []
      const picked = members.find((m) => m.label === item.title)
      if (!picked) return
      const existing = extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[])
      if (existing.includes(picked.id)) return
      editor.insertInlineContent(
        [
          { type: 'mention', props: { userId: String(picked.id), label: picked.label } },
          ' ',
        ] as Parameters<typeof editor.insertInlineContent>[0],
      )
    },
    [editor],
  )

  const renderMentionMenu = useCallback(
    (menuProps: SuggestionMenuProps<DefaultReactSuggestionItem>) => (
      <MentionMemberSuggestionMenu
        {...menuProps}
        searchPlaceholder={mentionSearchPh}
        noMatchesText={mentionNoMatchPh}
        loadingText={mentionLoadingPh}
        resolveMember={(label) => (mentionMembersRef.current ?? []).find((m) => m.label === label)}
        checkPiBridgeOnMention={checkPiBridgeOnMention}
      />
    ),
    [mentionSearchPh, mentionNoMatchPh, mentionLoadingPh, checkPiBridgeOnMention],
  )

  const handleStructuredMentionPick = useCallback(
    (item: StructuredSuggestionItem) => {
      if (item.kind === 'member') {
        const existing = extractMentionIdsFromBlocks(editor.document as unknown as AnyBlock[])
        if (existing.includes(item.memberId)) return
        editor.insertInlineContent(
          [createMemberMentionInline(item.memberId, item.label), ' '] as Parameters<
            typeof editor.insertInlineContent
          >[0],
        )
        return
      }
      // 文档引用只写稳定 ID 路由；标题仅作为当次插入的可读链接文本。
      editor.insertInlineContent(
        [createProjectDocumentLinkInline(item.projectId, item.documentId, item.label), ' '] as Parameters<
          typeof editor.insertInlineContent
        >[0],
      )
    },
    [editor],
  )

  const renderStructuredMentionMenu = useCallback(
    (menuProps: SuggestionMenuProps<StructuredSuggestionItem>) => (
      <StructuredMentionSuggestionMenu
        {...menuProps}
        loadingText={mentionLoadingPh}
        noMatchesText={mentionNoMatchPh}
      />
    ),
    [mentionLoadingPh, mentionNoMatchPh],
  )

  const getStructuredMentionItems = useCallback(async (query: string): Promise<StructuredSuggestionItem[]> => {
    const memberItems: StructuredSuggestionItem[] = (mentionMembersRef.current ?? []).map((member) => ({
      kind: 'member',
      memberId: member.id,
      label: member.label,
      title: member.label,
      group: mentionMembersGroup,
      aliases: [member.label.toLowerCase()],
      onItemClick: () => {},
    }))
    const documentItems: StructuredSuggestionItem[] = (mentionDocumentsRef.current ?? []).map((document) => ({
      kind: 'document',
      documentId: document.id,
      projectId: document.projectId,
      label: document.title,
      title: document.title,
      group: mentionDocumentsGroup,
      aliases: [document.title.toLowerCase()],
      onItemClick: () => {},
    }))
    return filterSuggestionItems([...memberItems, ...documentItems], query)
  }, [mentionDocumentsGroup, mentionMembersGroup])

  return (
    <DocumentImageContext.Provider value={documentImageContext}>
      <div ref={editorRootRef} className="bn-mermaid-editor-root">
      <BlockNoteView
        editor={editor}
        editable={editable}
        onChange={emitDocumentToVue}
        onBlur={handleBlur}
        onFocus={handleFocus}
        theme="light"
        slashMenu={false}
        // Keep the slash menu and table handles, but hide BlockNote's two
        // line-start controls (add block + drag handle).
        sideMenu={false}
        formattingToolbar={false}
        linkToolbar={false}
        filePanel={false}
        tableHandles={blockChromeOn}
        emojiPicker={false}
        comments={false}
      >
        {documentIdResolved == null && blockChromeOn ? (
          <SuggestionMenuController
            triggerCharacter="/"
            floatingUIOptions={taskDescriptionSuggestionMenuOptions}
          />
        ) : null}
        {mentionDocuments !== undefined ? (
          <SuggestionMenuController<typeof getStructuredMentionItems>
            triggerCharacter="@"
            suggestionMenuComponent={renderStructuredMentionMenu}
            onItemClick={handleStructuredMentionPick}
            getItems={getStructuredMentionItems}
          />
        ) : mentionMembers !== undefined && (
          <SuggestionMenuController
            triggerCharacter="@"
            suggestionMenuComponent={renderMentionMenu}
            onItemClick={handleMentionPickSubmit}
            getItems={async (query) => {
              const members = mentionMembersRef.current ?? []
              const items: DefaultReactSuggestionItem[] = members.map((m) => ({
                title: m.label,
                aliases: [m.label.toLowerCase()],
                onItemClick: () => {},
              }))
              return filterSuggestionItems(items, query)
            }}
          />
        )}
        {documentIdResolved != null && editable ? (
          <SuggestionMenuController
            triggerCharacter="/"
            getItems={getDocumentSlashItems}
          />
        ) : null}
      </BlockNoteView>
        {documentIdResolved != null && editable ? (
          <input
            ref={documentImageFileInputRef}
            type="file"
            accept="image/*"
            aria-label={documentImageMenuLabelResolved}
            style={{ display: 'none' }}
            onChange={handleDocumentImageFileChange}
          />
        ) : null}
        {imagePasteNotice ? (
          <div className="bn-image-paste-notice" role="alert" aria-live="polite">
            {imagePasteNotice}
          </div>
        ) : null}
        <div ref={mermaidLayerRef} className="bn-mermaid-preview-layer" aria-hidden="false" />
      </div>
    </DocumentImageContext.Provider>
  )
}
