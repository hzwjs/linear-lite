<script setup lang="ts">
import { watch, ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import { Extension } from '@tiptap/core'
import type { Node as PmNode, ResolvedPos } from '@tiptap/pm/model'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Heading from '@tiptap/extension-heading'
import BulletList from '@tiptap/extension-bullet-list'
import ListItem from '@tiptap/extension-list-item'
import OrderedList from '@tiptap/extension-ordered-list'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { createLowlight, common } from 'lowlight'
import { createCodeBlockLinear } from '../extensions/CodeBlockLinear'
import Blockquote from '@tiptap/extension-blockquote'
import Placeholder from '@tiptap/extension-placeholder'
import { mdToHtml, htmlToMd, looksLikeMarkdownTaskList } from '../utils/editorMarkdown'
import { uploadApi } from '../services/api/upload'
import {
  FAILED_IMAGE_STATE,
  UPLOADED_IMAGE_STATE,
  UPLOADING_IMAGE_STATE,
  getEditorUploadStateSummaryFromHtml,
  serializeEditorHtmlForSave,
  validateEditorImageFile,
} from '../utils/editorImageUpload'
import { randomClientId } from '../utils/clientId'
import { useI18n } from 'vue-i18n'
import { TaskImage } from '../extensions/TaskImage'
import Mention from '@tiptap/extension-mention'
import { createMentionListRender } from '../extensions/mentionSuggestionDom'
import TiptapSlashMenu from './TiptapSlashMenu.vue'
import { Skeleton } from '@brayamvalero/vue3-skeleton'
import '@brayamvalero/vue3-skeleton/dist/style.css'
import PhotoSwipe from 'photoswipe'
import 'photoswipe/style.css'

const lowlight = createLowlight(common)

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    minHeight?: number
    /** 传入则启用 @ 成员建议（任务评论等）；不传则不注册 Mention */
    mentionMembers?: Array<{ id: number; label: string }>
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 120,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'upload-state-change': [state: { hasPending: boolean; hasFailed: boolean }]
  blur: []
  ready: []
}>()

const { t } = useI18n()
const resolvedPlaceholder = computed(() => props.placeholder || t('editor.placeholder'))

const slashMenuOpen = ref(false)
const slashMenuPos = ref({ left: 0, top: 0 })
const slashMenuSelection = ref<{ from: number; to: number } | null>(null)
const editorWrapRef = ref<HTMLElement | null>(null)
const loadingImageOverlays = ref<Array<{ key: string; left: number; top: number; width: number; height: number }>>([])
const uploadStateByLocalId = new Map<
  string,
  { file: File; objectUrl: string }
>()
let loadingOverlayFrame = 0
let photoSwipeInstance: PhotoSwipe | null = null

function isPlainRemoteImage(img: HTMLImageElement) {
  return !img.closest('.task-image-node') && !img.classList.contains('ProseMirror-separator')
}

function applyRemoteImageLoadingStyle(img: HTMLImageElement) {
  if (!isPlainRemoteImage(img)) return
  if (img.complete && img.naturalWidth > 0) {
    img.classList.remove('editor-remote-image-loading')
    return
  }
  img.classList.add('editor-remote-image-loading')
}

function syncRemoteImageOverlays(dom?: HTMLElement) {
  const root = dom ?? editor.value?.view.dom
  const wrap = editorWrapRef.value
  if (!(root instanceof HTMLElement) || !(wrap instanceof HTMLElement)) {
    loadingImageOverlays.value = []
    return
  }

  const wrapRect = wrap.getBoundingClientRect()
  const overlays = Array.from(root.querySelectorAll('img'))
    .filter((img): img is HTMLImageElement => img instanceof HTMLImageElement && isPlainRemoteImage(img))
    .flatMap((img, index) => {
      applyRemoteImageLoadingStyle(img)
      if (img.complete && img.naturalWidth > 0) return []
      const rect = img.getBoundingClientRect()
      const width = Math.max(rect.width, 120)
      const height = Math.max(rect.height, 72)
      return [{
        key: `${img.currentSrc || img.src || index}-${index}`,
        left: rect.left - wrapRect.left + wrap.scrollLeft,
        top: rect.top - wrapRect.top + wrap.scrollTop,
        width,
        height,
      }]
    })

  loadingImageOverlays.value = overlays
}

function scheduleRemoteImageOverlaySync(dom?: HTMLElement) {
  if (loadingOverlayFrame) cancelAnimationFrame(loadingOverlayFrame)
  loadingOverlayFrame = requestAnimationFrame(() => {
    loadingOverlayFrame = 0
    syncRemoteImageOverlays(dom)
  })
}

function destroyPhotoSwipe() {
  photoSwipeInstance?.destroy()
  photoSwipeInstance = null
}

function openImageGallery(clickedImage: HTMLImageElement, dom: HTMLElement) {
  const images = Array.from(dom.querySelectorAll('img'))
    .filter((img): img is HTMLImageElement => img instanceof HTMLImageElement && isPlainRemoteImage(img))
  const index = images.indexOf(clickedImage)
  if (index < 0) return

  destroyPhotoSwipe()
  photoSwipeInstance = new PhotoSwipe({
    dataSource: images.map((img) => ({
      src: img.currentSrc || img.src,
      width: img.naturalWidth || Math.max(Math.round(img.getBoundingClientRect().width), 1200),
      height: img.naturalHeight || Math.max(Math.round(img.getBoundingClientRect().height), 800),
      alt: img.alt || '',
    })),
    index,
    showHideAnimationType: 'zoom',
    bgOpacity: 0.92,
    wheelToZoom: true,
    initialZoomLevel: 'fit',
    secondaryZoomLevel: 1.5,
    maxZoomLevel: 4,
  })
  photoSwipeInstance.init()
}

function openSlashMenu(left: number, top: number, from: number, to: number) {
  slashMenuPos.value = { left, top }
  slashMenuSelection.value = { from, to }
  slashMenuOpen.value = true
}

/** 当前段内「逻辑行」起点（块首或上一个 hardBreak 之后），用于斜杠菜单仅在行首类位置触发 */
function lineStartOffsetInTextblock(parent: PmNode, offsetInParent: number): number {
  if (!parent.isTextblock) return 0
  let lineStart = 0
  let pos = 0
  for (let i = 0; i < parent.childCount; i++) {
    const child = parent.child(i)
    const end = pos + child.nodeSize
    if (end > offsetInParent) break
    if (child.type.name === 'hardBreak') lineStart = end
    pos = end
  }
  return lineStart
}

/** 与常见块编辑器一致：仅在块内当前行的行首（仅含空白）输入 `/` 时打开斜杠菜单 */
function isSlashTriggerPosition($from: ResolvedPos): boolean {
  const parent = $from.parent
  if (!parent.isTextblock) return false
  const off = $from.parentOffset
  const lineStart = lineStartOffsetInTextblock(parent, off)
  const before = parent.textBetween(lineStart, off, '', '\ufffc')
  return /^\s*$/.test(before)
}

const mentionExtension =
  props.mentionMembers !== undefined
    ? Mention.configure({
        HTMLAttributes: { class: 'editor-mention-node' },
        suggestion: {
          allowedPrefixes: null,
          items: ({ query }) => {
            const list = props.mentionMembers ?? []
            const q = (query ?? '').toLowerCase()
            return list
              .filter((m) => m.label.toLowerCase().includes(q))
              .slice(0, 24)
              .map((m) => ({ id: String(m.id), label: m.label }))
          },
          render: () => createMentionListRender(),
        },
      })
    : null

const slashMenuExtension = Extension.create({
  name: 'slashMenu',
  addKeyboardShortcuts() {
    return {
      '/': () => {
        const { view, state } = this.editor
        if (view.composing) return false
        const { $from, from, to } = state.selection
        if (from !== to) return false
        if ($from.parent.type.name === 'codeBlock') return false
        if (!isSlashTriggerPosition($from)) return false
        const coords = view.coordsAtPos(from)
        openSlashMenu(coords.left, coords.bottom, from, to)
        return true
      },
      /** 任意位置打开块菜单（与 Notion 等产品的 Cmd/Ctrl+K 一致） */
      'Mod-k': () => {
        const { view, state } = this.editor
        if (view.composing) return false
        const { $from, from, to } = state.selection
        if ($from.parent.type.name === 'codeBlock') return false
        const coords = view.coordsAtPos(from)
        openSlashMenu(coords.left, coords.bottom, from, to)
        return true
      },
    }
  },
})

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      codeBlock: false,
      heading: false,
      blockquote: false,
      bulletList: false,
      listItem: false,
      orderedList: false,
    }),
    Heading.configure({ levels: [1, 2, 3] }),
    Image.configure({ inline: true }),
    BulletList,
    ListItem,
    OrderedList,
    TaskList,
    TaskItem.configure({ nested: true }),
    TaskImage.configure({
      inline: true,
      onRetry: (localId: string) => {
        void retryImageUpload(localId)
      },
      onRemove: (localId: string) => {
        removeImageByLocalId(localId)
      },
      getUploadingLabel: () => t('attachments.uploading'),
      getFailedLabel: () => t('attachments.uploadFailed'),
      getRetryLabel: () => t('common.retry'),
      getRemoveLabel: () => t('common.remove'),
    } as any),
    createCodeBlockLinear({ lowlight, defaultLanguage: 'bash' }),
    Blockquote,
    Placeholder.configure({
      placeholder: () => resolvedPlaceholder.value,
      emptyEditorClass: 'is-editor-empty',
    }),
    slashMenuExtension,
    ...(mentionExtension ? [mentionExtension] : []),
  ],
  content: mdToHtml(props.modelValue ?? ''),
  onUpdate: ({ editor: ed }: { editor: { getHTML: () => string } }) => {
    const html = ed.getHTML()
    emit('update:modelValue', htmlToMd(serializeEditorHtmlForSave(html)))
    emit('upload-state-change', getEditorUploadStateSummaryFromHtml(html))
  },
})

watch(
  editor,
  (e) => {
    if (e) emit('ready')
    if (!e?.view?.dom) return
    const dom = e.view.dom
    const onBlur = () => emit('blur')
    dom.addEventListener('blur', onBlur)

    const onImgLoadOrError = (ev: Event) => {
      const img = ev.target as HTMLImageElement
      if (img?.tagName !== 'IMG') return
      if (isPlainRemoteImage(img)) {
        img.classList.remove('editor-remote-image-loading')
        scheduleRemoteImageOverlaySync(dom)
        return
      }
      const wrap = img.closest?.('.task-image-node')
      if (!(wrap instanceof HTMLElement)) return
      const uploadState = wrap.getAttribute('data-upload-state')
      if (uploadState === 'loading-remote') {
        wrap.setAttribute('data-upload-state', UPLOADED_IMAGE_STATE)
        wrap.classList.remove('task-image-node--loading')
        const placeholder = wrap.querySelector('.task-image-node__placeholder')
        if (placeholder) placeholder.remove()
        img.style.display = ''
        img.removeAttribute('style')
        return
      }
      wrap.classList.remove('task-image-node--loading')
    }
    dom.addEventListener('load', onImgLoadOrError, true)
    dom.addEventListener('error', onImgLoadOrError, true)
    scheduleRemoteImageOverlaySync(dom)

    const remoteImageObserver = new MutationObserver(() => {
      scheduleRemoteImageOverlaySync(dom)
    })
    remoteImageObserver.observe(dom, { childList: true, subtree: true, attributes: true })

    const onTaskImageAction = (ev: Event) => {
      const btn = (ev.target as HTMLElement)?.closest?.('.task-image-node__action[data-action][data-local-id]')
      if (!btn) return
      ev.preventDefault()
      const action = btn.getAttribute('data-action')
      const localId = btn.getAttribute('data-local-id') ?? ''
      const ext = e.extensionManager.extensions.find((x) => x.name === 'taskImage')
      if (action === 'retry') (ext?.options as { onRetry?: (id: string) => void })?.onRetry?.(localId)
      if (action === 'remove') (ext?.options as { onRemove?: (id: string) => void })?.onRemove?.(localId)
    }
    dom.addEventListener('click', onTaskImageAction)

    const onPlainImageClick = (ev: Event) => {
      const img = ev.target as HTMLImageElement
      if (img?.tagName !== 'IMG' || !isPlainRemoteImage(img)) return
      ev.preventDefault()
      openImageGallery(img, dom)
    }
    dom.addEventListener('click', onPlainImageClick, true)

    const onWindowResize = () => scheduleRemoteImageOverlaySync(dom)
    window.addEventListener('resize', onWindowResize)
    nextTick(() => scheduleRemoteImageOverlaySync(dom))

    return () => {
      dom.removeEventListener('blur', onBlur)
      dom.removeEventListener('load', onImgLoadOrError, true)
      dom.removeEventListener('error', onImgLoadOrError, true)
      dom.removeEventListener('click', onTaskImageAction)
      dom.removeEventListener('click', onPlainImageClick, true)
      window.removeEventListener('resize', onWindowResize)
      remoteImageObserver.disconnect()
      if (loadingOverlayFrame) {
        cancelAnimationFrame(loadingOverlayFrame)
        loadingOverlayFrame = 0
      }
      loadingImageOverlays.value = []
    }
  },
  { immediate: true }
)

function closeSlashMenu() {
  slashMenuOpen.value = false
  slashMenuSelection.value = null
}

function focusEditor() {
  editor.value?.commands.focus()
}

function getMentionedUserIdsFromDoc(): number[] {
  if (!editor.value) return []
  const ids: number[] = []
  editor.value.state.doc.descendants((node) => {
    if (node.type.name === 'mention') {
      const raw = node.attrs.id as string | null | undefined
      if (raw == null) return true
      const n = parseInt(String(raw), 10)
      if (Number.isFinite(n)) ids.push(n)
    }
    return true
  })
  return [...new Set(ids)]
}

defineExpose({ focus: focusEditor, getMentionedUserIdsFromDoc })

function onWrapMouseDown(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (!editorWrapRef.value?.contains(target)) return
  if (target.closest('[data-slash-menu]')) return
  if (target.closest('.code-block-linear')) return
  editor.value?.commands.focus()
}

function onSlashMenuClose() {
  closeSlashMenu()
  editor.value?.commands.focus()
}

function handleClickOutside(e: MouseEvent) {
  if (!slashMenuOpen.value) return
  const target = e.target as Node
  const inEditor = editorWrapRef.value?.contains(target)
  const inMenu = document.querySelector('[data-slash-menu]')?.contains(target)
  const inMention = (target as HTMLElement).closest?.('.tiptap-mention-suggestion')
  if (!inEditor && !inMenu && !inMention) closeSlashMenu()
}

function revokePreviewUrl(localId: string) {
  const entry = uploadStateByLocalId.get(localId)
  if (!entry) return
  URL.revokeObjectURL(entry.objectUrl)
}

function findImageNodePosition(localId: string): number | null {
  if (!editor.value) return null
  let found: number | null = null
  editor.value.state.doc.descendants((node, pos) => {
    if (node.type.name === 'taskImage' && node.attrs.localId === localId) {
      found = pos
      return false
    }
    return true
  })
  return found
}

function updateImageNodeByLocalId(localId: string, attrs: Record<string, unknown>) {
  if (!editor.value) return false
  const pos = findImageNodePosition(localId)
  if (pos == null) return false
  const node = editor.value.state.doc.nodeAt(pos)
  if (!node) return false
  const tr = editor.value.state.tr.setNodeMarkup(pos, undefined, {
    ...node.attrs,
    ...attrs,
  })
  editor.value.view.dispatch(tr)
  return true
}

function removeImageByLocalId(localId: string) {
  if (!editor.value) return
  const pos = findImageNodePosition(localId)
  if (pos == null) return
  const node = editor.value.state.doc.nodeAt(pos)
  if (!node) return
  revokePreviewUrl(localId)
  uploadStateByLocalId.delete(localId)
  const tr = editor.value.state.tr.delete(pos, pos + node.nodeSize)
  editor.value.view.dispatch(tr)
}

function insertPreviewImage(file: File, pos?: number) {
  if (!editor.value) return { localId: '', nextPos: pos }
  const localId = randomClientId()
  const objectUrl = URL.createObjectURL(file)
  uploadStateByLocalId.set(localId, { file, objectUrl })
  const chain = editor.value.chain().focus()
  if (typeof pos === 'number') {
    chain.setTextSelection(pos)
  }
  chain
    .insertContent({
      type: 'taskImage',
      attrs: {
        src: objectUrl,
        alt: file.name || t('taskImage.altFallback'),
        localId,
        uploadState: UPLOADING_IMAGE_STATE,
        errorMessage: null,
      },
    })
    .insertContent(' ')
    .run()
  return { localId, nextPos: editor.value.state.selection.from }
}

async function startImageUpload(localId: string) {
  const entry = uploadStateByLocalId.get(localId)
  if (!entry) return
  updateImageNodeByLocalId(localId, {
    uploadState: UPLOADING_IMAGE_STATE,
    errorMessage: null,
  })
  try {
    const uploaded = await uploadApi.uploadImage(entry.file)
    
    const img = new window.Image()
    const handleLoad = () => {
      updateImageNodeByLocalId(localId, {
        src: uploaded.url,
        uploadState: UPLOADED_IMAGE_STATE,
        errorMessage: null,
      })
      revokePreviewUrl(localId)
      uploadStateByLocalId.delete(localId)
    }
    img.onload = handleLoad
    img.onerror = handleLoad
    img.src = uploaded.url
  } catch (error) {
    updateImageNodeByLocalId(localId, {
      uploadState: FAILED_IMAGE_STATE,
      errorMessage: error instanceof Error ? error.message : t('attachments.uploadFailed'),
    })
  }
}

async function retryImageUpload(localId: string) {
  if (!uploadStateByLocalId.has(localId)) return
  await startImageUpload(localId)
}

function queueImageUpload(localId: string) {
  void startImageUpload(localId)
}

function handleImageFiles(files: File[], pos?: number) {
  let nextPos = pos
  for (const file of files) {
  const validation = validateEditorImageFile(file)
  if (!validation.ok) {
      continue
  }
    const inserted = insertPreviewImage(file, nextPos)
    nextPos = inserted.nextPos
    if (inserted.localId) queueImageUpload(inserted.localId)
  }
}

function getClipboardImageFiles(event: ClipboardEvent): File[] {
  const items = Array.from(event.clipboardData?.items ?? [])
  return items
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file != null)
    .filter((file) => file.type.startsWith('image/'))
}

function getDroppedImageFiles(event: DragEvent): File[] {
  return Array.from(event.dataTransfer?.files ?? []).filter((file) =>
    file.type.startsWith('image/')
  )
}

async function handlePaste(event: ClipboardEvent) {
  const files = getClipboardImageFiles(event)
  if (files.length > 0) {
    event.preventDefault()
    handleImageFiles(files)
    return
  }

  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (!text || !looksLikeMarkdownTaskList(text)) return

  event.preventDefault()
  editor.value?.chain().focus().insertContent(mdToHtml(text)).run()
}

async function handleDrop(event: DragEvent) {
  const files = getDroppedImageFiles(event)
  if (files.length === 0) return
  event.preventDefault()
  const pos = editor.value?.view.posAtCoords({
    left: event.clientX,
    top: event.clientY,
  })?.pos
  handleImageFiles(files, pos)
}

onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside)
})
onUnmounted(() => {
  document.removeEventListener('mousedown', handleClickOutside)
  destroyPhotoSwipe()
  if (loadingOverlayFrame) cancelAnimationFrame(loadingOverlayFrame)
  for (const [localId] of uploadStateByLocalId) {
    revokePreviewUrl(localId)
  }
  uploadStateByLocalId.clear()
})

watch(
  () => props.modelValue,
  (newVal) => {
    if (!editor.value) return
    const currentMd = htmlToMd(editor.value.getHTML())
    const newNorm = (newVal ?? '').trim()
    if (currentMd.trim() !== newNorm) {
      editor.value.commands.setContent(mdToHtml(newVal ?? ''), false)
    }
  }
)
</script>

<template>
  <div
    ref="editorWrapRef"
    class="tiptap-editor-wrap"
    :style="{ minHeight: `${minHeight}px` }"
    @mousedown="onWrapMouseDown"
    @dragover.prevent
    @paste.capture="handlePaste"
    @drop.capture="handleDrop"
  >
    <div
      v-if="!editor"
      class="tiptap-editor-placeholder"
      :style="{ minHeight: `${minHeight}px` }"
      aria-hidden="true"
    >
      <span class="tiptap-editor-placeholder__line" />
      <span class="tiptap-editor-placeholder__line" />
      <span class="tiptap-editor-placeholder__line tiptap-editor-placeholder__line--short" />
    </div>
    <EditorContent v-else :editor="editor" />
    <div v-if="loadingImageOverlays.length" class="editor-image-skeleton-layer" aria-hidden="true">
      <Skeleton
        v-for="overlay in loadingImageOverlays"
        :key="overlay.key"
        class="editor-image-skeleton"
        :style="{
          left: `${overlay.left}px`,
          top: `${overlay.top}px`,
          width: `${overlay.width}px`,
          height: `${overlay.height}px`,
        }"
        :loading="true"
        :base-color="'rgba(226, 232, 240, 0.9)'"
        :highlight-color="'rgba(255, 255, 255, 0.85)'"
        :border-radius="12"
      />
    </div>
    <TiptapSlashMenu
      :visible="slashMenuOpen"
      :position="slashMenuPos"
      :editor="editor ?? null"
      :selection="slashMenuSelection"
      @close="onSlashMenuClose"
      @select="onSlashMenuClose"
    />
  </div>
</template>

<style scoped>
.tiptap-editor-wrap {
  background: var(--color-bg-subtle);
  cursor: text;
  padding-top: 0;
  position: relative;
}

.tiptap-editor-placeholder {
  width: 100%;
  padding: 0 2px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  justify-content: center;
}

.tiptap-editor-placeholder__line {
  display: block;
  height: 12px;
  border-radius: 6px;
  background: var(--color-border);
  opacity: 0.6;
  animation: tiptap-editor-placeholder-shimmer 1.2s ease-in-out infinite;
}

.tiptap-editor-placeholder__line--short {
  width: 60%;
}

@keyframes tiptap-editor-placeholder-shimmer {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 0.85; }
}
.tiptap-editor-wrap :deep(.tiptap) {
  outline: none;
  color: var(--color-text-primary);
  font-size: var(--font-size-body);
  padding: 0;
  min-height: 2.5em;
}
.tiptap-editor-wrap :deep(.tiptap p:first-child) {
  margin-top: 0;
}
.tiptap-editor-wrap :deep(.tiptap > *:first-child) {
  margin-top: 0;
}
.tiptap-editor-wrap :deep(.tiptap.is-editor-empty) {
  min-height: 2.5em;
}
.tiptap-editor-wrap :deep(.tiptap.is-editor-empty p:first-child::before) {
  color: var(--color-text-muted);
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}
.tiptap-editor-wrap :deep(.tiptap p.is-editor-empty:first-child::before) {
  color: var(--color-text-muted);
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}

/* 任务描述图片：静态 HTML 渲染，首帧即有占位，与文字同时出现 */
.tiptap-editor-wrap :deep(.tiptap .task-image-node) {
  display: inline-block;
  vertical-align: top;
  margin: 0.35rem 0.4rem 0.35rem 0;
  max-width: 100%;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node.task-image-node--loading) {
  background: transparent;
  animation: none;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__frame) {
  position: relative;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  border-radius: 12px;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node--loading .task-image-node__frame) {
  display: inline-block;
  min-width: 96px;
  min-height: 64px;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__image) {
  display: block;
  max-width: 100%;
  height: auto;
}
.tiptap-editor-wrap :deep(.tiptap img.editor-remote-image-loading) {
  display: inline-block;
  max-width: 100%;
  min-width: 120px;
  min-height: 72px;
  border-radius: 12px;
  opacity: 0;
  vertical-align: top;
}
.tiptap-editor-wrap :deep(.tiptap img:not(.ProseMirror-separator)) {
  display: inline-block;
  max-width: 100%;
  height: auto;
  border-radius: 12px;
  cursor: zoom-in;
  vertical-align: top;
}
.editor-image-skeleton-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 1;
}
.editor-image-skeleton {
  position: absolute;
}
@keyframes task-image-placeholder-pulse {
  0%, 100% { opacity: 0.92; }
  50% { opacity: 0.65; }
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__status) {
  position: absolute;
  left: 6px;
  bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  padding: 5px 7px;
  max-width: calc(100% - 12px);
  border-radius: 999px;
  background: color-mix(in srgb, rgba(15, 23, 42, 0.82) 72%, var(--color-bg-base));
  backdrop-filter: blur(8px);
  border: 1px solid color-mix(in srgb, var(--color-border-subtle) 80%, transparent);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.18);
  color: var(--color-text-primary);
  font-size: 11px;
  line-height: 1.2;
  z-index: 1;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__status.failed) {
  color: var(--color-danger, #b42318);
  border-color: color-mix(in srgb, var(--color-danger, #b42318) 20%, transparent);
  background: color-mix(in srgb, var(--color-danger, #b42318) 10%, var(--color-bg-base));
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__status-main) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__action) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 11px;
}
.tiptap-editor-wrap :deep(.tiptap .task-image-node__action:hover) {
  text-decoration: underline;
}

.tiptap-editor-wrap :deep(.tiptap h1) {
  margin: 1.35em 0 0.5em;
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1.24;
  letter-spacing: -0.025em;
}

.tiptap-editor-wrap :deep(.tiptap h1:first-child) {
  margin-top: 0.1em;
}

.tiptap-editor-wrap :deep(.tiptap h2) {
  margin: 1.2em 0 0.45em;
  font-size: 1.25rem;
  font-weight: 650;
  line-height: 1.3;
  letter-spacing: -0.018em;
}

.tiptap-editor-wrap :deep(.tiptap h3) {
  margin: 1.05em 0 0.4em;
  font-size: 1.05rem;
  font-weight: 650;
  line-height: 1.35;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"]) {
  list-style: none;
  padding-left: 1.5em;
  margin: 0.5em 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li) {
  display: flex;
  align-items: center;
  gap: 0.55em;
  list-style: none;
  margin: 0.2em 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li::marker) {
  content: '';
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > label) {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  margin: 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > div) {
  flex: 1;
  min-width: 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > div > p) {
  margin: 0;
}

/* Blockquote：左边框 + 左内边距，与正文区分 */
.tiptap-editor-wrap :deep(.tiptap blockquote) {
  margin: 0.5em 0;
  padding-left: 1em;
  border-left: 4px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}

.tiptap-editor-wrap :deep(.tiptap span[data-type='mention']) {
  border-radius: 4px;
  padding: 0 3px;
  background: color-mix(in srgb, var(--color-accent, #5e6ad2) 18%, transparent);
  color: var(--color-accent, #5e6ad2);
  font-weight: 500;
}

</style>

<style>
/* suggestion 挂在 body 上，不能 scoped */
.tiptap-mention-suggestion {
  min-width: 200px;
  max-width: 280px;
  max-height: 220px;
  overflow: auto;
  padding: 4px;
  border-radius: 8px;
  border: 1px solid var(--color-border, #e5e5e5);
  background: var(--color-bg-elevated, #fff);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}
.tiptap-mention-suggestion-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  color: var(--color-text, #111);
}
.tiptap-mention-suggestion-item:hover,
.tiptap-mention-suggestion-item--active {
  background: var(--color-bg-hover, rgba(0, 0, 0, 0.06));
}
.tiptap-mention-suggestion-empty {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--color-text-muted, #888);
}
</style>
