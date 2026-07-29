<script setup lang="ts">
import { Archive, Check, Clock3, Copy, Loader2, RefreshCw, TriangleAlert } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import StructuredDocumentEditor from '../StructuredDocumentEditor.vue'
import type { DocumentSaveState, ProjectDocument, ProjectDocumentTreeNode } from '../../types/document'

const props = defineProps<{
  document: ProjectDocument
  treeNodes: ProjectDocumentTreeNode[]
  saveState: DocumentSaveState
  conflictVersion: number | null
  mentionMembers: Array<{ id: number; label: string }>
  mentionDocuments: Array<{ id: number; title: string; projectId: number }>
}>()

const emit = defineEmits<{
  updateTitle: [title: string]
  updateContent: [content: string]
  archive: []
  history: []
  reload: []
  retry: []
}>()

const { t } = useI18n()
const copied = ref(false)

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

async function copyDraft() {
  await navigator.clipboard.writeText(`${props.document.title}\n\n${props.document.content}`)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1800)
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
      <label class="sr-only" :for="`document-title-${document.id}`">{{ t('documents.documentTitle') }}</label>
      <input
        :id="`document-title-${document.id}`"
        class="document-editor__title"
        type="text"
        maxlength="256"
        :value="document.title"
        :readonly="saveState === 'conflict'"
        @input="emit('updateTitle', ($event.target as HTMLInputElement).value)"
      />
      <StructuredDocumentEditor
        :key="document.id"
        :model-value="document.content"
        :readonly="saveState === 'conflict'"
        :placeholder="t('documents.bodyPlaceholder')"
        :mention-members="mentionMembers"
        :mention-documents="mentionDocuments"
        @update:model-value="emit('updateContent', $event)"
      />
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
  margin-bottom: 20px;
  padding: 0;
  color: var(--color-text-primary);
  font-size: 36px;
  font-weight: var(--font-weight-semibold);
  letter-spacing: -0.035em;
  line-height: 1.2;
}

.document-editor__title:focus-visible { outline: none; }
.document-editor__title[readonly] { color: var(--color-text-secondary); }

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
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
