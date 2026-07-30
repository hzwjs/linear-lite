<script setup lang="ts">
import { ArchiveRestore, FilePlus2, Inbox, Loader2, Plus, Search, TriangleAlert, X } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import DocumentEditor from '../components/documents/DocumentEditor.vue'
import DocumentHistoryPanel from '../components/documents/DocumentHistoryPanel.vue'
import DocumentTree from '../components/documents/DocumentTree.vue'
import { projectApi } from '../services/api/project'
import { useDocumentStore } from '../store/documentStore'
import { useProjectStore } from '../store/projectStore'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const store = useDocumentStore()
const projectStore = useProjectStore()

const filterQuery = ref('')
const historyOpen = ref(false)
const archiveOpen = ref(false)
const creating = ref(false)
const moving = ref(false)
const members = ref<Array<{ id: number; label: string }>>([])
let workspaceLoadSequence = 0

const projectId = computed(() => Number(route.params.projectId))
const documentId = computed(() => {
  const raw = route.params.documentId
  if (typeof raw !== 'string') return null
  const id = Number(raw)
  return Number.isInteger(id) ? id : null
})
const activeProject = computed(() => projectStore.projects.find((project) => project.id === projectId.value) ?? null)

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
      // 页面保留 store.error 的明确错误态，用户可原地重试。
    }
  },
  { immediate: true }
)

async function openDocument(id: number) {
  if (id === documentId.value) return
  await store.flushSaves()
  await router.push(documentRoute(id))
}

async function createDocument(parentDocumentId: number | null) {
  if (creating.value) return
  creating.value = true
  try {
    const document = await store.createDocument(projectId.value, parentDocumentId, t('documents.untitled'))
    await router.push(documentRoute(document.id))
  } finally {
    creating.value = false
  }
}

async function moveDocument(payload: { documentId: number; parentDocumentId: number | null; previousSiblingId: number | null }) {
  if (moving.value) return
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
  workspaceLoadSequence += 1
  historyOpen.value = false
})
</script>

<template>
  <div class="documents-view">
    <div class="documents-view__workspace">
      <aside class="documents-sidebar" :aria-label="t('documents.title')">
        <header>
          <div>
            <span class="documents-sidebar__project">{{ activeProject?.name }}</span>
            <h1>{{ t('documents.title') }}</h1>
          </div>
          <button
            type="button"
            class="documents-sidebar__new"
            :disabled="creating"
            :title="t('documents.newRoot')"
            :aria-label="t('documents.newRoot')"
            @click="createDocument(null)"
          >
            <Loader2 v-if="creating" class="spin" aria-hidden="true" />
            <Plus v-else aria-hidden="true" />
          </button>
        </header>

        <label class="documents-sidebar__search">
          <span class="sr-only">{{ t('documents.filterLabel') }}</span>
          <Search aria-hidden="true" />
          <input v-model="filterQuery" type="search" :placeholder="t('documents.filterPlaceholder')" />
          <button v-if="filterQuery" type="button" :aria-label="t('documents.clearFilter')" @click="filterQuery = ''">
            <X aria-hidden="true" />
          </button>
        </label>

        <div class="documents-sidebar__tree-scroll">
          <div v-if="store.loadingTree" class="documents-sidebar__state">
            <Loader2 class="spin" aria-hidden="true" />{{ t('documents.loadingTree') }}
          </div>
          <div v-else-if="store.error" class="documents-sidebar__state" role="alert">
            <span>{{ t('documents.loadFailed') }}</span>
            <button type="button" @click="retryWorkspace">{{ t('common.retry') }}</button>
          </div>
          <div v-else-if="store.treeNodes.length === 0" class="documents-sidebar__empty">
            <FilePlus2 aria-hidden="true" />
            <p>{{ t('documents.emptyTree') }}</p>
            <button type="button" :disabled="creating" @click="createDocument(null)">{{ t('documents.createFirst') }}</button>
          </div>
          <DocumentTree
            v-else
            :key="store.treeSnapshotVersion"
            :project-id="projectId"
            :nodes="store.treeNodes"
            :active-id="documentId"
            :query="filterQuery"
            :moving="moving"
            @select="openDocument"
            @create-child="createDocument"
            @archive="archiveDocument"
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
        <DocumentEditor
          v-else-if="store.activeDocument"
          :document="store.activeDocument"
          :tree-nodes="store.treeNodes"
          :save-state="store.saveState"
          :conflict-version="store.conflictVersion"
          :mention-members="members"
          :mention-documents="store.treeNodes.map((node) => ({ id: node.id, title: node.title, projectId: node.projectId }))"
          @update-title="store.updateDraft({ title: $event })"
          @update-content="store.updateDraft({ content: $event })"
          @archive="archiveDocument(store.activeDocument.id)"
          @history="historyOpen = true"
          @reload="store.reloadAfterConflict"
          @retry="store.saveNow"
        />
        <div v-else class="documents-content__state">
          <FilePlus2 aria-hidden="true" />
          <span>{{ t('documents.selectDocument') }}</span>
        </div>

        <DocumentHistoryPanel
          v-if="store.activeDocument"
          :open="historyOpen"
          :document="store.activeDocument"
          @close="historyOpen = false"
          @restored="historyOpen = false"
        />
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
.documents-sidebar__project { color: var(--color-text-muted); font-size: var(--font-size-xs); }
.documents-sidebar h1 { margin: 2px 0 0; font-size: 16px; font-weight: var(--font-weight-semibold); }
.documents-sidebar__new { display: inline-flex; align-items: center; justify-content: center; border-radius: var(--radius-sm); }
.documents-sidebar__new:hover { background: var(--color-bg-hover); }
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
.documents-sidebar__search button { display: inline-flex; align-items: center; justify-content: center; padding: 0 7px; }
.documents-sidebar__tree-scroll { min-height: 0; flex: 1; padding: 0 6px; overflow: auto; }
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
