<script setup lang="ts">
import { Archive, Check, Clock3, Copy, FileDown, Loader2, MoreHorizontal, RefreshCw, Star, TriangleAlert } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import StructuredDocumentEditor from '../StructuredDocumentEditor.vue'
import DocumentMinimap from './DocumentMinimap.vue'
import { documentApi } from '../../services/api/documents'
import type { DocumentSaveState, ProjectDocument, ProjectDocumentTreeNode } from '../../types/document'
import { formatRelativeTime } from '../../utils/beijingTime'
import { startDocumentPdfExport } from '../../utils/documentPdfExport'

const DOCUMENT_ATTACHMENT_PATH = /^\/api\/project-documents\/(\d+)\/attachments\/(\d+)\/download$/

const props = withDefaults(defineProps<{
  document: ProjectDocument
  treeNodes: ProjectDocumentTreeNode[]
  saveState: DocumentSaveState
  conflictVersion: number | null
  mentionMembers: Array<{ id: number; label: string }>
  mentionDocuments: Array<{ id: number; title: string; projectId: number }>
  favoritePending?: boolean
}>(), {
  favoritePending: false
})

const emit = defineEmits<{
  updateTitle: [title: string]
  updateContent: [content: string]
  archive: []
  history: []
  reload: []
  retry: []
  toggleFavorite: []
}>()

const { t } = useI18n()
const copied = ref(false)
const exportingPdf = ref(false)
const moreOpen = ref(false)
const bodyEditorRef = ref<InstanceType<typeof StructuredDocumentEditor> | null>(null)
const moreMenuRef = ref<HTMLElement | null>(null)
const documentPageRef = ref<HTMLElement | null>(null)
const documentBodyRef = ref<HTMLElement | null>(null)
const documentEditorSurfaceRef = ref<HTMLElement | null>(null)
const attachmentDownloadError = ref('')
const attachmentDownloadPending = ref(false)
const attachmentDeletePending = new Set<number>()
const attachmentImageStates = new Map<HTMLImageElement, 'loading' | 'error'>()
const attachmentOverlays = ref<Array<{
  key: string
  type: 'delete' | 'image'
  href?: string
  attachmentId?: number
  state?: 'loading' | 'error'
  style: Record<string, string>
}>>([])
const relativeTimeClock = ref(Date.now())
const attachmentImageObjectUrls = new Map<HTMLImageElement, string>()
const pendingAttachmentImages = new WeakSet<HTMLImageElement>()
let attachmentImageObserver: MutationObserver | null = null
let attachmentImageGeneration = 0
let relativeTimeTimer: ReturnType<typeof setInterval> | null = null
let restorePdfExportState: (() => void) | null = null

function matchDocumentAttachmentPath(value: string | null): RegExpMatchArray | null {
  try {
    const url = new URL(value ?? '', window.location.href)
    if (url.origin !== window.location.origin || url.search !== '' || url.hash !== '') return null
    return url.pathname.match(DOCUMENT_ATTACHMENT_PATH)
  } catch {
    return null
  }
}

function matchDocumentAttachmentEvent(event: MouseEvent): RegExpMatchArray | null {
  if (!(event.target instanceof Element)) return null
  const anchor = event.target.closest<HTMLAnchorElement>('a[href]')
  return anchor == null ? null : matchDocumentAttachmentPath(anchor.getAttribute('href'))
}

function onMoreMenuOutsideClick(event: MouseEvent) {
  const menu = moreMenuRef.value
  if (menu == null || menu.contains(event.target as Node)) return
  moreOpen.value = false
}

// BlockNote owns the surface DOM; controls and image states are positioned in a sibling overlay.
function syncAttachmentOverlays() {
  const body = documentBodyRef.value
  const surface = documentEditorSurfaceRef.value
  if (body == null || surface == null) return
  const bodyRect = body.getBoundingClientRect()
  const overlays: typeof attachmentOverlays.value = []

  if (props.saveState !== 'conflict') for (const anchor of surface.querySelectorAll<HTMLAnchorElement>('a[href]')) {
    const href = anchor.getAttribute('href')
    const match = matchDocumentAttachmentPath(href)
    if (match == null || Number(match[1]) !== props.document.id) continue
    const rect = anchor.getBoundingClientRect()
    overlays.push({
      key: `delete-${href}`,
      type: 'delete',
      href: href ?? '',
      attachmentId: Number(match[2]),
      style: {
        top: `${rect.top - bodyRect.top + (rect.height - 44) / 2}px`,
        left: `${rect.right - bodyRect.left - 52}px`
      }
    })
  }

  surface.querySelectorAll<HTMLImageElement>('img[src]').forEach((image, index) => {
    const match = matchDocumentAttachmentPath(image.getAttribute('src'))
    const state = attachmentImageStates.get(image)
    if (match == null || Number(match[1]) !== props.document.id || state == null) return
    const rect = image.getBoundingClientRect()
    overlays.push({
      key: `image-${match[2]}-${index}`,
      type: 'image',
      state,
      style: {
        top: `${rect.top - bodyRect.top}px`,
        left: `${rect.left - bodyRect.left}px`,
        width: `${Math.max(rect.width, 160)}px`,
        height: `${Math.max(rect.height, 96)}px`
      }
    })
  })

  attachmentOverlays.value = overlays
}

function revokeAttachmentImageUrls() {
  for (const objectUrl of attachmentImageObjectUrls.values()) URL.revokeObjectURL(objectUrl)
  attachmentImageObjectUrls.clear()
}

function setAttachmentImageState(image: HTMLImageElement, state: 'loading' | 'loaded' | 'error') {
  if (state === 'loaded') attachmentImageStates.delete(image)
  else attachmentImageStates.set(image, state)
  syncAttachmentOverlays()
}

async function hydrateDocumentAttachments() {
  const generation = attachmentImageGeneration
  await nextTick()
  const body = documentBodyRef.value
  const surface = documentEditorSurfaceRef.value
  if (body == null || surface == null || generation !== attachmentImageGeneration) return

  for (const [image, objectUrl] of attachmentImageObjectUrls) {
    if (body.contains(image)) continue
    URL.revokeObjectURL(objectUrl)
    attachmentImageObjectUrls.delete(image)
  }

  for (const image of surface.querySelectorAll<HTMLImageElement>('img[src]')) {
    const match = matchDocumentAttachmentPath(image.getAttribute('src'))
    if (match == null || attachmentImageObjectUrls.has(image) || pendingAttachmentImages.has(image)) continue
    const documentId = Number(match[1])
    const attachmentId = Number(match[2])
    if (documentId !== props.document.id) {
      attachmentDownloadError.value = t('documents.attachmentDocumentMismatch')
      continue
    }

    pendingAttachmentImages.add(image)
    setAttachmentImageState(image, 'loading')
    let blobAssigned = false
    const markLoaded = () => {
      if (blobAssigned) setAttachmentImageState(image, 'loaded')
    }
    const markFailed = () => {
      if (blobAssigned) setAttachmentImageState(image, 'error')
    }
    image.addEventListener('load', markLoaded, { once: true })
    image.addEventListener('error', markFailed, { once: true })
    try {
      const blob = await documentApi.getAttachmentBlob(documentId, attachmentId)
      if (generation !== attachmentImageGeneration || !surface.contains(image)) continue
      // BlockNote 的原始 img 请求不会携带 JWT；只把精确附件路径替换为当前会话的 Blob URL。
      const objectUrl = URL.createObjectURL(blob)
      attachmentImageObjectUrls.set(image, objectUrl)
      image.src = objectUrl
      blobAssigned = true
      if (image.complete && image.naturalWidth > 0) markLoaded()
    } catch {
      if (generation === attachmentImageGeneration) {
        setAttachmentImageState(image, 'error')
        attachmentDownloadError.value = t('attachments.downloadFailed')
      }
    } finally {
      pendingAttachmentImages.delete(image)
    }
  }
}

onMounted(async () => {
  window.document.addEventListener('click', onMoreMenuOutsideClick, true)
  // 页面停留期间按分钟刷新相对时间，避免“最近更新”文案逐渐失真。
  relativeTimeTimer = window.setInterval(() => { relativeTimeClock.value = Date.now() }, 60_000)
  await nextTick()
  const surface = documentEditorSurfaceRef.value
  if (surface == null) return
  // BlockNote 会在父组件 mounted 后继续异步构建图片节点，监听新增节点后再执行精确路径水合。
  attachmentImageObserver = new MutationObserver(() => {
    syncAttachmentOverlays()
    void hydrateDocumentAttachments()
  })
  attachmentImageObserver.observe(surface, { childList: true, subtree: true })
  syncAttachmentOverlays()
  void hydrateDocumentAttachments()
})
watch(
  () => [props.document.id, props.document.content] as const,
  ([documentId], previous) => {
    if (previous != null && previous[0] !== documentId) {
      attachmentImageGeneration += 1
      attachmentImageStates.clear()
      attachmentOverlays.value = []
      revokeAttachmentImageUrls()
    }
    syncAttachmentOverlays()
    void hydrateDocumentAttachments()
  },
  { flush: 'post' }
)
onBeforeUnmount(() => {
  if (relativeTimeTimer != null) clearInterval(relativeTimeTimer)
  window.document.removeEventListener('click', onMoreMenuOutsideClick, true)
  restorePdfExportState?.()
  attachmentImageObserver?.disconnect()
  attachmentImageObserver = null
  attachmentImageGeneration += 1
  attachmentImageStates.clear()
  attachmentOverlays.value = []
  revokeAttachmentImageUrls()
})

const breadcrumbs = computed(() => {
  const byId = new Map(props.treeNodes.map((node) => [node.id, node]))
  const path: ProjectDocumentTreeNode[] = []
  let current: ProjectDocumentTreeNode | undefined = byId.get(props.document.id)
  while (current) {
    path.unshift(current)
    current = current.parentDocumentId == null ? undefined : byId.get(current.parentDocumentId)
  }
  return path
})

const saveLabel = computed(() => t(`documents.saveState.${props.saveState}`))
const lastEditor = computed(() => props.mentionMembers.find((member) => member.id === props.document.lastEditorId))

function relativeUpdatedTime(updatedAt: string) {
  return formatRelativeTime(updatedAt, t, 'documents.updatedTime', relativeTimeClock.value)
}

const updatedMetadata = computed(() => {
  if (lastEditor.value == null) return null
  // 更新人只按文档的 lastEditorId 关联项目成员，保持唯一字段链路。
  return t('documents.updatedBy', {
    name: lastEditor.value.label,
    time: relativeUpdatedTime(props.document.updatedAt)
  })
})

function handleTitleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.isComposing) return
  // 标题是单行输入框；回车后把光标交给正文，保持文档编辑的连续键盘流。
  event.preventDefault()
  bodyEditorRef.value?.focus()
}

async function copyDraft() {
  await navigator.clipboard.writeText(`${props.document.title}\n\n${props.document.content}`)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1800)
}

function exportPdf() {
  if (exportingPdf.value) return
  exportingPdf.value = true
  // 通过打印态隐藏工作区 chrome，并把当前文档标题作为 PDF 默认文件名。
  restorePdfExportState = startDocumentPdfExport(
    props.document.title.trim() || t('documents.untitled'),
    () => {
      exportingPdf.value = false
      restorePdfExportState = null
    }
  )
}

function handleDocumentBodyMouseDown(event: MouseEvent) {
  if (event.target instanceof Element && event.target.closest('.document-attachment-delete') != null) {
    event.stopPropagation()
    return
  }
  if (matchDocumentAttachmentEvent(event) == null) return
  // ProseMirror 会在 mousedown 后注册 document mouseup，并在 click 前通过 window.open 打开链接。
  event.stopPropagation()
}

async function handleDocumentAttachmentDelete(href: string, attachmentId: number) {
  const match = matchDocumentAttachmentPath(href)
  if (match == null) return
  const documentId = Number(match[1])
  if (documentId !== props.document.id || attachmentDeletePending.has(attachmentId)) return

  attachmentDownloadError.value = ''
  attachmentDeletePending.add(attachmentId)
  syncAttachmentOverlays()
  try {
    await documentApi.deleteAttachment(documentId, attachmentId)
    bodyEditorRef.value?.removeAttachmentLink(href)
  } catch {
    attachmentDownloadError.value = t('attachments.deleteFailed')
  } finally {
    attachmentDeletePending.delete(attachmentId)
    syncAttachmentOverlays()
  }
}

async function handleDocumentBodyClick(event: MouseEvent) {
  if (event.target instanceof Element) {
    const deleteButton = event.target.closest<HTMLButtonElement>('.document-attachment-delete')
    if (deleteButton != null && documentBodyRef.value?.contains(deleteButton)) {
      event.preventDefault()
      event.stopPropagation()
      void handleDocumentAttachmentDelete(
        deleteButton.dataset.attachmentHref ?? '',
        Number(deleteButton.dataset.attachmentId ?? '')
      )
      return
    }
  }
  const match = matchDocumentAttachmentEvent(event)
  if (match == null) return

  // 只接管附件路由，并阻止 Tiptap 的链接处理器再次通过 window.open 打开未鉴权地址。
  event.preventDefault()
  event.stopPropagation()
  const documentId = Number(match[1])
  const attachmentId = Number(match[2])
  if (documentId !== props.document.id) {
    attachmentDownloadError.value = t('documents.attachmentDocumentMismatch')
    return
  }
  if (attachmentDownloadPending.value) return

  attachmentDownloadError.value = ''
  attachmentDownloadPending.value = true
  try {
    await documentApi.downloadAttachment(documentId, attachmentId)
  } catch {
    attachmentDownloadError.value = t('attachments.downloadFailed')
  } finally {
    attachmentDownloadPending.value = false
  }
}
</script>

<template>
  <article class="document-editor" :aria-label="document.title">
    <header class="document-editor__toolbar">
      <div class="document-editor__toolbar-meta">
        <nav class="document-editor__breadcrumbs" :aria-label="t('documents.breadcrumbLabel')">
          <span v-for="(item, index) in breadcrumbs" :key="item.id">
            <span v-if="index > 0" aria-hidden="true">/</span>
            <span :title="item.title">{{ item.title }}</span>
          </span>
        </nav>
        <!-- 与任务详情页保持同级结构：收藏按钮紧跟文档名称并由父容器统一垂直居中。 -->
        <button
          type="button"
          class="document-editor__favorite"
          :class="{ 'document-editor__favorite--active': document.favorited }"
          :disabled="favoritePending"
          :aria-label="document.favorited ? t('documents.removeFavorite') : t('documents.addFavorite')"
          :aria-pressed="document.favorited"
          @click="emit('toggleFavorite')"
        >
          <Loader2 v-if="favoritePending" class="spin" aria-hidden="true" />
          <Star v-else aria-hidden="true" />
        </button>
      </div>
      <div class="document-editor__actions">
        <span class="document-editor__save-state" role="status" aria-live="polite">
          <Loader2 v-if="saveState === 'saving'" class="spin" aria-hidden="true" />
          <TriangleAlert v-else-if="saveState === 'conflict' || saveState === 'invalid' || saveState === 'failed'" aria-hidden="true" />
          <Check v-else-if="saveState === 'saved'" aria-hidden="true" />
          {{ saveLabel }}
        </span>
        <button type="button" :title="t('documents.exportPdf')" :disabled="exportingPdf" @click="exportPdf">
          <Loader2 v-if="exportingPdf" class="spin" aria-hidden="true" />
          <FileDown v-else aria-hidden="true" /><span>{{ exportingPdf ? t('documents.exportingPdf') : t('documents.exportPdf') }}</span>
        </button>
        <div ref="moreMenuRef" class="document-editor__more">
          <button
            type="button"
            class="document-editor__more-trigger"
            :title="t('documents.moreActions')"
            :aria-label="t('documents.moreActions')"
            :aria-expanded="moreOpen"
            aria-haspopup="menu"
            @click.stop="moreOpen = !moreOpen"
            @keydown.esc.stop="moreOpen = false"
          >
            <MoreHorizontal aria-hidden="true" />
          </button>
          <div v-if="moreOpen" class="document-editor__more-menu" role="menu" @keydown.esc.stop="moreOpen = false">
            <button type="button" role="menuitem" @click="moreOpen = false; emit('history')">
              <Clock3 aria-hidden="true" /><span>{{ t('documents.history') }}</span>
            </button>
            <button type="button" role="menuitem" @click="moreOpen = false; emit('archive')">
              <Archive aria-hidden="true" /><span>{{ t('documents.archive') }}</span>
            </button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="saveState === 'conflict'" class="document-editor__conflict" role="alert">
      <TriangleAlert aria-hidden="true" />
      <div>
        <strong>{{ t('documents.conflictTitle') }}</strong>
        <p>{{ t('documents.conflictDescription', { version: conflictVersion }) }}</p>
      </div>
      <button type="button" @click="copyDraft">
        <Copy aria-hidden="true" />{{ copied ? t('documents.copied') : t('documents.copyDraft') }}
      </button>
      <button type="button" @click="emit('reload')">
        <RefreshCw aria-hidden="true" />{{ t('documents.reloadServerVersion') }}
      </button>
    </div>
    <div v-else-if="saveState === 'failed'" class="document-editor__conflict" role="alert">
      <TriangleAlert aria-hidden="true" />
      <div><strong>{{ t('documents.saveFailedTitle') }}</strong><p>{{ t('documents.saveFailedDescription') }}</p></div>
      <span />
      <button type="button" @click="emit('retry')"><RefreshCw aria-hidden="true" />{{ t('common.retry') }}</button>
    </div>
    <div v-else-if="saveState === 'invalid'" class="document-editor__conflict" role="alert">
      <TriangleAlert aria-hidden="true" />
      <div><strong>{{ t('documents.titleRequired') }}</strong><p>{{ t('documents.titleRequiredDescription') }}</p></div>
      <span />
    </div>

    <DocumentMinimap :scroll-element="documentPageRef" :controls="`document-scroll-${document.id}`" />
    <div :id="`document-scroll-${document.id}`" ref="documentPageRef" class="document-editor__page">
      <div class="document-editor__heading">
        <label class="sr-only" :for="`document-title-${document.id}`">{{ t('documents.documentTitle') }}</label>
        <input
          :id="`document-title-${document.id}`"
          class="document-editor__title"
          type="text"
          maxlength="256"
          required
          :aria-invalid="saveState === 'invalid'"
          :value="document.title"
          :readonly="saveState === 'conflict'"
          @input="emit('updateTitle', ($event.target as HTMLInputElement).value)"
          @keydown="handleTitleKeydown"
        />
        <h1 class="document-editor__print-title">{{ document.title }}</h1>
        <p v-if="updatedMetadata" class="document-editor__updated">{{ updatedMetadata }}</p>
      </div>
      <div
        ref="documentBodyRef"
        class="document-editor__body"
        @mousedown.capture="handleDocumentBodyMouseDown"
        @click.capture="handleDocumentBodyClick"
      >
        <div ref="documentEditorSurfaceRef" class="document-editor__surface">
          <StructuredDocumentEditor
            ref="bodyEditorRef"
            :key="document.id"
            :document-id="document.id"
            paste-file-as-link
            :file-uploading-text="$t('documents.attachmentUploading')"
            :file-upload-failed-text="$t('documents.attachmentUploadFailed')"
            :model-value="document.content"
            :readonly="saveState === 'conflict'"
            :placeholder="t('documents.bodyPlaceholder')"
            :mention-members="mentionMembers"
            :mention-documents="mentionDocuments"
            @update:model-value="emit('updateContent', $event)"
          />
        </div>
        <div v-if="attachmentOverlays.length > 0" class="document-attachment-overlay" aria-live="polite">
          <button
            v-for="item in attachmentOverlays.filter((candidate) => candidate.type === 'delete')"
            :key="item.key"
            type="button"
            class="document-attachment-delete"
            :style="item.style"
            :data-attachment-href="item.href"
            :data-attachment-id="item.attachmentId"
            :disabled="attachmentDeletePending.has(item.attachmentId ?? -1)"
            :aria-label="t('documents.deleteAttachment')"
            :title="t('documents.deleteAttachment')"
            @click.stop.prevent="handleDocumentAttachmentDelete(item.href ?? '', item.attachmentId ?? -1)"
          />
          <span
            v-for="item in attachmentOverlays.filter((candidate) => candidate.type === 'image')"
            :key="item.key"
            class="document-attachment-image-status"
            :class="`document-attachment-image-status--${item.state}`"
            :style="item.style"
            role="status"
          >
            <span class="document-attachment-image-status__icon" aria-hidden="true" />
            <span>{{ item.state === 'loading' ? t('documents.imageLoading') : t('documents.imageLoadFailed') }}</span>
          </span>
        </div>
      </div>
      <p v-if="attachmentDownloadError" class="document-editor__attachment-error" role="alert">
        <TriangleAlert aria-hidden="true" />{{ attachmentDownloadError }}
      </p>
    </div>
  </article>
</template>

<style scoped>
.document-editor {
  --document-page-horizontal-inset: clamp(40px, 6vw, 96px);
  container: document-editor / inline-size;
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  background: var(--color-bg-base);
}

.document-editor__toolbar {
  display: flex;
  min-height: 48px;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 16px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.document-editor__toolbar-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.document-editor__breadcrumbs {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
}

.document-editor__breadcrumbs > span {
  display: flex;
  min-width: 0;
  gap: 7px;
}

.document-editor__breadcrumbs span span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-editor__actions,
.document-editor__actions > button,
.document-editor__save-state {
  display: flex;
  align-items: center;
}

.document-editor__actions { flex: none; gap: 6px; }
.document-editor__actions > button { gap: 6px; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.document-editor__actions > button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.document-editor__favorite { display: inline-flex; flex: 0 0 24px; align-items: center; justify-content: center; width: 24px; height: 24px; min-width: 24px; min-height: 24px; padding: 4px; border-radius: var(--radius-sm); color: var(--color-text-muted); transition: color var(--transition-fast), background var(--transition-fast); }
.document-editor__favorite:hover { background: var(--color-bg-hover); color: var(--color-text-secondary); }
.document-editor__favorite--active { color: #d4a106; }
.document-editor__favorite--active:hover { color: #b58900; }
.document-editor__favorite--active svg { fill: currentColor; }
.document-editor__actions > button:focus-visible,
.document-editor__favorite:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.document-editor__actions > button svg,
.document-editor__save-state svg { width: 14px; height: 14px; }
.document-editor__favorite svg { width: 16px; height: 16px; }
.document-editor__save-state { gap: 5px; padding: 0 6px; color: var(--color-text-muted); font-size: var(--font-size-caption); }

.document-editor__more { position: relative; }
.document-editor__more-trigger { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; padding: 0 !important; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.document-editor__more-trigger:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.document-editor__more-trigger:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.document-editor__more-trigger svg { width: 16px; height: 16px; }
.document-editor__more-menu { position: absolute; z-index: 20; top: calc(100% + 4px); right: 0; width: 152px; padding: 4px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-base); box-shadow: var(--shadow-popover); }
.document-editor__more-menu button { display: flex; width: 100%; align-items: center; gap: 8px; padding: 7px 8px; border-radius: var(--radius-sm); color: var(--color-text-secondary); text-align: left; }
.document-editor__more-menu button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.document-editor__more-menu button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: -2px; }
.document-editor__more-menu svg { width: 14px; height: 14px; flex: none; }

.document-editor__conflict {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-accent-muted-border);
  background: var(--color-accent-muted);
  color: var(--color-text-primary);
}

.document-editor__conflict > svg { width: 18px; height: 18px; color: var(--color-status-warning); }
.document-editor__conflict p { margin: 2px 0 0; color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.document-editor__conflict button { display: flex; align-items: center; gap: 6px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg-base); }
.document-editor__conflict button svg { width: 14px; height: 14px; }

.document-editor__page {
  /* 宽屏使用可用空间，阅读边距通过响应式内缩控制，避免滚动条后形成孤立空白区。 */
  width: 100%;
  min-height: 0;
  flex: 1;
  margin: 0;
  padding: 56px var(--document-page-horizontal-inset) 120px;
  overflow-y: auto;
}

.document-editor__title {
  width: 100%;
  min-height: 52px;
  padding: 0;
  color: var(--color-text-primary);
  font-size: 36px;
  font-weight: var(--font-weight-semibold);
  letter-spacing: -0.035em;
  line-height: 1.2;
}

.document-editor__title:focus-visible { outline: none; }
.document-editor__title[readonly] { color: var(--color-text-secondary); }
.document-editor__print-title { display: none; }

.document-editor__heading { margin-bottom: 20px; }

.document-editor__updated {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-body);
  line-height: 1.5;
}

.document-editor__body { position: relative; }
.document-editor__surface { position: relative; }
.document-attachment-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  pointer-events: none;
}

/* BlockNote owns the editable DOM; attachment styling stays CSS-only inside it. */
.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]) {
  position: relative;
  display: block;
  min-height: 48px;
  margin: 5px 0;
  padding: 13px 96px 13px 46px;
  overflow: hidden;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  color: var(--color-text-primary) !important;
  font-weight: var(--font-weight-medium);
  line-height: 20px;
  text-decoration: none !important;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 120ms ease, border-color 120ms ease;
}

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::before),
.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::after) {
  position: absolute;
  top: 50%;
  width: 18px;
  height: 18px;
  background: currentColor;
  content: '';
  transform: translateY(-50%);
  -webkit-mask-position: center;
  mask-position: center;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-size: contain;
  mask-size: contain;
}

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::before) {
  left: 15px;
  color: var(--color-text-muted);
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z'/%3E%3Cpath d='M14 2v6h6'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z'/%3E%3Cpath d='M14 2v6h6'/%3E%3C/svg%3E");
}

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::after) {
  right: 64px;
  color: var(--color-text-secondary);
  opacity: 0;
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4'/%3E%3Cpath d='m7 10 5 5 5-5'/%3E%3Cpath d='M12 15V3'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4'/%3E%3Cpath d='m7 10 5 5 5-5'/%3E%3Cpath d='M12 15V3'/%3E%3C/svg%3E");
  transition: opacity 120ms ease;
}

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]:hover) {
  border-color: var(--color-border);
  background: var(--color-bg-hover);
}

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]:hover::after),
.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]:focus-visible::after) { opacity: 1; }

.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]:focus-visible) {
  outline: 2px solid var(--color-border-strong);
  outline-offset: 2px;
}

.document-attachment-image-status {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1;
  pointer-events: none;
  user-select: none;
}

.document-attachment-image-status--loading {
  background:
    linear-gradient(110deg, transparent 30%, color-mix(in srgb, var(--color-bg-hover) 72%, transparent) 48%, transparent 66%),
    var(--color-bg-subtle);
  background-size: 220% 100%;
  animation: document-attachment-image-shimmer 1.6s ease-in-out infinite;
}

.document-attachment-image-status--error {
  border: 1px solid color-mix(in srgb, var(--color-danger) 28%, var(--color-border-subtle));
  background: color-mix(in srgb, var(--color-danger) 6%, var(--color-bg-base));
}

.document-attachment-image-status__icon {
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  /* 加载反馈使用中性色，避免在阅读界面引入突兀的蓝色强调。 */
  border: 2px solid color-mix(in srgb, var(--color-text-muted) 22%, transparent);
  border-top-color: var(--color-text-secondary);
  border-radius: 50%;
  animation: document-attachment-image-spin 0.8s linear infinite;
}

.document-attachment-delete {
  position: absolute;
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  pointer-events: auto;
  transition: color 120ms ease, background-color 120ms ease;
}

.document-attachment-delete::before {
  width: 18px;
  height: 18px;
  background: currentColor;
  content: '';
  -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M3 6h18'/%3E%3Cpath d='M8 6V4h8v2'/%3E%3Cpath d='M19 6v14H5V6'/%3E%3Cpath d='M10 11v5'/%3E%3Cpath d='M14 11v5'/%3E%3C/svg%3E");
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M3 6h18'/%3E%3Cpath d='M8 6V4h8v2'/%3E%3Cpath d='M19 6v14H5V6'/%3E%3Cpath d='M10 11v5'/%3E%3Cpath d='M14 11v5'/%3E%3C/svg%3E");
  -webkit-mask-position: center;
  mask-position: center;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-size: contain;
  mask-size: contain;
}

.document-attachment-delete:hover {
  background: var(--color-bg-hover);
  color: var(--color-danger);
}

.document-attachment-delete:focus-visible {
  outline: 2px solid var(--color-border-strong);
  outline-offset: -2px;
}

.document-attachment-delete:disabled {
  cursor: wait;
  opacity: 0.55;
}

.document-editor__attachment-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 12px 0 0;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
}

.document-editor__attachment-error svg { width: 14px; height: 14px; }

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

.spin { animation: document-spin 800ms linear infinite; }
@keyframes document-spin { to { transform: rotate(360deg); } }
@keyframes document-attachment-image-shimmer {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}
@keyframes document-attachment-image-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
  .document-attachment-image-status--loading { animation: none; }
  .document-attachment-image-status__icon { animation: none; }
  .document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]),
  .document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::after) { transition: none; }
}

@media print {
  .document-editor { display: block; min-height: auto; background: #fff; }
  .document-editor__toolbar,
  .document-editor__conflict,
  .document-editor__attachment-error,
  .document-editor__title,
  :deep(.document-minimap) { display: none !important; }
  .document-editor__page { display: block; min-height: auto; padding: 0; overflow: visible; }
  .document-editor__print-title { display: block; margin: 0 0 8mm; color: #0d0d0d; font-size: 28px; font-weight: 600; line-height: 1.2; }
  .document-editor__heading { margin-bottom: 8mm; }
  /* PDF 导出只输出文档内容，不导出“更新人 已更新 X前”这类编辑元信息 */
  .document-editor__updated { display: none !important; }
  .document-editor__body :deep(.bn-editor) { min-height: auto !important; padding: 0 !important; font-size: 11pt; line-height: 1.6; }
  /* BlockNote renders the editor hint as a generated ::after node; it is UI chrome, not document content. */
  .document-editor__body :deep(.bn-block-content)::after { content: none !important; display: none !important; }
  .document-editor__body :deep(.bn-image-preview-button),
  .document-editor__body :deep(.document-attachment-delete),
  .document-editor__body :deep(.document-attachment-image-status) { display: none !important; }
}
</style>
