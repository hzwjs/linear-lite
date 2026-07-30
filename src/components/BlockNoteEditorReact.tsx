/** @jsxImportSource react */
import { useCallback, useEffect, useMemo, useRef } from 'react'
import '@blocknote/mantine/style.css'
import PhotoSwipe from 'photoswipe'
import { BlockNoteView } from '@blocknote/mantine'
import {
  SuggestionMenuController,
  createReactInlineContentSpec,
  useCreateBlockNote,
  type DefaultReactSuggestionItem,
  type SuggestionMenuProps,
} from '@blocknote/react'
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
import { normalizeMermaidRenderError, renderMermaidSvg } from '../utils/mermaidRenderer'
import { MentionMemberSuggestionMenu } from './MentionMemberSuggestionMenu'
import {
  StructuredMentionSuggestionMenu,
  type StructuredSuggestionItem,
} from './StructuredMentionSuggestionMenu'

// ─── Code block with language selector ─────────────────────────────────────────

type CodeBlockLanguageLoader = () => Promise<{ default: LanguageInput }>

// Keep grammar modules out of the editor's initial bundle. BlockNote requests the
// selected grammar through this map only when a code block needs highlighting.
const codeBlockLanguageLoaders: Record<string, CodeBlockLanguageLoader> = {
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
}

async function createCodeBlockHighlighter() {
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
    const loader = codeBlockLanguageLoaders[language]
    return loader == null ? Promise.resolve() : loader().then(({ default: grammar }) => loadLanguage(grammar))
  }
  return highlighter as unknown as HighlighterGeneric<any, any>
}

const codeBlockOptions = {
  // BlockNote only renders language tokens after a Shiki highlighter is supplied.
  createHighlighter: createCodeBlockHighlighter,
  supportedLanguages: {
    text:       { name: 'Plain Text' },
    javascript: { name: 'JavaScript', aliases: ['js'] },
    typescript: { name: 'TypeScript', aliases: ['ts'] },
    python:     { name: 'Python',     aliases: ['py'] },
    java:       { name: 'Java' },
    go:         { name: 'Go',         aliases: ['golang'] },
    rust:       { name: 'Rust' },
    cpp:        { name: 'C++',        aliases: ['c++'] },
    c:          { name: 'C' },
    csharp:     { name: 'C#',         aliases: ['cs'] },
    php:        { name: 'PHP' },
    ruby:       { name: 'Ruby',       aliases: ['rb'] },
    swift:      { name: 'Swift' },
    kotlin:     { name: 'Kotlin' },
    html:       { name: 'HTML' },
    css:        { name: 'CSS' },
    scss:       { name: 'SCSS' },
    sql:        { name: 'SQL' },
    json:       { name: 'JSON' },
    yaml:       { name: 'YAML',       aliases: ['yml'] },
    xml:        { name: 'XML' },
    bash:       { name: 'Bash',       aliases: ['sh', 'shell'] },
    markdown:   { name: 'Markdown',   aliases: ['md'] },
    mermaid:    { name: 'Mermaid',    aliases: ['mmd'] },
  },
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
  blockOuter.classList.add('bn-mermaid-block')
  return blockOuter
}

function removeStaleMermaidOverlays(
  root: HTMLElement,
  layer: HTMLElement,
  activeBlockIds: Set<string>
) {
  for (const outer of root.querySelectorAll<HTMLElement>('.bn-mermaid-block')) {
    const blockId = outer.dataset.id
    if (blockId && activeBlockIds.has(blockId)) continue
    outer.classList.remove('bn-mermaid-block', 'bn-mermaid-block--source')
  }
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

function syncMermaidPreviewHeight(root: HTMLElement, preview: HTMLElement) {
  const blockId = preview.dataset.blockId
  if (!blockId) return
  const host = findMermaidBlockHost(root, blockId)
  if (!host || host.classList.contains('bn-mermaid-block--source')) return
  const height = Math.max(96, Math.ceil(preview.getBoundingClientRect().height))
  root.style.setProperty('--bn-mermaid-preview-height', `${height}px`)
  const content = host.querySelector<HTMLElement>(
    '.bn-block-content[data-content-type="codeBlock"][data-language="mermaid"]'
  )
  content?.style.setProperty('--bn-mermaid-preview-height', `${height}px`)
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
  removeStaleMermaidOverlays(root, layer, activeIds)

  for (const block of mermaidBlocks) {
    const host = findMermaidBlockHost(root, block.id)
    if (!host) continue
    const preview = ensureMermaidPreview(root, layer, host, block.id, block.source)
    const zoomButton = ensureMermaidPreviewZoomButton(root, layer, host, block.id)
    host.classList.toggle(
      'bn-mermaid-block--source',
      preview.classList.contains('bn-mermaid-preview--source')
    )
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

// Schema shared across all editor instances (includes default text/link + mention,
// and a code block with a language selector)
const schema = BlockNoteSchema.create({
  blockSpecs: {
    ...defaultBlockSpecs,
    codeBlock: codeBlockWithLanguages,
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

// ─── Component ──────────────────────────────────────────────────────────────────

export interface EditorApi {
  focus: () => void
  focusAppend: () => void
  getMentionedUserIds: () => number[]
  insertMention: (userId: string, label: string) => void
}

/** Vue/veaury 可能以 `upload-file` 传入，与 React 的 `uploadFile` 并存 */
export type BlockNoteEditorReactProps = {
  /** BlockNote 存库的 JSON（Block[]）或 Markdown 文本（含以 `[` 开头的链接等） */
  initialContent?: string
  placeholder?: string
  editable?: boolean
  mentionMembers?: Array<{ id: number; label: string }>
  mentionDocuments?: Array<{ id: number; title: string; projectId: number }>
  /** Should resolve the uploaded file URL */
  uploadFile?: (file: File) => Promise<string>
  'upload-file'?: (file: File) => Promise<string>
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
}

export default function BlockNoteEditorReact(props: BlockNoteEditorReactProps) {
  const {
    initialContent,
    placeholder,
    editable = true,
    mentionMembers,
    mentionDocuments,
    uploadFile,
    onChange,
    onBlur,
    onInit,
    blockChrome = false,
  } = props

  const blockChromeOn = blockChrome === true || props['block-chrome'] === true

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

  const editorRootRef = useRef<HTMLDivElement | null>(null)
  const mermaidLayerRef = useRef<HTMLDivElement | null>(null)
  const uploadFileRef = useRef(uploadFileResolved)
  uploadFileRef.current = uploadFileResolved

  /** BlockNote 只在创建 editor 时读 options；用稳定函数 + ref 承接 Vue 侧晚到或 `upload-file` 命名的回调 */
  const blockNoteUploadFile = useCallback((file: File) => {
    const fn = uploadFileRef.current
    if (!fn) {
      return Promise.reject(new Error('uploadFile not configured'))
    }
    return fn(file)
  }, [])

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
      schema,
      uploadFile: blockNoteUploadFile,
      initialContent: parsedJsonInitial as PartialBlock<any, any, any>[] | undefined,
      // Use deprecated placeholders until dictionary approach is confirmed stable
      placeholders: placeholder ? { default: placeholder } : undefined,
    },
    [] // no deps – editor is stable for the component lifetime
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
    const timers = [
      window.setTimeout(hydrate, 0),
      window.setTimeout(hydrate, 120),
      window.setTimeout(hydrate, 500),
      window.setTimeout(hydrate, 1500),
      window.setTimeout(hydrate, 3000),
      window.setTimeout(hydrate, 5000),
      window.setTimeout(hydrate, 8000),
    ]
    const observer = new MutationObserver(queueHydrate)
    observer.observe(root, { childList: true, subtree: true })
    let intervalTicks = 0
    const interval = window.setInterval(() => {
      intervalTicks += 1
      hydrate()
      if (intervalTicks >= 24) window.clearInterval(interval)
    }, 500)
    return () => {
      if (hydrationTimer != null) window.clearTimeout(hydrationTimer)
      observer.disconnect()
      window.clearInterval(interval)
      for (const timer of timers) window.clearTimeout(timer)
    }
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
      host?.classList.add('bn-mermaid-block--source')
      preview.classList.add('bn-mermaid-preview--source')
      host?.querySelector<HTMLElement>('[contenteditable="true"]')?.focus()
    }

    const restorePreviewIfPointerLeft = (event: MouseEvent) => {
      for (const preview of root.querySelectorAll<HTMLElement>('.bn-mermaid-preview--source')) {
        const blockId = preview.dataset.blockId
        if (!blockId) continue
        const host = findMermaidBlockHost(root, blockId)
        if (!host) {
          document
            .querySelector<HTMLElement>(`.bn-block-outer[data-id="${blockId}"]`)
            ?.classList.remove('bn-mermaid-block--source')
          preview.classList.remove('bn-mermaid-preview--source')
          continue
        }
        const rect = host.getBoundingClientRect()
        const inside =
          event.clientX >= rect.left &&
          event.clientX <= rect.right &&
          event.clientY >= rect.top &&
          event.clientY <= rect.bottom
        if (!inside) {
          host.classList.remove('bn-mermaid-block--source')
          preview.classList.remove('bn-mermaid-preview--source')
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
      />
    ),
    [mentionSearchPh, mentionNoMatchPh, mentionLoadingPh],
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
    <div ref={editorRootRef} className="bn-mermaid-editor-root">
      <BlockNoteView
        editor={editor}
        editable={editable}
        onChange={emitDocumentToVue}
        onBlur={handleBlur}
        onFocus={handleFocus}
        theme="light"
        slashMenu={blockChromeOn}
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
      </BlockNoteView>
      <div ref={mermaidLayerRef} className="bn-mermaid-preview-layer" aria-hidden="false" />
    </div>
  )
}
