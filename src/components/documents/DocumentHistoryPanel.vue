<script setup lang="ts">
import { ArrowLeft, History, Loader2, RotateCcw, X } from 'lucide-vue-next'
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { documentApi } from '../../services/api/documents'
import { useDocumentStore } from '../../store/documentStore'
import type { ProjectDocument, ProjectDocumentRevisionSummary } from '../../types/document'
import StructuredDocumentEditor from '../StructuredDocumentEditor.vue'

const props = defineProps<{
  open: boolean
  document: ProjectDocument
}>()

const emit = defineEmits<{ close: []; restored: [] }>()
const { t, locale } = useI18n()
const store = useDocumentStore()
const revisions = ref<ProjectDocumentRevisionSummary[]>([])
const loading = ref(false)
const restoring = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  store.activeRevision = null
  try {
    revisions.value = await documentApi.listRevisions(props.document.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => { if (open) void load() }, { immediate: true })

async function selectRevision(version: number) {
  loading.value = true
  try {
    await store.loadRevision(props.document.id, version)
  } finally {
    loading.value = false
  }
}

async function restoreSelected() {
  if (!store.activeRevision) return
  restoring.value = true
  try {
    await store.restoreRevision(store.activeRevision.version)
    emit('restored')
    await load()
  } finally {
    restoring.value = false
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value))
}
</script>

<template>
  <aside v-if="open" class="document-history" :aria-label="t('documents.history')">
    <header>
      <button
        v-if="store.activeRevision"
        type="button"
        class="icon-button"
        :aria-label="t('documents.backToHistory')"
        @click="store.activeRevision = null"
      ><ArrowLeft aria-hidden="true" /></button>
      <History v-else aria-hidden="true" />
      <strong>{{ store.activeRevision ? t('documents.version', { version: store.activeRevision.version }) : t('documents.history') }}</strong>
      <button type="button" class="icon-button close" :aria-label="t('common.close')" @click="emit('close')">
        <X aria-hidden="true" />
      </button>
    </header>

    <div v-if="loading" class="document-history__state"><Loader2 class="spin" aria-hidden="true" />{{ t('common.loading') }}</div>
    <div v-else-if="error" class="document-history__state" role="alert">
      <span>{{ t('documents.historyLoadFailed') }}</span>
      <button type="button" @click="load">{{ t('common.retry') }}</button>
    </div>
    <template v-else-if="store.activeRevision">
      <div class="document-history__preview">
        <h2>{{ store.activeRevision.title }}</h2>
        <StructuredDocumentEditor :model-value="store.activeRevision.content" readonly />
      </div>
      <footer>
        <button type="button" :disabled="restoring" @click="restoreSelected">
          <Loader2 v-if="restoring" class="spin" aria-hidden="true" />
          <RotateCcw v-else aria-hidden="true" />
          {{ restoring ? t('documents.restoring') : t('documents.restoreThisVersion') }}
        </button>
      </footer>
    </template>
    <ol v-else class="document-history__list">
      <li v-for="revision in revisions" :key="revision.version">
        <button type="button" @click="selectRevision(revision.version)">
          <strong>{{ t('documents.version', { version: revision.version }) }}</strong>
          <span>{{ revision.title }}</span>
          <time :datetime="revision.createdAt">{{ formatDate(revision.createdAt) }}</time>
        </button>
      </li>
      <li v-if="revisions.length === 0" class="document-history__state">{{ t('documents.noHistory') }}</li>
    </ol>
  </aside>
</template>

<style scoped>
.document-history {
  display: flex;
  width: min(420px, 42vw);
  min-width: 320px;
  min-height: 0;
  flex: none;
  flex-direction: column;
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-base);
  box-shadow: -4px 0 12px rgba(0, 0, 0, 0.04);
}

.document-history header {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.document-history header > svg,
.document-history button svg { width: 15px; height: 15px; }
.document-history header .close { margin-left: auto; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-sm); }
.icon-button:hover { background: var(--color-bg-hover); }

.document-history__list { margin: 0; padding: 8px; overflow-y: auto; list-style: none; }
.document-history__list button {
  display: grid;
  width: 100%;
  grid-template-columns: 1fr auto;
  gap: 3px 12px;
  padding: 10px;
  border-radius: var(--radius-sm);
  text-align: left;
}
.document-history__list button:hover { background: var(--color-bg-hover); }
.document-history__list span { grid-column: 1 / -1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-secondary); }
.document-history__list time { color: var(--color-text-muted); font-size: var(--font-size-caption); }
.document-history__state { display: flex; min-height: 120px; align-items: center; justify-content: center; gap: 8px; padding: 20px; color: var(--color-text-muted); text-align: center; }
.document-history__preview { min-height: 0; padding: 24px; overflow-y: auto; }
.document-history__preview h2 { margin: 0 0 20px; font-size: 22px; }
.document-history footer { display: flex; justify-content: flex-end; padding: 12px; border-top: 1px solid var(--color-border); }
.document-history footer button { display: flex; align-items: center; gap: 7px; border-radius: var(--radius-sm); background: var(--color-accent); color: var(--color-text-on-accent); }
.document-history footer button:disabled { cursor: not-allowed; opacity: 0.5; }
.document-history button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.spin { animation: history-spin 800ms linear infinite; }
@keyframes history-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
