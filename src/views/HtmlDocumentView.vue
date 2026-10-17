<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, Code2, Download, Eye, History, Loader2, Play, Save, Upload } from 'lucide-vue-next'
import HtmlDocumentPreview from '../components/documents/HtmlDocumentPreview.vue'
import HtmlDocumentCollaboration from '../components/documents/HtmlDocumentCollaboration.vue'
import DocumentHistoryPanel from '../components/documents/DocumentHistoryPanel.vue'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import { useDocumentStore } from '../store/documentStore'
import type { ProjectDocument } from '../types/document'
import { downloadHtmlDocument, HTML_DOCUMENT_MAX_BYTES, htmlDocumentPath, readHtmlDocumentFile } from '../utils/htmlDocument'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const store = useDocumentStore()
const document = ref<ProjectDocument | null>(null)
const title = ref('')
const content = ref('')
const previewContent = ref('')
const mode = ref<'preview' | 'source'>('preview')
const previewRun = ref(0)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const conflict = ref(false)
const historyOpen = ref(false)
const readingFile = ref(false)
const collaborationStatus = ref<{ saveState: 'saved' | 'saving' | 'offline'; connectionState: string; hasSynced: boolean; collaborators: Array<{ id: number; name: string; color: string }>; version: number; commitSeq: number } | null>(null)
const collaborationStatusKey = computed(() => {
  const status = collaborationStatus.value
  if (status == null) return 'connecting'
  if (!status.hasSynced) return status.connectionState === 'connecting' ? 'connecting' : 'connectionLost'
  return status.saveState
})
const collaborationPrefetch = shallowRef<{ documentId: number; promise: Promise<DocumentCollaborationSession> } | null>(null)
let fileReadSequence = 0
let draftRevision = 0
let createdNavigationPending = false
watch([title, content], () => { draftRevision += 1 }, { flush: 'sync' })
const fileInput = ref<HTMLInputElement | null>(null)
let sequence = 0
const projectId = computed(() => Number(route.params.projectId))
const dirty = computed(() => document.value == null
  ? content.value.length > 0 || title.value.length > 0
  : title.value !== document.value.title)
const valid = computed(() => title.value.trim().length > 0 && title.value.trim().length <= 256 &&
  content.value.trim().length > 0 && !content.value.includes('\0') &&
  new TextEncoder().encode(content.value).length <= HTML_DOCUMENT_MAX_BYTES)

function acceptDocument(value: ProjectDocument, body = value.content) {
  document.value = value
  title.value = value.title
  content.value = body
  previewContent.value = body
  window.document.title = `${value.title} · Linear Lite`
}

function acceptCollaborativeContent(body: string) {
  content.value = body
  if (mode.value === 'preview') previewContent.value = body
}

async function load() {
  const request = ++sequence
  fileReadSequence += 1
  readingFile.value = false
  loading.value = true
  document.value = null
  title.value = ''
  content.value = ''
  previewContent.value = ''
  error.value = ''
  conflict.value = false
  historyOpen.value = false
  collaborationStatus.value = null
  collaborationPrefetch.value = null
  if (!Number.isInteger(projectId.value) || projectId.value <= 0) {
    error.value = t('documents.html.invalidRoute')
    loading.value = false
    return
  }
  if (route.name === 'html-document-create') {
    mode.value = 'source'
    loading.value = false
    window.document.title = `${t('documents.html.new')} · Linear Lite`
    return
  }
  const id = Number(route.params.documentId)
  if (!Number.isInteger(id) || id <= 0) {
    error.value = t('documents.html.invalidRoute')
    loading.value = false
    return
  }
  try {
    const sessionPromise = documentCollaborationApi.createSession(id)
    void sessionPromise.catch(() => undefined)
    collaborationPrefetch.value = { documentId: id, promise: sessionPromise }
    const value = await documentApi.get(id)
    if (request !== sequence) return
    if (value.projectId !== projectId.value || value.contentFormat !== 'HTML' || value.archivedAt != null) {
      error.value = t('documents.html.unavailable')
      return
    }
    // Existing document bodies enter the page through the collaboration checkpoint.
    acceptDocument(value, '')
    mode.value = 'preview'
  } catch {
    if (request === sequence) error.value = t('documents.html.loadFailed')
  } finally {
    if (request === sequence) loading.value = false
  }
}

async function save() {
  if (saving.value || readingFile.value || !valid.value || conflict.value) return
  const current = document.value
  if (current != null && title.value.trim() === current.title) return
  fileReadSequence += 1
  saving.value = true
  error.value = ''
  const request = sequence
  const savedTitle = title.value.trim()
  const savedContent = content.value
  const parent = route.query.parentDocumentId
  const parentId = parent == null ? null : Number(parent)
  if (parentId != null && (!Number.isInteger(parentId) || parentId <= 0)) {
    error.value = t('documents.html.invalidRoute')
    saving.value = false
    return
  }
  try {
    if (current == null) {
      const created = await documentApi.create(projectId.value, {
        parentDocumentId: parentId, title: savedTitle, content: savedContent, contentFormat: 'HTML'
      })
      if (request !== sequence) return
      acceptDocument(created)
      createdNavigationPending = true
      await router.replace(htmlDocumentPath(created.projectId, created.id))
    } else {
      const saved = await documentCollaborationApi.updateTitle(current.id, current.title, savedTitle)
      if (request !== sequence) return
      document.value = { ...current, ...saved }
      title.value = saved.title
      window.document.title = `${saved.title} · Linear Lite`
    }
    conflict.value = false
    mode.value = 'preview'
  } catch (cause) {
    if (request !== sequence) return
    conflict.value = cause != null && typeof cause === 'object' && 'response' in cause &&
      (cause as { response?: { status?: unknown } }).response?.status === 409
    error.value = t(conflict.value ? 'documents.html.conflict' : 'documents.html.saveFailed')
  } finally {
    saving.value = false
  }
}

async function replaceFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file == null || saving.value) return
  const request = ++fileReadSequence
  const draftAtRead = draftRevision
  readingFile.value = true
  try {
    const source = await readHtmlDocumentFile(file)
    if (request !== fileReadSequence || draftAtRead !== draftRevision) return
    content.value = source
    if (document.value == null && title.value.trim().length === 0) title.value = file.name.replace(/\.html?$/i, '')
    mode.value = 'source'
    error.value = ''
  } catch (cause) {
    if (request === fileReadSequence) error.value = t(cause instanceof Error ? cause.message : 'documents.html.invalidFile')
  } finally {
    if (request === fileReadSequence) readingFile.value = false
  }
}

function runPreview() {
  if (!valid.value) return
  previewContent.value = content.value
  previewRun.value += 1
  mode.value = 'preview'
}

function reload() {
  if (!dirty.value || window.confirm(t('documents.html.discardChanges'))) void load()
}

function openHistory() {
  if (document.value == null || dirty.value) return
  store.activeDocument = document.value
  historyOpen.value = true
}

async function restored() {
  historyOpen.value = false
  await load()
}

function preventUnsavedExit(event: BeforeUnloadEvent) {
  if (!dirty.value && !saving.value && collaborationStatus.value?.saveState !== 'saving' && collaborationStatus.value?.saveState !== 'offline') return
  event.preventDefault()
  event.returnValue = ''
}
function refreshSession(event: StorageEvent) {
  if (event.key === JWT_STORAGE_KEY || event.key === USER_STORAGE_KEY) window.location.reload()
}
window.addEventListener('beforeunload', preventUnsavedExit)
window.addEventListener('storage', refreshSession)
function confirmNavigation() {
  if (createdNavigationPending) {
    createdNavigationPending = false
    return true
  }
  return saving.value ? false : (!dirty.value && (document.value == null || collaborationStatus.value?.saveState === 'saved')) || window.confirm(t('documents.html.discardChanges'))
}
onBeforeRouteLeave(confirmNavigation)
onBeforeRouteUpdate(confirmNavigation)
onBeforeUnmount(() => {
  sequence += 1
  fileReadSequence += 1
  window.removeEventListener('beforeunload', preventUnsavedExit)
  window.removeEventListener('storage', refreshSession)
})
watch(() => [route.params.projectId, route.params.documentId, route.name], load, { immediate: true })
</script>

<template>
  <main class="html-document" :aria-busy="loading || saving">
    <header class="html-document__toolbar">
      <a :href="`/projects/${projectId}/documents`" class="html-document__back" :aria-label="t('documents.html.back')" :title="t('documents.html.back')"><ArrowLeft aria-hidden="true" /></a>
      <Code2 class="html-document__type" aria-hidden="true" />
      <h1>{{ document ? document.title : t(route.name === 'html-document-create' ? 'documents.html.new' : 'documents.html.title') }}</h1>
      <span v-if="document" class="html-document__version">v{{ collaborationStatus?.version ?? document.version }}</span>
      <span v-if="dirty" class="html-document__status" role="status">{{ t('documents.html.unsaved') }}</span>
      <span v-if="document && collaborationStatus" class="html-document__status" role="status" aria-live="polite">
        {{ t(`documents.collaboration.${collaborationStatusKey}`) }}
      </span>
      <div v-if="collaborationStatus?.collaborators.length" class="html-document__collaborators" :aria-label="t('documents.collaboration.people')">
        <span v-for="person in collaborationStatus.collaborators" :key="person.id" :title="person.name" :style="{ '--collaborator-color': person.color }">{{ person.name.slice(0, 1).toUpperCase() }}</span>
      </div>
      <div v-if="!loading && (document || route.name === 'html-document-create')" class="html-document__actions">
        <div class="html-document__modes" :aria-label="t('documents.html.viewMode')">
          <button type="button" :aria-pressed="mode === 'preview' && !historyOpen" :disabled="!valid || saving" @click="historyOpen = false; runPreview()"><Eye aria-hidden="true" />{{ t('documents.html.preview') }}</button>
          <button type="button" :aria-pressed="mode === 'source' && !historyOpen" :disabled="saving" @click="historyOpen = false; mode = 'source'"><Code2 aria-hidden="true" />{{ t('documents.html.source') }}</button>
        </div>
        <button v-if="document" type="button" :disabled="saving" @click="downloadHtmlDocument(title, content)"><Download aria-hidden="true" /><span>{{ t('documents.html.download') }}</span></button>
        <button v-if="document" type="button" :disabled="dirty || saving || collaborationStatus?.saveState !== 'saved'" @click="openHistory"><History aria-hidden="true" /><span>{{ t('documents.history') }}</span></button>
        <button class="html-document__save" type="button" :disabled="!valid || saving || readingFile || conflict || (document != null && !dirty)" @click="save"><Loader2 v-if="saving" aria-hidden="true" /><Save v-else aria-hidden="true" />{{ t(saving ? 'documents.saveState.saving' : 'documents.html.save') }}</button>
      </div>
    </header>
    <div v-if="error" class="html-document__error" role="alert">
      <span>{{ error }}</span>
      <button v-if="conflict || (!document && route.name !== 'html-document-create')" type="button" @click="reload">{{ t('documents.html.reload') }}</button>
    </div>
    <div v-if="loading" class="html-document__state" role="status">{{ t('documents.loadingDocument') }}</div>
    <div v-else-if="document || route.name === 'html-document-create'" class="html-document__workspace">
    <section v-show="mode === 'source' && !historyOpen" class="html-document__editor" :aria-label="t('documents.html.source')">
      <div class="html-document__fields">
        <label class="html-document__title-field"><span>{{ t('documents.html.documentTitle') }}</span><input v-model="title" maxlength="256" :disabled="saving" :placeholder="t('documents.untitled')" /></label>
        <button type="button" :disabled="saving || readingFile" :aria-busy="readingFile" @click="fileInput?.click()"><Upload aria-hidden="true" />{{ t(document ? 'documents.html.replace' : 'documents.html.import') }}</button>
        <button type="button" :disabled="!valid || saving || readingFile" @click="runPreview"><Play aria-hidden="true" />{{ t('documents.html.run') }}</button>
        <input ref="fileInput" class="html-document__file" type="file" accept=".html,.htm,text/html" aria-label="HTML" @change="replaceFile" />
      </div>
      <p class="html-document__hint" id="html-source-hint">{{ t('documents.html.sourceHint') }}</p>
      <div class="html-document__source-label"><span>{{ t('documents.html.source') }}</span>
        <HtmlDocumentCollaboration
          v-if="document"
          :key="document.id"
          v-show="mode === 'source' && !historyOpen"
          :document-id="document.id"
          :initial-session="collaborationPrefetch?.documentId === document.id ? collaborationPrefetch.promise : undefined"
          :visible="mode === 'source'"
          :ariaLabel="t('documents.html.source')"
          :placeholder="t('documents.html.sourcePlaceholder')"
          @content="acceptCollaborativeContent"
          @status="collaborationStatus = $event"
        />
        <textarea v-else v-model="content" :disabled="saving" aria-describedby="html-source-hint" spellcheck="false" autocapitalize="off" autocomplete="off" :placeholder="t('documents.html.sourcePlaceholder')" />
      </div>
    </section>
    <HtmlDocumentPreview v-if="mode === 'preview' && valid && !historyOpen" :key="previewRun" :project-id="projectId" :content="previewContent" :title="title" />
    <div v-else-if="mode === 'preview' && document && collaborationStatusKey === 'connectionLost'" class="html-document__state" role="status">{{ t('documents.collaboration.connectionLost') }}</div>
    </div>
    <DocumentHistoryPanel v-if="document && historyOpen" :document="document" :open="historyOpen" @close="historyOpen = false" @restored="restored" />
  </main>
</template>

<style scoped>
.html-document { --html-toolbar-size: 56px; --html-action-size: 36px; --html-page-gap: 12px; display: flex; flex-direction: column; height: 100dvh; color: var(--color-text-primary); background: var(--color-bg-base); }
.html-document__toolbar { display: flex; min-height: var(--html-toolbar-size); flex: none; align-items: center; gap: var(--html-page-gap); padding: 8px 16px; border-bottom: 1px solid var(--color-border); }
.html-document h1 { min-width: 0; overflow: hidden; margin: 0; font-size: var(--font-size-body); font-weight: var(--font-weight-semibold); text-overflow: ellipsis; white-space: nowrap; }
.html-document button, .html-document__back { display: inline-flex; min-height: var(--html-action-size); align-items: center; justify-content: center; gap: 6px; border-radius: var(--radius-sm); color: var(--color-text-secondary); text-decoration: none; white-space: nowrap; }
.html-document button:hover, .html-document__back:hover { background: var(--color-bg-hover); }
.html-document button:focus-visible, .html-document a:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.html-document button:disabled { opacity: 0.5; cursor: default; }
.html-document svg { width: 16px; height: 16px; flex: none; }
.html-document__back { width: var(--html-action-size); flex: none; }
.html-document__type, .html-document__version { flex: none; color: var(--color-text-muted); }
.html-document__status { color: var(--color-text-secondary); font-size: var(--font-size-xs); white-space: nowrap; }
.html-document__actions { display: flex; margin-left: auto; align-items: center; gap: 8px; }
.html-document__modes { display: flex; gap: 2px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.html-document__modes button[aria-pressed=true] { background: var(--color-bg-active); color: var(--color-text-primary); }
.html-document button.html-document__save { background: var(--color-accent); color: var(--color-text-on-accent); }
.html-document__error { display: flex; align-items: center; justify-content: space-between; gap: var(--html-page-gap); padding: 8px 16px; color: var(--color-danger); background: var(--color-bg-subtle); }
.html-document__state { display: flex; min-height: 0; flex: 1; align-items: center; justify-content: center; color: var(--color-text-secondary); }
.html-document__workspace { display: flex; min-height: 0; flex: 1; flex-direction: column; }
.html-document__editor { display: flex; min-height: 0; flex: 1; flex-direction: column; padding: 20px; }
.html-document__fields { display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--html-page-gap); }
.html-document__collaborators { display: flex; align-items: center; }
.html-document__collaborators span { display: grid; width: 26px; height: 26px; margin-left: -5px; place-items: center; border: 2px solid var(--color-bg-base); border-radius: 50%; color: white; background: var(--collaborator-color, var(--color-accent)); font-size: 11px; }
.html-document__title-field { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 6px; }
.html-document__title-field input { width: 100%; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.html-document__hint { margin: 12px 0; color: var(--color-text-secondary); font-size: var(--font-size-xs); }
.html-document__source-label { display: flex; min-height: 0; flex: 1; flex-direction: column; gap: 6px; }
.html-document__source-label > span, .html-document__title-field > span { font-size: var(--font-size-xs); color: var(--color-text-secondary); }
.html-document textarea { width: 100%; min-height: 0; flex: 1; resize: none; padding: 14px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg-subtle); color: var(--color-text-primary); font-family: ui-monospace, 'SF Mono', Menlo, monospace; line-height: 1.6; tab-size: 2; }
.html-document input:focus-visible, .html-document textarea:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.html-document__file { display: none; }
@media (max-width: 767px) {
  .html-document { --html-action-size: 44px; --html-page-gap: 8px; }
  .html-document__toolbar { flex-wrap: wrap; padding: 8px; }
  .html-document h1 { flex: 1; }
  .html-document__actions { width: 100%; margin: 0; flex-wrap: wrap; gap: 8px; }
  .html-document__actions > button:last-child { margin-left: auto; }
  .html-document__editor { padding: 12px; }
  .html-document__title-field { flex-basis: 100%; }
}
</style>
