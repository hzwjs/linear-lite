<script setup lang="ts">
import { History, Loader2, RotateCcw } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { documentApi } from '../../services/api/documents'
import { useDocumentStore } from '../../store/documentStore'
import type { ProjectDocument, ProjectDocumentRevision, ProjectDocumentRevisionSummary } from '../../types/document'
import { formatBeijingDateTime } from '../../utils/beijingTime'
import StructuredDocumentEditor from '../StructuredDocumentEditor.vue'

const props = defineProps<{ open: boolean; document: ProjectDocument }>()
const emit = defineEmits<{ close: []; restored: [] }>()
const { t } = useI18n()
const store = useDocumentStore()
const revisions = ref<ProjectDocumentRevisionSummary[]>([])
const selectedRevision = ref<ProjectDocumentRevision | null>(null)
const selectedRevisionId = ref<number | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
const restoring = ref(false)
const error = ref<string | null>(null)
const railRef = ref<HTMLElement | null>(null)

const selectedTitle = computed(() => selectedRevision.value?.title ?? props.document.title)
const selectedContent = computed(() => selectedRevision.value?.content ?? props.document.content)
const selectedEditor = computed(() => selectedRevision.value?.editorName ?? t('documents.currentVersion'))
const selectedTime = computed(() => selectedRevision.value?.createdAt ?? props.document.updatedAt)

const groupedRevisions = computed(() => {
  const groups = new Map<string, ProjectDocumentRevisionSummary[]>()
  for (const revision of revisions.value) {
    const key = revision.createdAt.slice(0, 10)
    const group = groups.get(key) ?? []
    group.push(revision)
    groups.set(key, group)
  }
  return [...groups].map(([key, items]) => ({ key, items }))
})

async function load() {
  loading.value = true
  error.value = null
  selectedRevisionId.value = null
  selectedRevision.value = null
  try {
    revisions.value = await documentApi.listRevisions(props.document.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

watch(() => [props.open, props.document.id] as const, ([open]) => {
  if (open) void load()
}, { immediate: true })

async function selectRevision(revisionId: number | null) {
  selectedRevisionId.value = revisionId
  selectedRevision.value = null
  detailError.value = null
  if (revisionId == null) return
  detailLoading.value = true
  try {
    selectedRevision.value = await documentApi.getRevision(props.document.id, revisionId)
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    detailLoading.value = false
  }
}

async function restoreSelected() {
  if (selectedRevisionId.value == null || restoring.value) return
  if (!window.confirm(t('documents.historyRestoreConfirm'))) return
  restoring.value = true
  try {
    await store.restoreRevision(selectedRevisionId.value)
    emit('restored')
  } finally {
    restoring.value = false
  }
}

function groupLabel(key: string) {
  const today = new Date().toISOString().slice(0, 10)
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
  if (key === today) return t('documents.historyToday')
  if (key === yesterday) return t('documents.historyYesterday')
  return key
}

function formatDate(value: string) {
  return formatBeijingDateTime(value)
}

function onDocumentClick(event: MouseEvent) {
  if (!(event.target instanceof Node) || railRef.value?.contains(event.target)) return
  emit('close')
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick, true)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick, true)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <section v-if="open" class="document-history" :aria-label="t('documents.history')">
    <div class="document-history__preview">
      <header class="document-history__preview-header">
        <div>
          <span class="document-history__eyebrow">{{ selectedRevision ? t('documents.history') : t('documents.currentVersion') }}</span>
          <h1>{{ selectedTitle }}</h1>
          <p>{{ t('documents.historySavedBy', { editor: selectedEditor, time: formatDate(selectedTime) }) }}</p>
        </div>
      </header>
      <div v-if="detailLoading" class="document-history__state"><Loader2 class="spin" aria-hidden="true" />{{ t('common.loading') }}</div>
      <div v-else-if="detailError" class="document-history__state" role="alert">
        <span>{{ t('documents.historyLoadFailed') }}</span>
        <button type="button" @click="selectRevision(selectedRevisionId)">{{ t('common.retry') }}</button>
      </div>
      <StructuredDocumentEditor v-else :model-value="selectedContent" :document-id="document.id" readonly />
    </div>

    <aside ref="railRef" class="document-history__rail" :aria-label="t('documents.history')">
      <header class="document-history__rail-header"><History aria-hidden="true" /><strong>{{ t('documents.history') }}</strong></header>
      <div v-if="loading" class="document-history__state"><Loader2 class="spin" aria-hidden="true" />{{ t('common.loading') }}</div>
      <div v-else-if="error" class="document-history__state" role="alert">
        <span>{{ t('documents.historyLoadFailed') }}</span>
        <button type="button" @click="load">{{ t('common.retry') }}</button>
      </div>
      <ol v-else class="document-history__list">
        <li>
          <button type="button" class="document-history__item" :class="{ selected: selectedRevisionId == null }" @click="selectRevision(null)">
            <strong>{{ t('documents.currentVersion') }}</strong>
            <span>{{ document.title }}</span>
            <time :datetime="document.updatedAt">{{ formatDate(document.updatedAt) }}</time>
          </button>
        </li>
        <li v-for="group in groupedRevisions" :key="group.key" class="document-history__group">
          <h2>{{ groupLabel(group.key) }}</h2>
          <ol>
            <li v-for="revision in group.items" :key="revision.revisionId">
              <button type="button" class="document-history__item" :class="{ selected: selectedRevisionId === revision.revisionId }" @click="selectRevision(revision.revisionId)">
                <strong>{{ revision.editorName }}</strong>
                <span>{{ revision.title }}</span>
                <time :datetime="revision.createdAt">{{ formatDate(revision.createdAt) }}</time>
              </button>
            </li>
          </ol>
        </li>
        <li v-if="revisions.length === 0" class="document-history__state">{{ t('documents.noHistory') }}</li>
      </ol>
      <footer v-if="selectedRevisionId != null && !detailError" class="document-history__rail-footer">
        <button type="button" :disabled="restoring || detailLoading" @click="restoreSelected">
          <Loader2 v-if="restoring" class="spin" aria-hidden="true" />
          <RotateCcw v-else aria-hidden="true" />
          {{ restoring ? t('documents.restoring') : t('documents.restoreThisVersion') }}
        </button>
      </footer>
    </aside>
  </section>
</template>

<style scoped>
.document-history { display: flex; min-width: 0; min-height: 0; flex: 1; background: var(--color-bg-base); }
.document-history__preview { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }
.document-history__preview-header { display: flex; align-items: flex-start; gap: 24px; padding: 20px clamp(24px, 5vw, 72px) 18px; border-bottom: 1px solid var(--color-border-subtle); }
.document-history__preview-header h1 { margin: 4px 0 0; font-size: clamp(20px, 2vw, 28px); font-weight: var(--font-weight-semibold); }
.document-history__preview-header p { margin: 6px 0 0; color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.document-history__eyebrow { color: var(--color-accent); font-size: var(--font-size-caption); font-weight: var(--font-weight-semibold); }
.document-history__preview > .structured-document-editor { min-height: 0; flex: 1; overflow-y: auto; padding: 28px clamp(24px, 5vw, 72px); }
.document-history__rail { display: flex; width: 320px; min-width: 280px; min-height: 0; flex-direction: column; border-left: 1px solid var(--color-border); background: var(--color-bg-subtle); }
.document-history__rail-header { display: flex; min-height: 52px; align-items: center; gap: 8px; padding: 0 14px; border-bottom: 1px solid var(--color-border-subtle); }
.document-history__rail-header svg, .document-history button svg { width: 15px; height: 15px; }
.document-history__list { margin: 0; padding: 10px 8px 24px; overflow-y: auto; list-style: none; }
.document-history__group { margin-top: 14px; }
.document-history__group h2 { margin: 0 10px 5px; color: var(--color-text-muted); font-size: var(--font-size-caption); font-weight: var(--font-weight-semibold); }
.document-history__group ol { margin: 0; padding: 0; list-style: none; }
.document-history__item { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 10px; padding: 10px; border-radius: var(--radius-sm); text-align: left; }
.document-history__item:hover, .document-history__item.selected { background: var(--color-bg-hover); }
.document-history__item.selected { box-shadow: inset 2px 0 var(--color-accent); }
.document-history__item span { grid-column: 1 / -1; overflow: hidden; color: var(--color-text-secondary); text-overflow: ellipsis; white-space: nowrap; }
.document-history__item time { color: var(--color-text-muted); font-size: var(--font-size-caption); }
.document-history__state { display: flex; min-height: 120px; align-items: center; justify-content: center; gap: 8px; padding: 20px; color: var(--color-text-muted); text-align: center; }
.document-history__state button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg-base); }
.document-history__rail-footer { display: flex; justify-content: flex-end; margin-top: auto; padding: 12px; border-top: 1px solid var(--color-border); }
.document-history__rail-footer button { display: flex; align-items: center; gap: 7px; border-radius: var(--radius-sm); background: var(--color-accent); color: var(--color-text-on-accent); }
.document-history footer button:disabled { cursor: not-allowed; opacity: 0.5; }
.document-history button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.spin { animation: history-spin 800ms linear infinite; }
@keyframes history-spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .document-history__rail { width: 280px; min-width: 240px; } .document-history__preview-header { padding-inline: 20px; } .document-history__preview > .structured-document-editor { padding-inline: 20px; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
