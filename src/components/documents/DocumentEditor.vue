<script setup lang="ts">
import { Archive, Check, Clock3, Copy, Loader2, RefreshCw, Star, TriangleAlert } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import StructuredDocumentEditor from '../StructuredDocumentEditor.vue'
import { documentApi } from '../../services/api/documents'
import type { DocumentSaveState, ProjectDocument, ProjectDocumentTreeNode } from '../../types/document'

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
const bodyEditorRef = ref<InstanceType<typeof StructuredDocumentEditor> | null>(null)
const documentBodyRef = ref<HTMLElement | null>(null)
const attachmentDownloadError = ref('')
const attachmentDownloadPending = ref(false)
const relativeTimeClock = ref(Date.now())
const attachmentImageObjectUrls = new Map<HTMLImageElement, string>()
const pendingAttachmentImages = new WeakSet<HTMLImageElement>()
let attachmentImageObserver: MutationObserver | null = null
let attachmentImageGeneration = 0
let relativeTimeTimer: ReturnType<typeof setInterval> | null = null

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

function revokeAttachmentImageUrls() {
  for (const objectUrl of attachmentImageObjectUrls.values()) URL.revokeObjectURL(objectUrl)
  attachmentImageObjectUrls.clear()
}

async function hydrateDocumentAttachments() {
  const generation = attachmentImageGeneration
  await nextTick()
  const body = documentBodyRef.value
  if (body == null || generation !== attachmentImageGeneration) return

  for (const [image, objectUrl] of attachmentImageObjectUrls) {
    if (body.contains(image)) continue
    URL.revokeObjectURL(objectUrl)
    attachmentImageObjectUrls.delete(image)
  }

  for (const image of body.querySelectorAll<HTMLImageElement>('img[src]')) {
    const match = matchDocumentAttachmentPath(image.getAttribute('src'))
    if (match == null || attachmentImageObjectUrls.has(image) || pendingAttachmentImages.has(image)) continue
    const documentId = Number(match[1])
    const attachmentId = Number(match[2])
    if (documentId !== props.document.id) {
      attachmentDownloadError.value = t('documents.attachmentDocumentMismatch')
      continue
    }

    pendingAttachmentImages.add(image)
    try {
      const blob = await documentApi.getAttachmentBlob(documentId, attachmentId)
      if (generation !== attachmentImageGeneration || !body.contains(image)) continue
      // BlockNote 的原始 img 请求不会携带 JWT；只把精确附件路径替换为当前会话的 Blob URL。
      const objectUrl = URL.createObjectURL(blob)
      attachmentImageObjectUrls.set(image, objectUrl)
      image.src = objectUrl
    } catch {
      if (generation === attachmentImageGeneration) {
        attachmentDownloadError.value = t('attachments.downloadFailed')
      }
    } finally {
      pendingAttachmentImages.delete(image)
    }
  }
}

onMounted(async () => {
  // 页面停留期间按分钟刷新相对时间，避免“最近更新”文案逐渐失真。
  relativeTimeTimer = window.setInterval(() => { relativeTimeClock.value = Date.now() }, 60_000)
  await nextTick()
  const body = documentBodyRef.value
  if (body == null) return
  // BlockNote 会在父组件 mounted 后继续异步构建图片节点，监听新增节点后再执行精确路径水合。
  attachmentImageObserver = new MutationObserver(() => { void hydrateDocumentAttachments() })
  attachmentImageObserver.observe(body, { childList: true, subtree: true })
  void hydrateDocumentAttachments()
})
watch(
  () => [props.document.id, props.document.content] as const,
  ([documentId], previous) => {
    if (previous != null && previous[0] !== documentId) {
      attachmentImageGeneration += 1
      revokeAttachmentImageUrls()
    }
    void hydrateDocumentAttachments()
  },
  { flush: 'post' }
)
onBeforeUnmount(() => {
  if (relativeTimeTimer != null) clearInterval(relativeTimeTimer)
  attachmentImageObserver?.disconnect()
  attachmentImageObserver = null
  attachmentImageGeneration += 1
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
  const elapsedSeconds = Math.max(0, Math.floor((relativeTimeClock.value - Date.parse(updatedAt)) / 1000))
  if (elapsedSeconds < 60) return t('documents.updatedTime.justNow')
  const elapsedMinutes = Math.floor(elapsedSeconds / 60)
  if (elapsedMinutes < 60) return t('documents.updatedTime.minutesAgo', { count: elapsedMinutes })
  const elapsedHours = Math.floor(elapsedMinutes / 60)
  if (elapsedHours < 24) return t('documents.updatedTime.hoursAgo', { count: elapsedHours })
  const elapsedDays = Math.floor(elapsedHours / 24)
  if (elapsedDays < 30) return t('documents.updatedTime.daysAgo', { count: elapsedDays })
  return t('documents.updatedTime.monthsAgo', { count: Math.floor(elapsedDays / 30) })
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

function handleDocumentBodyMouseDown(event: MouseEvent) {
  if (matchDocumentAttachmentEvent(event) == null) return
  // ProseMirror 会在 mousedown 后注册 document mouseup，并在 click 前通过 window.open 打开链接。
  event.stopPropagation()
}

async function handleDocumentBodyClick(event: MouseEvent) {
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
      <nav class="document-editor__breadcrumbs" :aria-label="t('documents.breadcrumbLabel')">
        <span v-for="(item, index) in breadcrumbs" :key="item.id">
          <span v-if="index > 0" aria-hidden="true">/</span>
          <span :title="item.title">{{ item.title }}</span>
        </span>
      </nav>
      <div class="document-editor__actions">
        <span class="document-editor__save-state" role="status" aria-live="polite">
          <Loader2 v-if="saveState === 'saving'" class="spin" aria-hidden="true" />
          <TriangleAlert v-else-if="saveState === 'conflict' || saveState === 'failed'" aria-hidden="true" />
          <Check v-else-if="saveState === 'saved'" aria-hidden="true" />
          {{ saveLabel }}
        </span>
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
        <button type="button" :title="t('documents.history')" @click="emit('history')">
          <Clock3 aria-hidden="true" /><span>{{ t('documents.history') }}</span>
        </button>
        <button type="button" :title="t('documents.archive')" @click="emit('archive')">
          <Archive aria-hidden="true" /><span>{{ t('documents.archive') }}</span>
        </button>
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

    <div class="document-editor__page">
      <div class="document-editor__heading">
        <label class="sr-only" :for="`document-title-${document.id}`">{{ t('documents.documentTitle') }}</label>
        <input
          :id="`document-title-${document.id}`"
          class="document-editor__title"
          type="text"
          maxlength="256"
          :value="document.title"
          :readonly="saveState === 'conflict'"
          @input="emit('updateTitle', ($event.target as HTMLInputElement).value)"
          @keydown="handleTitleKeydown"
        />
        <p v-if="updatedMetadata" class="document-editor__updated">{{ updatedMetadata }}</p>
      </div>
      <div
        ref="documentBodyRef"
        class="document-editor__body"
        @mousedown.capture="handleDocumentBodyMouseDown"
        @click.capture="handleDocumentBodyClick"
      >
        <StructuredDocumentEditor
          ref="bodyEditorRef"
          :key="document.id"
          :model-value="document.content"
          :readonly="saveState === 'conflict'"
          :placeholder="t('documents.bodyPlaceholder')"
          :mention-members="mentionMembers"
          :mention-documents="mentionDocuments"
          @update:model-value="emit('updateContent', $event)"
        />
      </div>
      <p v-if="attachmentDownloadError" class="document-editor__attachment-error" role="alert">
        <TriangleAlert aria-hidden="true" />{{ attachmentDownloadError }}
      </p>
    </div>
  </article>
</template>

<style scoped>
.document-editor {
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

.document-editor__breadcrumbs {
  display: flex;
  min-width: 0;
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
.document-editor__actions button,
.document-editor__save-state {
  display: flex;
  align-items: center;
}

.document-editor__actions { flex: none; gap: 6px; }
.document-editor__actions button { gap: 6px; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.document-editor__actions button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.document-editor__actions .document-editor__favorite--active { color: var(--color-status-warning); }
.document-editor__favorite--active svg { fill: currentColor; }
.document-editor__actions button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.document-editor__actions svg,
.document-editor__save-state svg { width: 14px; height: 14px; }
.document-editor__save-state { gap: 5px; padding: 0 6px; color: var(--color-text-muted); font-size: var(--font-size-caption); }

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
  width: min(100%, 860px);
  min-height: 0;
  flex: 1;
  margin: 0 auto;
  padding: 56px 56px 120px;
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

.document-editor__heading { margin-bottom: 20px; }

.document-editor__updated {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-body);
  line-height: 1.5;
}

/* BlockNote owns the editable DOM; style the fixed attachment path directly to avoid mutation feedback loops. */
.document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]) {
  position: relative;
  display: block;
  min-height: 48px;
  margin: 5px 0;
  padding: 13px 48px 13px 46px;
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
  right: 15px;
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
@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
  .document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]),
  .document-editor__body :deep(a[href^="/api/project-documents/"][href*="/attachments/"][href$="/download"]::after) { transition: none; }
}
</style>
