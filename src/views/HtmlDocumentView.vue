<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import * as Y from 'yjs'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, Code2, Download, Eye, History, Loader2, Play, RefreshCw, Save, Trash2, Upload } from 'lucide-vue-next'
import HtmlDocumentPreview from '../components/documents/HtmlDocumentPreview.vue'
import HtmlDocumentCollaboration from '../components/documents/HtmlDocumentCollaboration.vue'
import DocumentHistoryPanel from '../components/documents/DocumentHistoryPanel.vue'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import type { CollaborationSaveState } from '../composables/useDocumentCollaboration'
import { useDocumentStore } from '../store/documentStore'
import { useAuthStore } from '../store/authStore'
import type { ProjectDocument } from '../types/document'
import { downloadHtmlDocument, HTML_DOCUMENT_MAX_BYTES, htmlDocumentPath, readHtmlDocumentFile } from '../utils/htmlDocument'
import { clearAllDocumentDrafts, clearDocumentDraft, decodeBase64ToUint8Array, documentDraftId, listDocumentDrafts, readDocumentDraft, saveDocumentDraft, type DocumentLocalDraft } from '../utils/documentDraftStorage'
import { JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const store = useDocumentStore()
const auth = useAuthStore()
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
const collaborationStatus = ref<{ saveState: CollaborationSaveState; connectionState: string; hasSynced: boolean; authFailed?: boolean; collaborators: Array<{ id: number; name: string; color: string }>; version: number; commitSeq: number } | null>(null)
const reconnecting = ref(false)
const collaborationDisconnected = computed(() => {
  const status = collaborationStatus.value
  if (status == null) return false
  return status.authFailed === true ||
    status.connectionState === 'error' ||
    (!status.hasSynced && status.connectionState === 'offline')
})
const collaborationStatusKey = computed(() => {
  const status = collaborationStatus.value
  if (status == null) return 'connecting'
  if (status.authFailed || status.connectionState === 'error') return 'connectionLost'
  if (!status.hasSynced) return status.connectionState === 'connecting' ? 'connecting' : 'connectionLost'
  return status.saveState
})
const collaborationPrefetch = shallowRef<{ documentId: number; promise: Promise<DocumentCollaborationSession> } | null>(null)
const collaborationRef = ref<InstanceType<typeof HtmlDocumentCollaboration> | null>(null)
const draftCandidates = ref<Array<{ draftId: string; draft: DocumentLocalDraft }>>([])
const selectedDraftId = ref('')
const showDraftPicker = ref(false)
const draftPickerError = ref('')
const draftSource = ref<string | null>(null)
const selectedDraft = computed(() => draftCandidates.value.find((entry) => entry.draftId === selectedDraftId.value))
watch(selectedDraftId, () => { draftPickerError.value = ''; draftSource.value = null }, { flush: 'sync' })
let loadedSession: { ownerId: number; token: string } | null = null
let restoredTitleSourceDraftId: string | null = null
let fileReadSequence = 0
let draftRevision = 0
let createdNavigationPending = false
let htmlDraftTimer: ReturnType<typeof setTimeout> | null = null
let applyingDocument = false
let pendingTitleDraft: { ownerId: number; token: string; request: number; documentId: number; title: string; serverVersion: number } | null = null

function isCurrentSession(ownerId: number, token: string): boolean {
  return auth.getConsistentAccessToken() === token && auth.currentUser?.id === ownerId
}

function clearTitleDraft(ownerId: number, documentId: number, token: string) {
  if (!isCurrentSession(ownerId, token)) return
  const draft = readDocumentDraft(ownerId, documentId, documentDraftId)
  if (!draft?.titleDirty) return
  if (!draft.ydocUpdate) {
    clearDocumentDraft(ownerId, documentId, documentDraftId)
    return
  }
  const { title: _title, ...bodyDraft } = draft
  saveDocumentDraft(ownerId, documentDraftId, { ...bodyDraft, titleDirty: false, savedAt: Date.now() })
}

function cancelPendingTitleDraft() {
  if (htmlDraftTimer != null) clearTimeout(htmlDraftTimer)
  htmlDraftTimer = null
  pendingTitleDraft = null
}

function flushPendingTitleDraft(): boolean {
  if (htmlDraftTimer != null) clearTimeout(htmlDraftTimer)
  htmlDraftTimer = null
  const pending = pendingTitleDraft
  if (!pending) return true
  if (pending.request !== sequence || document.value?.id !== pending.documentId || !isCurrentSession(pending.ownerId, pending.token)) {
    pendingTitleDraft = null
    return false
  }
  const existing = readDocumentDraft(pending.ownerId, pending.documentId, documentDraftId)
  const saved = saveDocumentDraft(pending.ownerId, documentDraftId, {
    documentId: pending.documentId,
    format: 'HTML',
    title: pending.title,
    titleDirty: true,
    ydocUpdate: existing?.ydocUpdate,
    ydocEpoch: existing?.ydocEpoch,
    serverVersion: pending.serverVersion,
    savedAt: Date.now()
  })
  if (saved) pendingTitleDraft = null
  return saved
}

watch(title, (newTitle) => {
  draftRevision += 1
  cancelPendingTitleDraft()
  const current = document.value
  const ownerId = auth.currentUser?.id
  if (!current || ownerId == null || applyingDocument) return
  const token = auth.getConsistentAccessToken()
  if (!token || !isCurrentSession(ownerId, token)) return
  if (newTitle === current.title) {
    clearTitleDraft(ownerId, current.id, token)
    return
  }
  pendingTitleDraft = { ownerId, token, request: sequence, documentId: current.id, title: newTitle, serverVersion: current.version }
  htmlDraftTimer = setTimeout(flushPendingTitleDraft, 400)
}, { flush: 'sync' })
watch(content, () => { draftRevision += 1 }, { flush: 'sync' })
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
  applyingDocument = true
  title.value = value.title
  applyingDocument = false
  content.value = body
  previewContent.value = body
  window.document.title = `${value.title} · Linear Lite`
}

function acceptCollaborativeContent(body: string) {
  if (!loadedSession || !isCurrentSession(loadedSession.ownerId, loadedSession.token)) return
  content.value = body
  if (mode.value === 'preview') previewContent.value = body
}

function acceptCollaborationStatus(status: NonNullable<typeof collaborationStatus.value>) {
  if (!loadedSession || !isCurrentSession(loadedSession.ownerId, loadedSession.token)) return
  collaborationStatus.value = status
}

function closeDraftPicker() {
  showDraftPicker.value = false
  selectedDraftId.value = ''
  draftPickerError.value = ''
  draftSource.value = null
}

function refreshDraftCandidates(ownerId: number, documentId: number, token: string) {
  if (!isCurrentSession(ownerId, token)) return
  draftCandidates.value = listDocumentDrafts(ownerId, documentId)
    .filter((entry) => entry.draftId !== documentDraftId && entry.draft.format === 'HTML')
    .sort((left, right) => right.draft.savedAt - left.draft.savedAt)
}

function refreshCollaborationDrafts() {
  const current = document.value
  const session = loadedSession
  if (current && session) refreshDraftCandidates(session.ownerId, current.id, session.token)
}

function viewDraftSource() {
  const current = document.value
  const session = loadedSession
  if (!current || !session || !isCurrentSession(session.ownerId, session.token)) return
  const draft = readDocumentDraft(session.ownerId, current.id, selectedDraftId.value)
  if (JSON.stringify(draft) !== JSON.stringify(selectedDraft.value?.draft)) {
    refreshDraftCandidates(session.ownerId, current.id, session.token)
    selectedDraftId.value = ''
    draftPickerError.value = t('documents.draftChanged')
    return
  }
  if (!draft?.ydocUpdate) return
  const doc = new Y.Doc()
  try {
    Y.applyUpdate(doc, decodeBase64ToUint8Array(draft.ydocUpdate))
    draftSource.value = doc.getText('html').toString()
  } catch {
    draftPickerError.value = t('documents.draftUnreadable')
  } finally {
    doc.destroy()
  }
}

function restoreSelectedDraft(forceCrossEpoch = false) {
  const current = document.value
  const session = loadedSession
  if (!current || !session || !isCurrentSession(session.ownerId, session.token)) return
  flushPendingTitleDraft()
  const hasCurrentChanges = dirty.value || collaborationStatus.value?.saveState !== 'saved' ||
    Boolean(readDocumentDraft(session.ownerId, current.id, documentDraftId))
  let force = forceCrossEpoch
  if (hasCurrentChanges && !force) {
    if (!window.confirm(t('documents.draftOverwriteConfirm'))) {
      draftPickerError.value = t('documents.draftResolveCurrent')
      return
    }
    force = true
  }
  const sourceDraftId = selectedDraftId.value
  const draft = readDocumentDraft(session.ownerId, current.id, sourceDraftId)
  if (!draft || draft.format !== 'HTML') {
    draftPickerError.value = t('documents.draftUnavailable')
    return
  }
  if (JSON.stringify(draft) !== JSON.stringify(selectedDraft.value?.draft)) {
    refreshDraftCandidates(session.ownerId, current.id, session.token)
    selectedDraftId.value = ''
    draftPickerError.value = t('documents.draftChanged')
    return
  }
  const result = collaborationRef.value?.restoreDraft(draft, { sourceDraftId, force }) ?? 'busy'
  if (result === 'epoch-mismatch' && !forceCrossEpoch) {
    viewDraftSource()
    draftPickerError.value = t('documents.draftEpochMismatch')
    return
  }
  if (result !== 'restored') {
    draftPickerError.value = result === 'epoch-mismatch'
      ? t('documents.draftEpochMismatch')
      : result === 'invalid' ? t('documents.draftUnreadable') : t('documents.draftSyncing')
    return
  }
  if (draft.titleDirty && draft.title != null) {
    title.value = draft.title
    restoredTitleSourceDraftId = sourceDraftId !== documentDraftId ? sourceDraftId : null
  }
  closeDraftPicker()
}

function applyCrossEpochHtmlDraft() {
  if (!window.confirm(t('documents.draftCrossEpochConfirm'))) return
  restoreSelectedDraft(true)
}

function deleteSelectedDraft() {
  const current = document.value
  const session = loadedSession
  if (!current || !session || !isCurrentSession(session.ownerId, session.token) || !selectedDraftId.value) return
  clearDocumentDraft(session.ownerId, current.id, selectedDraftId.value)
  selectedDraftId.value = ''
  draftSource.value = null
  draftPickerError.value = ''
  refreshDraftCandidates(session.ownerId, current.id, session.token)
  if (draftCandidates.value.length === 0) closeDraftPicker()
}

function clearAllHtmlDrafts() {
  const current = document.value
  const session = loadedSession
  if (!current || !session || !isCurrentSession(session.ownerId, session.token)) return
  if (!window.confirm(t('documents.clearAllDraftsConfirm'))) return
  clearAllDocumentDrafts(session.ownerId, current.id, { excludeDraftId: documentDraftId })
  refreshDraftCandidates(session.ownerId, current.id, session.token)
  closeDraftPicker()
}

async function reconnectCollaboration() {
  if (reconnecting.value) return
  reconnecting.value = true
  try {
    await collaborationRef.value?.reconnect()
  } finally {
    reconnecting.value = false
  }
}

async function load() {
  flushPendingTitleDraft()
  const request = ++sequence
  cancelPendingTitleDraft()
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
  saving.value = false
  collaborationStatus.value = null
  collaborationPrefetch.value = null
  loadedSession = null
  draftCandidates.value = []
  closeDraftPicker()
  const ownerId = auth.currentUser?.id
  const token = auth.getConsistentAccessToken()
  if (ownerId == null || !token || auth.currentUser?.id !== ownerId) {
    if (request === sequence) loading.value = false
    return
  }
  loadedSession = { ownerId, token }
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
    if (request !== sequence || !isCurrentSession(ownerId, token)) return
    if (value.projectId !== projectId.value || value.contentFormat !== 'HTML' || value.archivedAt != null) {
      error.value = t('documents.html.unavailable')
      return
    }
    // Existing document bodies enter the page through the collaboration checkpoint.
    acceptDocument(value, '')
    refreshDraftCandidates(ownerId, id, token)
    mode.value = 'preview'
  } catch {
    if (request === sequence && isCurrentSession(ownerId, token)) error.value = t('documents.html.loadFailed')
  } finally {
    if (request === sequence) loading.value = false
  }
}

async function save() {
  if (saving.value || readingFile.value || !valid.value || conflict.value) return
  const ownerId = auth.currentUser?.id
  const token = auth.getConsistentAccessToken()
  if (ownerId == null || !token || auth.currentUser?.id !== ownerId) return
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
      if (request !== sequence || !isCurrentSession(ownerId, token)) return
      acceptDocument(created)
      createdNavigationPending = true
      await router.replace(htmlDocumentPath(created.projectId, created.id))
    } else {
      const saved = await documentCollaborationApi.updateTitle(current.id, current.title, savedTitle)
      if (request !== sequence || !isCurrentSession(ownerId, token)) return
      document.value = { ...current, ...saved }
      title.value = saved.title
      window.document.title = `${saved.title} · Linear Lite`
      cancelPendingTitleDraft()
      const draft = readDocumentDraft(ownerId, current.id, documentDraftId)
      if (draft?.ydocUpdate) {
        const { title: _title, ...bodyDraft } = draft
        saveDocumentDraft(ownerId, documentDraftId, { ...bodyDraft, titleDirty: false, serverVersion: saved.version, savedAt: Date.now() })
      } else {
        clearDocumentDraft(ownerId, current.id, documentDraftId)
      }
      if (restoredTitleSourceDraftId && restoredTitleSourceDraftId !== documentDraftId) {
        const sourceDraft = readDocumentDraft(ownerId, current.id, restoredTitleSourceDraftId)
        if (sourceDraft) {
          if (!sourceDraft.ydocUpdate) {
            clearDocumentDraft(ownerId, current.id, restoredTitleSourceDraftId)
          } else if (sourceDraft.titleDirty) {
            const { title: _sourceTitle, ...sourceBody } = sourceDraft
            saveDocumentDraft(ownerId, restoredTitleSourceDraftId, { ...sourceBody, titleDirty: false, serverVersion: saved.version, savedAt: Date.now() })
          }
        }
        restoredTitleSourceDraftId = null
        refreshDraftCandidates(ownerId, current.id, token)
      }
    }
    conflict.value = false
    mode.value = 'preview'
  } catch (cause) {
    if (request !== sequence || !isCurrentSession(ownerId, token)) return
    conflict.value = cause != null && typeof cause === 'object' && 'response' in cause &&
      (cause as { response?: { status?: unknown } }).response?.status === 409
    error.value = t(conflict.value ? 'documents.html.conflict' : 'documents.html.saveFailed')
  } finally {
    if (request === sequence) saving.value = false
  }
}

async function replaceFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file == null || saving.value) return
  const ownerId = auth.currentUser?.id
  const token = auth.getConsistentAccessToken()
  if (ownerId == null || !token || auth.currentUser?.id !== ownerId) return
  const request = ++fileReadSequence
  const draftAtRead = draftRevision
  readingFile.value = true
  try {
    const source = await readHtmlDocumentFile(file)
    if (request !== fileReadSequence || draftAtRead !== draftRevision || !isCurrentSession(ownerId, token)) return
    content.value = source
    if (document.value == null && title.value.trim().length === 0) title.value = file.name.replace(/\.html?$/i, '')
    mode.value = 'source'
    error.value = ''
  } catch (cause) {
    if (request === fileReadSequence && isCurrentSession(ownerId, token)) error.value = t(cause instanceof Error ? cause.message : 'documents.html.invalidFile')
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
  flushPendingTitleDraft()
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
  if (saving.value) return false
  if (!dirty.value && (document.value == null || collaborationStatus.value?.saveState === 'saved')) return true
  if (!window.confirm(t('documents.html.discardChanges'))) return false
  if (dirty.value && document.value && loadedSession) {
    cancelPendingTitleDraft()
    clearTitleDraft(loadedSession.ownerId, document.value.id, loadedSession.token)
  }
  return true
}
onBeforeRouteLeave(confirmNavigation)
onBeforeRouteUpdate(confirmNavigation)
onBeforeUnmount(() => {
  flushPendingTitleDraft()
  sequence += 1
  loadedSession = null
  fileReadSequence += 1
  cancelPendingTitleDraft()
  window.removeEventListener('beforeunload', preventUnsavedExit)
  window.removeEventListener('storage', refreshSession)
})
watch(() => [route.params.projectId, route.params.documentId, route.name, auth.currentUser?.id, auth.jwtToken], load, { immediate: true, flush: 'sync' })
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
    <div v-if="document && draftCandidates.length" class="html-document__drafts">
      <button type="button" @click="showDraftPicker = !showDraftPicker">{{ t('documents.availableDrafts', { count: draftCandidates.length }) }}</button>
      <section v-if="showDraftPicker" class="html-document__draft-picker" :aria-label="t('documents.draftSelect')">
        <label>
          <span>{{ t('documents.draftSelect') }}</span>
          <select v-model="selectedDraftId">
            <option value="" disabled>{{ t('documents.draftSelectPlaceholder') }}</option>
            <option v-for="entry in draftCandidates" :key="entry.draftId" :value="entry.draftId">
              {{ new Date(entry.draft.savedAt).toLocaleString() }}{{ entry.draft.titleDirty ? ` · ${entry.draft.title}` : '' }}
            </option>
          </select>
        </label>
        <template v-if="selectedDraft">
          <p v-if="selectedDraft.draft.titleDirty">{{ t('documents.draftTitles', { current: document.title, draft: selectedDraft.draft.title }) }}</p>
          <p v-if="selectedDraft.draft.serverVersion !== document.version">{{ t('documents.draftVersionChanged') }}</p>
          <div class="html-document__draft-actions">
            <button type="button" @click="restoreSelectedDraft()">{{ t('documents.restoreDraft') }}</button>
            <button v-if="selectedDraft.draft.ydocUpdate" type="button" @click="viewDraftSource">{{ t('documents.draftView') }}</button>
            <button v-if="draftSource != null && draftPickerError === t('documents.draftEpochMismatch')" type="button" @click="applyCrossEpochHtmlDraft">{{ t('documents.draftApplyCrossEpoch') }}</button>
            <button type="button" @click="deleteSelectedDraft"><Trash2 aria-hidden="true" />{{ t('documents.deleteDraft') }}</button>
            <button v-if="draftCandidates.length > 1" type="button" @click="clearAllHtmlDrafts">{{ t('documents.clearAllDrafts') }}</button>
            <button type="button" @click="closeDraftPicker">{{ t('common.close') }}</button>
          </div>
          <textarea v-if="draftSource != null" :value="draftSource" readonly :aria-label="t('documents.draftSource')" />
        </template>
        <p v-if="draftPickerError" role="alert">{{ draftPickerError }}</p>
      </section>
    </div>
    <div v-if="document && collaborationDisconnected" class="html-document__error" role="alert">
      <span>{{ t('documents.collaboration.disconnectedTitle') }} — {{ t('documents.collaboration.disconnectedDescription') }}</span>
      <div class="html-document__actions">
        <button type="button" :disabled="reconnecting" @click="reconnectCollaboration">
          <Loader2 v-if="reconnecting" aria-hidden="true" /><RefreshCw v-else aria-hidden="true" />
          {{ t(reconnecting ? 'documents.collaboration.reconnecting' : 'documents.collaboration.reconnect') }}
        </button>
        <button type="button" @click="reload">{{ t('documents.html.reload') }}</button>
      </div>
    </div>
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
          ref="collaborationRef"
          v-if="document"
          :key="document.id"
          v-show="mode === 'source' && !historyOpen"
          :document-id="document.id"
          :initial-session="collaborationPrefetch?.documentId === document.id ? collaborationPrefetch.promise : undefined"
          :visible="mode === 'source'"
          :ariaLabel="t('documents.html.source')"
          :placeholder="t('documents.html.sourcePlaceholder')"
          @content="acceptCollaborativeContent"
          @status="acceptCollaborationStatus"
          @drafts-changed="refreshCollaborationDrafts"
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
.html-document__drafts { padding: 8px 16px; border-bottom: 1px solid var(--color-border); }
.html-document__draft-picker { display: grid; gap: 8px; margin-top: 8px; }
.html-document__draft-picker label { display: grid; gap: 4px; }
.html-document__draft-picker select { max-width: 100%; }
.html-document__draft-picker p { margin: 0; }
.html-document__draft-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.html-document__draft-picker textarea { min-height: 160px; }
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
