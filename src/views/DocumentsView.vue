<script setup lang="ts">
import { ArchiveRestore, Check, Code2, ExternalLink, FilePlus2, ImagePlus, Inbox, Loader2, Plus, Search, TriangleAlert, Upload, X } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { htmlDocumentPath } from '../utils/htmlDocument'
import DocumentEditor from '../components/documents/DocumentEditor.vue'
import DocumentHistoryPanel from '../components/documents/DocumentHistoryPanel.vue'
import DocumentSearchResults from '../components/documents/DocumentSearchResults.vue'
import DocumentTree from '../components/documents/DocumentTree.vue'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import { projectApi } from '../services/api/project'
import { useDocumentStore } from '../store/documentStore'
import { useProjectStore } from '../store/projectStore'
import type { ProjectContentSearchResult } from '../types/search'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const store = useDocumentStore()
const projectStore = useProjectStore()

const documentSearchQuery = ref('')
const documentSearchResults = ref<ProjectContentSearchResult[]>([])
const documentSearchLoading = ref(false)
const documentSearchError = ref(false)
const historyOpen = ref(false)
const archiveOpen = ref(false)
const creating = ref(false)
const moving = ref(false)
const markdownInput = ref<HTMLInputElement | null>(null)
const markdownImportParentId = ref<number | null>(null)
const imageInput = ref<HTMLInputElement | null>(null)
const imageDirectoryInput = ref<HTMLInputElement | null>(null)
const fileDragging = ref(false)
const fileDropParentId = ref<number | null>(null)
type MarkdownImport = {
  file: File
  projectId: number
  parentDocumentId: number | null
  state: 'importing' | 'resources' | 'imported' | 'failed'
  error: string | null
  images: Array<{ path: string; file: File | null }>
  candidates: File[]
}
let imagePickerTarget: { item: MarkdownImport; path: string | null } | null = null
const markdownImports = ref<MarkdownImport[]>([])
const importWorkspaceError = ref(false)
const importing = ref(false)
const visibleMarkdownImports = computed(() => markdownImports.value.filter((item) => item.projectId === projectId.value))
const fileDropTitle = computed(() => store.treeNodes.find((node) => node.id === fileDropParentId.value)?.title)
let workspaceDisposed = false
const members = ref<Array<{ id: number; label: string }>>([])
let workspaceLoadSequence = 0
const collaborationPrefetch = shallowRef<{ documentId: number; promise: Promise<DocumentCollaborationSession> } | null>(null)

function prefetchCollaboration(documentId: number | null) {
  if (documentId == null) {
    collaborationPrefetch.value = null
    return
  }
  const promise = documentCollaborationApi.createSession(documentId)
  void promise.catch(() => undefined)
  collaborationPrefetch.value = { documentId, promise }
}
let documentSearchSequence = 0
let documentSearchTimer: ReturnType<typeof setTimeout> | null = null
const DOCUMENT_SEARCH_DEBOUNCE_MS = 250

function refreshAfterTabFocus() {
  void store.loadTree(projectId.value).catch(() => undefined)
}
onMounted(() => window.addEventListener('focus', refreshAfterTabFocus))
onBeforeUnmount(() => window.removeEventListener('focus', refreshAfterTabFocus))

const projectId = computed(() => Number(route.params.projectId))
const documentId = computed(() => {
  const raw = route.params.documentId
  if (typeof raw !== 'string') return null
  const id = Number(raw)
  return Number.isInteger(id) ? id : null
})
const activeProject = computed(() => projectStore.projects.find((project) => project.id === projectId.value) ?? null)
const hasDocumentSearch = computed(() => documentSearchQuery.value.trim().length > 0)

function cancelDocumentSearch() {
  documentSearchSequence += 1
  if (documentSearchTimer) clearTimeout(documentSearchTimer)
  documentSearchTimer = null
  documentSearchLoading.value = false
}

function clearDocumentSearch() {
  cancelDocumentSearch()
  documentSearchQuery.value = ''
  documentSearchResults.value = []
  documentSearchError.value = false
}

async function runDocumentSearch(query: string) {
  const sequence = ++documentSearchSequence
  const requestedProjectId = projectId.value
  documentSearchLoading.value = true
  documentSearchError.value = false
  try {
    const results = await documentApi.search(requestedProjectId, query)
    // 只允许当前项目、当前关键词的最后一次请求提交结果，避免慢请求覆盖新输入。
    if (sequence === documentSearchSequence && requestedProjectId === projectId.value) {
      documentSearchResults.value = results
    }
  } catch {
    if (sequence === documentSearchSequence) documentSearchError.value = true
  } finally {
    if (sequence === documentSearchSequence) documentSearchLoading.value = false
  }
}

function scheduleDocumentSearch(value: string) {
  cancelDocumentSearch()
  documentSearchResults.value = []
  documentSearchError.value = false
  const query = value.trim()
  if (!query) return
  documentSearchLoading.value = true
  documentSearchTimer = setTimeout(() => {
    documentSearchTimer = null
    void runDocumentSearch(query)
  }, DOCUMENT_SEARCH_DEBOUNCE_MS)
}

watch(documentSearchQuery, scheduleDocumentSearch)

function documentRoute(id: number) {
  return `/projects/${projectId.value}/documents/${id}`
}

function latestRootDocumentId(): number | null {
  const roots = store.treeNodes.filter((node) => node.parentDocumentId == null)
  roots.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || a.id - b.id)
  return roots[0]?.id ?? null
}

async function loadWorkspace() {
  const sequence = ++workspaceLoadSequence
  const id = projectId.value
  const requestedDocumentId = documentId.value
  if (!Number.isInteger(id)) {
    await router.replace('/')
    return
  }
  projectStore.setActiveProject(id)
  historyOpen.value = false
  archiveOpen.value = false
  prefetchCollaboration(requestedDocumentId)
  // 路由已明确文档 ID 时立即加载正文，禁止被树或成员接口串行阻塞。
  const routedDocumentPromise = requestedDocumentId == null
    ? Promise.resolve(null)
    : store.loadDocument(requestedDocumentId)
  const [, memberList, routedDocument] = await Promise.all([
    store.loadTree(id),
    projectApi.listMembers(id),
    routedDocumentPromise
  ])
  if (sequence !== workspaceLoadSequence) return
  members.value = memberList.map((member) => ({ id: member.id, label: member.username }))
  if (requestedDocumentId == null) {
    const latestId = latestRootDocumentId()
    store.activeDocument = null
    if (latestId != null) await router.replace(documentRoute(latestId))
    return
  }
  if (routedDocument != null && routedDocument.projectId !== id) {
    await router.replace(`/projects/${id}/documents`)
  }
}

async function loadDocumentRoute() {
  const sequence = ++workspaceLoadSequence
  await loadRoutedDocument(sequence, projectId.value, documentId.value)
}

async function loadRoutedDocument(sequence: number, id: number, requestedDocumentId: number | null) {
  if (sequence !== workspaceLoadSequence) return
  prefetchCollaboration(requestedDocumentId)
  if (requestedDocumentId == null) {
    const latestId = latestRootDocumentId()
    store.activeDocument = null
    if (latestId != null) await router.replace(documentRoute(latestId))
    return
  }
  const document = await store.loadDocument(requestedDocumentId)
  if (sequence !== workspaceLoadSequence) return
  if (document.projectId !== id) await router.replace(`/projects/${id}/documents`)
}

watch(
  () => [route.params.projectId, route.params.documentId] as const,
  async (next, previous) => {
    if (previous) await store.flushSaves()
    try {
      // 项目切换才重载树和成员；项目内文档跳转只加载目标正文。
      if (!previous || next[0] !== previous[0]) await loadWorkspace()
      else if (next[1] !== previous[1]) await loadDocumentRoute()
    } catch {
      // 页面保留各请求自己的明确错误态，用户可原地重试。
    }
  },
  { immediate: true }
)

watch(projectId, () => {
  clearDocumentSearch()
  fileDragging.value = false
  fileDropParentId.value = null
})

function isFileDrag(event: DragEvent) {
  return event.dataTransfer?.types.includes('Files') === true
}

function onFileDragOver(event: DragEvent) {
  if (!isFileDrag(event)) return
  // 文件导入与树节点移动分流，阻止浏览器打开本地文件和节点移动事件。
  event.preventDefault()
  event.stopPropagation()
  const blocked = importing.value || moving.value || creating.value || store.loadingTree || store.treeError != null
  if (event.dataTransfer) event.dataTransfer.dropEffect = blocked ? 'none' : 'copy'
  fileDragging.value = !blocked
  const row = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-document-drop-id]') : null
  fileDropParentId.value = row == null ? null : Number(row.dataset.documentDropId)
}

function onFileDragLeave(event: DragEvent) {
  const region = event.currentTarget as HTMLElement
  if (event.relatedTarget instanceof Node && region.contains(event.relatedTarget)) return
  fileDragging.value = false
  fileDropParentId.value = null
}

function onFileDrop(event: DragEvent) {
  if (!isFileDrag(event)) return
  event.preventDefault()
  event.stopPropagation()
  const parentId = fileDropParentId.value
  const accepted = fileDragging.value
  fileDragging.value = false
  fileDropParentId.value = null
  if (accepted) void importMarkdownFiles(Array.from(event.dataTransfer!.files), parentId)
}

function onMarkdownSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  const parentDocumentId = markdownImportParentId.value
  markdownImportParentId.value = null
  input.value = ''
  void importMarkdownFiles(files, parentDocumentId)
}

function openMarkdownPicker(parentDocumentId: number | null) {
  if (importing.value || creating.value || moving.value || store.loadingTree || store.treeError != null) return
  markdownImportParentId.value = parentDocumentId
  markdownInput.value?.click()
}

async function importMarkdownItem(item: MarkdownImport): Promise<number | null> {
  item.state = 'importing'
  item.error = null
  try {
    if (!/\.md$/i.test(item.file.name)) throw new Error(t('documents.markdown.fileType'))
    if (item.file.size > 5 * 1024 * 1024) throw new Error(t('documents.markdown.fileSize'))
    const preview = await documentApi.inspectMarkdown(item.projectId, item.file)
    item.images = preview.imagePaths.map((path) => ({ path, file: item.images.find((image) => image.path === path)?.file ?? null }))
    matchMarkdownImages(item, item.candidates, false)
    if (item.images.some((image) => image.file == null)) {
      item.state = 'resources'
      return null
    }
    const images = item.images.map((image) => ({ path: image.path, file: image.file! }))
    const document = await documentApi.importMarkdown(item.projectId, item.parentDocumentId, item.file, images)
    item.state = 'imported'
    return document.id
  } catch (cause) {
    item.state = 'failed'
    item.error = cause instanceof Error ? cause.message : t('documents.markdown.failed')
    return null
  }
}

function matchMarkdownImages(item: MarkdownImport, files: File[], directory: boolean) {
  for (const image of item.images) {
    if (image.file != null) continue
    const matches = files.filter((file) => {
      // 目录选择携带顶层目录名；其余路径以用户所选目录为 Markdown 资源根目录。
      if (directory && (typeof file.webkitRelativePath !== 'string' || !file.webkitRelativePath.includes('/'))) return false
      const path = directory ? file.webkitRelativePath.slice(file.webkitRelativePath.indexOf('/') + 1) : file.name
      return path === image.path
    })
    if (matches.length === 1) image.file = matches[0]!
  }
}

function chooseMarkdownImages(item: MarkdownImport, path: string | null, directory = false) {
  imagePickerTarget = { item, path }
  if (directory) imageDirectoryInput.value?.click()
  else if (imageInput.value) {
    imageInput.value.multiple = path == null
    imageInput.value.click()
  }
}

function onMarkdownImagesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  const target = imagePickerTarget
  imagePickerTarget = null
  if (target == null || files.length === 0) return
  if (target.path != null) {
    const resource = target.item.images.find((image) => image.path === target.path)
    if (resource && files.length === 1) resource.file = files[0]!
  } else {
    matchMarkdownImages(target.item, files, input.hasAttribute('webkitdirectory'))
  }
  target.item.state = 'resources'
  target.item.error = null
}

async function finishMarkdownImport(requestedProjectId: number, documentIdToOpen: number | null) {
  if (workspaceDisposed || projectId.value !== requestedProjectId || documentIdToOpen == null) return
  clearDocumentSearch()
  try {
    await store.loadTree(requestedProjectId)
    if (workspaceDisposed || projectId.value !== requestedProjectId) return
    await openDocument(documentIdToOpen)
  } catch {
    // 文档创建成功后不重传文件；目录刷新失败提供独立重试入口。
    importWorkspaceError.value = true
  }
}

async function importMarkdownFiles(files: File[], parentDocumentId: number | null) {
  if (importing.value || creating.value || moving.value || files.length === 0) return
  importing.value = true
  const requestedProjectId = projectId.value
  importWorkspaceError.value = false
  const candidates = files.filter((file) => /\.(png|jpe?g|gif)$/i.test(file.name))
  markdownImports.value = files.filter((file) => !candidates.includes(file)).map((file) => ({
    file, parentDocumentId, projectId: requestedProjectId, state: 'importing', error: null, images: [], candidates
  }))
  let lastDocumentId: number | null = null
  // 按文件隔离结果，重试使用失败项保留的文件与目录。
  for (const item of markdownImports.value) {
    const importedId = await importMarkdownItem(item)
    if (importedId != null) lastDocumentId = importedId
  }
  try {
    await finishMarkdownImport(requestedProjectId, lastDocumentId)
  } finally {
    importing.value = false
  }
}

async function retryMarkdownImport(item: MarkdownImport) {
  if (importing.value || creating.value || moving.value) return
  importing.value = true
  importWorkspaceError.value = false
  try {
    await finishMarkdownImport(item.projectId, await importMarkdownItem(item))
  } finally {
    importing.value = false
  }
}

async function openDocument(id: number) {
  const node = store.treeNodes.find((item) => item.id === id)
  if (node?.contentFormat === 'HTML') {
    window.open(htmlDocumentPath(projectId.value, id), '_blank', 'noopener,noreferrer')
    return
  }
  if (id === documentId.value) return
  await store.flushSaves()
  await router.push(documentRoute(id))
}

async function openDocumentSearchResult(result: ProjectContentSearchResult) {
  const id = Number(result.resourceId)
  if (!Number.isInteger(id)) return
  clearDocumentSearch()
  if (result.contentFormat === 'HTML') {
    window.open(htmlDocumentPath(result.projectId, id), '_blank', 'noopener,noreferrer')
    return
  }
  await openDocument(id)
}

async function createDocument(parentDocumentId: number | null) {
  if (creating.value || importing.value) return
  creating.value = true
  try {
    const document = await store.createDocument(projectId.value, parentDocumentId, t('documents.untitled'))
    await router.push(documentRoute(document.id))
  } finally {
    creating.value = false
  }
}

async function moveDocument(payload: { documentId: number; parentDocumentId: number | null; previousSiblingId: number | null }) {
  if (moving.value || importing.value) return
  moving.value = true
  try {
    await store.moveDocument(projectId.value, payload.documentId, payload.parentDocumentId, payload.previousSiblingId)
  } finally {
    moving.value = false
  }
}

async function archiveDocument(id: number) {
  const node = store.treeNodes.find((candidate) => candidate.id === id)
  if (!node || !window.confirm(t('documents.archiveConfirm', { title: node.title }))) return
  const wasActive = store.activeDocument?.id === id
  await store.archiveDocument(id)
  if (wasActive) await router.replace(`/projects/${projectId.value}/documents`)
}

async function openArchive() {
  archiveOpen.value = true
  await store.loadArchive(projectId.value)
}

async function openHistory() {
  await store.flushSaves()
  historyOpen.value = true
}

async function restoreArchived(id: number) {
  await store.restoreDocument(id, projectId.value)
  archiveOpen.value = false
  await router.push(documentRoute(id))
}

async function retryWorkspace() {
  await loadWorkspace()
}

onBeforeRouteLeave(async () => {
  await store.flushSaves()
})

onBeforeUnmount(() => {
  workspaceDisposed = true
  workspaceLoadSequence += 1
  cancelDocumentSearch()
  historyOpen.value = false
})
</script>

<template>
  <div class="documents-view">
    <div class="documents-view__workspace">
      <aside class="documents-sidebar" :aria-label="t('documents.title')">
        <header>
          <div>
            <span class="documents-sidebar__project" :title="activeProject?.name">{{ activeProject?.name }}</span>
            <h1>{{ t('documents.title') }}</h1>
          </div>
          <a class="documents-sidebar__new" :href="`/projects/${projectId}/documents/html/new`" target="_blank" rel="noopener noreferrer" :aria-label="t('documents.html.new')" :title="t('documents.html.new')"><Code2 aria-hidden="true" /> HTML</a>
          <button type="button" class="documents-sidebar__new" :disabled="importing || creating || moving || store.loadingTree || !!store.treeError"
            :aria-label="t('documents.markdown.import')" :title="t('documents.markdown.requirements')" @click="openMarkdownPicker(null)">
            <Loader2 v-if="importing" class="spin" aria-hidden="true" /><Upload v-else aria-hidden="true" /> MD
          </button>
          <input ref="markdownInput" type="file" accept=".md,.png,.jpg,.jpeg,.gif" multiple hidden @change="onMarkdownSelected" />
          <input ref="imageInput" data-markdown-images type="file" accept="image/png,image/jpeg,image/gif" multiple hidden @change="onMarkdownImagesSelected" />
          <input ref="imageDirectoryInput" data-markdown-image-directory type="file" webkitdirectory multiple hidden @change="onMarkdownImagesSelected" />
          <button
            type="button"
            class="documents-sidebar__new"
            :disabled="creating || importing"
            :title="t('documents.newRoot')"
            :aria-label="t('documents.newRoot')"
            @click="createDocument(null)"
          >
            <Loader2 v-if="creating" class="spin" aria-hidden="true" />
            <Plus v-else aria-hidden="true" />
          </button>
        </header>

        <label class="documents-sidebar__search">
          <span class="sr-only">{{ t('documents.searchLabel') }}</span>
          <Search aria-hidden="true" />
          <input
            v-model="documentSearchQuery"
            type="search"
            autocomplete="off"
            :placeholder="t('documents.searchPlaceholder')"
            :aria-label="t('documents.searchLabel')"
          />
          <button v-if="documentSearchQuery" type="button" :aria-label="t('documents.clearSearch')" @click="clearDocumentSearch">
            <X aria-hidden="true" />
          </button>
        </label>

        <p class="documents-sidebar__import-hint" :class="{ 'documents-sidebar__import-hint--drop': fileDragging }" role="status">
          {{ fileDragging ? fileDropParentId == null ? t('documents.markdown.dropRoot') : t('documents.markdown.dropChild', { title: fileDropTitle }) : t('documents.markdown.hint') }}
        </p>
        <div v-if="visibleMarkdownImports.length" class="documents-sidebar__imports" aria-live="polite" :aria-label="t('documents.markdown.results')">
          <ul>
            <li v-for="(item, index) in visibleMarkdownImports" :key="index" class="documents-sidebar__import-item">
              <div class="documents-sidebar__import-summary">
              <Loader2 v-if="item.state === 'importing'" class="spin" aria-hidden="true" />
              <Check v-else-if="item.state === 'imported'" aria-hidden="true" />
              <ImagePlus v-else-if="item.state === 'resources'" aria-hidden="true" />
              <TriangleAlert v-else class="documents-sidebar__import-error" aria-hidden="true" />
              <div>
                <span class="documents-sidebar__import-name">{{ item.file.name }}</span>
                <span :class="{ 'documents-sidebar__import-error': item.state === 'failed' }">{{ item.error ?? t(`documents.markdown.${item.state}`) }}</span>
              </div>
              <button v-if="item.state === 'failed'" type="button" :disabled="importing || creating || moving" :aria-label="t('documents.markdown.retryFile', { name: item.file.name })" @click="retryMarkdownImport(item)">{{ t('common.retry') }}</button>
              </div>
              <div v-if="item.state === 'resources' || item.state === 'failed' && item.images.length > 0" class="documents-sidebar__image-resources">
                <p>{{ t('documents.markdown.resourceHint') }}</p>
                <ul>
                  <li v-for="image in item.images" :key="image.path">
                    <Check v-if="image.file" aria-hidden="true" />
                    <span :title="image.path">{{ image.path }}</span>
                    <button type="button" :disabled="importing" :aria-label="t('documents.markdown.chooseImage') + ': ' + image.path" @click="chooseMarkdownImages(item, image.path)">{{ image.file ? t('documents.markdown.replaceImage') : t('documents.markdown.chooseImage') }}</button>
                  </li>
                </ul>
                <div class="documents-sidebar__image-actions">
                  <button type="button" :disabled="importing" @click="chooseMarkdownImages(item, null)">{{ t('documents.markdown.chooseImages') }}</button>
                  <button type="button" :disabled="importing" @click="chooseMarkdownImages(item, null, true)">{{ t('documents.markdown.chooseDirectory') }}</button>
                  <button type="button" :disabled="importing || item.images.some((image) => image.file == null)" @click="retryMarkdownImport(item)">{{ t('documents.markdown.continueImport') }}</button>
                </div>
              </div>
            </li>
          </ul>
          <p v-if="importWorkspaceError" role="alert">{{ t('documents.markdown.refreshFailed') }} <button type="button" @click="store.loadTree(projectId).then(() => { importWorkspaceError = false }).catch(() => undefined)">{{ t('common.retry') }}</button></p>
        </div>

        <div class="documents-sidebar__tree-scroll" :class="{ 'documents-sidebar__tree-scroll--file-drop': fileDragging && fileDropParentId == null }"
          @dragover.capture="onFileDragOver" @dragleave="onFileDragLeave" @drop.capture="onFileDrop">
          <DocumentSearchResults
            v-if="hasDocumentSearch"
            :query="documentSearchQuery"
            :results="documentSearchResults"
            :tree-nodes="store.treeNodes"
            :loading="documentSearchLoading"
            :error="documentSearchError"
            @select="openDocumentSearchResult"
            @retry="runDocumentSearch(documentSearchQuery.trim())"
          />
          <div v-if="!hasDocumentSearch && store.loadingTree" class="documents-sidebar__state">
            <Loader2 class="spin" aria-hidden="true" />{{ t('documents.loadingTree') }}
          </div>
          <div v-else-if="!hasDocumentSearch && store.treeError" class="documents-sidebar__state" role="alert">
            <span>{{ t('documents.loadFailed') }}</span>
            <button type="button" @click="retryWorkspace">{{ t('common.retry') }}</button>
          </div>
          <div v-else-if="!hasDocumentSearch && store.treeNodes.length === 0" class="documents-sidebar__empty">
            <FilePlus2 aria-hidden="true" />
            <p>{{ t('documents.emptyTree') }}</p>
            <button type="button" :disabled="creating || importing" @click="createDocument(null)">{{ t('documents.createFirst') }}</button>
          </div>
          <DocumentTree
            v-else-if="!hasDocumentSearch"
            :key="store.treeSnapshotVersion"
            :project-id="projectId"
            :nodes="store.treeNodes"
            :active-id="documentId"
            :moving="moving || importing"
            :file-drop-parent-id="fileDragging ? fileDropParentId : null"
            @select="openDocument"
            @create-child="createDocument"
            @import-markdown="openMarkdownPicker"
            @archive="archiveDocument"
            @toggle-favorite="store.toggleFavorite"
            @move="moveDocument"
          />
        </div>

        <footer>
          <button type="button" :class="{ active: archiveOpen }" @click="openArchive">
            <Inbox aria-hidden="true" />{{ t('documents.archived') }}
          </button>
        </footer>
      </aside>

      <main class="documents-content">
        <div v-if="store.loadingDocument" class="documents-content__state">
          <Loader2 class="spin" aria-hidden="true" />{{ t('documents.loadingDocument') }}
        </div>
        <div v-else-if="store.error && !store.activeDocument" class="documents-content__state" role="alert">
          <TriangleAlert aria-hidden="true" />
          <span>{{ t('documents.loadFailed') }}</span>
          <button type="button" @click="retryWorkspace">{{ t('common.retry') }}</button>
        </div>
        <div v-else-if="archiveOpen" class="documents-archive">
          <header>
            <div><h2>{{ t('documents.archived') }}</h2><p>{{ t('documents.archivedDescription') }}</p></div>
            <button type="button" :aria-label="t('common.close')" @click="archiveOpen = false"><X aria-hidden="true" /></button>
          </header>
          <ul v-if="store.archivedTreeNodes.length > 0">
            <li v-for="node in store.archivedTreeNodes" :key="node.id">
              <span>{{ node.title }}</span>
              <button type="button" @click="restoreArchived(node.id)">
                <ArchiveRestore aria-hidden="true" />{{ t('documents.restore') }}
              </button>
            </li>
          </ul>
          <div v-else class="documents-content__state">{{ t('documents.noArchived') }}</div>
        </div>
        <DocumentHistoryPanel
          v-else-if="store.activeDocument && historyOpen"
          :open="historyOpen"
          :document="store.activeDocument"
          @close="historyOpen = false"
          @restored="historyOpen = false"
        />
        <div v-else-if="store.activeDocument?.contentFormat === 'HTML'" class="documents-content__state">
          <a :href="htmlDocumentPath(projectId, store.activeDocument.id)" target="_blank" rel="noopener noreferrer">
            <ExternalLink aria-hidden="true" />{{ t('documents.html.open') }}
          </a>
        </div>
        <DocumentEditor
          :key="store.activeDocument.id"
          v-else-if="store.activeDocument?.contentFormat === 'BLOCKNOTE'"
          :document="store.activeDocument"
          :initial-session="collaborationPrefetch?.documentId === store.activeDocument.id ? collaborationPrefetch.promise : undefined"
          :tree-nodes="store.treeNodes"
          :save-state="store.saveState"
          :conflict-version="store.conflictVersion"
          :mention-members="members"
          :mention-documents="store.treeNodes.map((node) => ({ id: node.id, title: node.title, projectId: node.projectId }))"
          :favorite-pending="store.favoritePendingIds.has(store.activeDocument.id)"
          @update-title="store.updateDraft({ title: $event })"
          @archive="archiveDocument(store.activeDocument.id)"
          @history="openHistory"
          @reload="store.reloadAfterConflict"
          @retry="store.saveNow"
          @toggle-favorite="store.toggleFavorite(store.activeDocument)"
        />
        <div v-else class="documents-content__state">
          <FilePlus2 aria-hidden="true" />
          <span>{{ t('documents.selectDocument') }}</span>
        </div>

      </main>
    </div>
  </div>
</template>

<style scoped>
.documents-view { display: flex; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }
.documents-view__workspace { display: flex; min-height: 0; flex: 1; }
.documents-sidebar {
  display: flex;
  width: 280px;
  min-width: 240px;
  flex: none;
  flex-direction: column;
  border-right: 1px solid var(--color-border);
  background: var(--color-bg-subtle);
}
.documents-sidebar > header { display: flex; min-height: 68px; align-items: center; justify-content: space-between; padding: 10px 12px; }
.documents-sidebar > header > div { min-width: 0; flex: 1; }
.documents-sidebar__project { display: block; overflow: hidden; color: var(--color-text-muted); font-size: var(--font-size-xs); text-overflow: ellipsis; white-space: nowrap; }
.documents-sidebar h1 { margin: 2px 0 0; font-size: 16px; font-weight: var(--font-weight-semibold); }
.documents-sidebar__new { display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-sm); }
.documents-sidebar__new:hover { background: var(--color-bg-hover); }
.documents-sidebar__new { gap: 4px; color: var(--color-text-secondary); text-decoration: none; font-size: var(--font-size-xs); padding: 6px; }
.documents-sidebar__new svg { width: 16px; height: 16px; }
.documents-sidebar__search {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  margin: 0 12px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
}
.documents-sidebar__search:focus-within { border-color: var(--color-border-strong); box-shadow: 0 0 0 2px var(--color-accent-muted); }
.documents-sidebar__search > svg,
.documents-sidebar__search button svg { width: 14px; height: 14px; }
.documents-sidebar__search > svg { margin-left: 8px; color: var(--color-text-muted); }
.documents-sidebar__search input { width: 100%; }
/* 保留统一的自定义清除按钮，避免浏览器原生控件重复显示。 */
.documents-sidebar__search input::-webkit-search-cancel-button { display: none; }
.documents-sidebar__search button { display: inline-flex; align-items: center; justify-content: center; padding: 0 7px; }
.documents-sidebar__tree-scroll { min-height: 0; flex: 1; padding: 0 6px; overflow: auto; }
.documents-sidebar__tree-scroll--file-drop { background: var(--color-accent-muted); outline: 1px dashed var(--color-accent); outline-offset: -1px; }
.documents-sidebar__import-hint { margin: 0 12px 8px; color: var(--color-text-muted); font-size: var(--font-size-xs); }
.documents-sidebar__import-hint--drop { color: var(--color-accent); }
.documents-sidebar__imports { --import-results-max-height: 320px; max-height: var(--import-results-max-height); margin: 0 12px 8px; overflow-y: auto; color: var(--color-text-secondary); font-size: var(--font-size-xs); }
.documents-sidebar__imports ul { margin: 0; padding: 0; list-style: none; }
.documents-sidebar__import-item { padding: 6px 0; }
.documents-sidebar__import-summary { display: flex; align-items: start; gap: 6px; }
.documents-sidebar__imports svg { width: 14px; height: 14px; flex: none; }
.documents-sidebar__import-summary > div { display: flex; min-width: 0; flex: 1; flex-direction: column; overflow-wrap: anywhere; }
.documents-sidebar__image-resources { padding: 6px; border: 1px solid var(--color-border-subtle); border-radius: var(--radius-sm); margin-top: 6px; }
.documents-sidebar__image-resources li { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
.documents-sidebar__image-resources li span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.documents-sidebar__image-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.documents-sidebar__image-actions button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.documents-sidebar__imports button:disabled { color: var(--color-text-muted); cursor: not-allowed; }
.documents-sidebar__import-name { color: var(--color-text-primary); }
.documents-sidebar__imports button { padding: 0 4px; color: var(--color-accent); }
.documents-sidebar__imports p { margin: 6px 0; }
.documents-sidebar__import-error { color: var(--color-danger); }
.documents-sidebar__state,
.documents-sidebar__empty { display: flex; min-height: 160px; flex-direction: column; align-items: center; justify-content: center; gap: 9px; padding: 20px; color: var(--color-text-muted); text-align: center; }
.documents-sidebar__state button,
.documents-sidebar__empty button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg-base); }
.documents-sidebar__empty > svg { width: 24px; height: 24px; }
.documents-sidebar__empty p { margin: 0; }
.documents-sidebar footer { padding: 8px; border-top: 1px solid var(--color-border-subtle); }
.documents-sidebar footer button { display: flex; width: 100%; align-items: center; justify-content: flex-start; gap: 8px; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.documents-sidebar footer button:hover,
.documents-sidebar footer button.active { background: var(--color-bg-hover); color: var(--color-text-primary); }
.documents-sidebar footer svg { width: 14px; height: 14px; }
.documents-content { display: flex; min-width: 0; min-height: 0; flex: 1; }
.documents-content__state { display: flex; min-height: 240px; flex: 1; align-items: center; justify-content: center; gap: 8px; color: var(--color-text-muted); }
.documents-content__state svg { width: 18px; height: 18px; }
.documents-archive { width: min(100%, 860px); margin: 0 auto; padding: 48px 56px; overflow-y: auto; }
.documents-archive > header { display: flex; align-items: start; justify-content: space-between; border-bottom: 1px solid var(--color-border); }
.documents-archive h2 { margin: 0; font-size: 28px; }
.documents-archive header p { margin: 8px 0 24px; color: var(--color-text-secondary); }
.documents-archive header button { display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-sm); }
.documents-archive header svg { width: 16px; height: 16px; }
.documents-archive ul { margin: 0; padding: 12px 0; list-style: none; }
.documents-archive li { display: flex; min-height: 44px; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--color-border-subtle); }
.documents-archive li button { display: flex; align-items: center; gap: 6px; border-radius: var(--radius-sm); color: var(--color-text-secondary); }
.documents-archive li button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.documents-archive li svg { width: 14px; height: 14px; }
.documents-view button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.spin { animation: documents-spin 800ms linear infinite; }
@keyframes documents-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
</style>
