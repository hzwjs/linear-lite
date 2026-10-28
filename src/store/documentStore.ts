import { defineStore } from 'pinia'
import { ref } from 'vue'
import { documentApi, getDocumentConflict } from '../services/api/documents'
import { documentCollaborationApi } from '../services/api/documentCollaboration'
import { useDocumentFavoriteStore } from './documentFavoriteStore'
import type {
  DocumentSaveState,
  ProjectDocument,
  ProjectDocumentTreeNode
} from '../types/document'

export const DOCUMENT_TREE_CACHE_TTL_MS = 30_000

type LoadTreeOptions = { force?: boolean }

function treeSnapshotsEqual(current: ProjectDocumentTreeNode[], incoming: ProjectDocumentTreeNode[]) {
  if (current.length !== incoming.length) return false
  return current.every((node, index) => {
    const next = incoming[index]
    return next != null && node.id === next.id && node.projectId === next.projectId &&
      node.parentDocumentId === next.parentDocumentId && node.contentFormat === next.contentFormat &&
      node.title === next.title && node.sortOrder === next.sortOrder && node.version === next.version &&
      node.favorited === next.favorited && node.updatedAt === next.updatedAt
  })
}

export const useDocumentStore = defineStore('documentStore', () => {
  const treeNodes = ref<ProjectDocumentTreeNode[]>([])
  const archivedTreeNodes = ref<ProjectDocumentTreeNode[]>([])
  const activeDocument = ref<ProjectDocument | null>(null)
  const saveState = ref<DocumentSaveState>('idle')
  const loadingTree = ref(false)
  const refreshingTree = ref(false)
  const treeSnapshotVersion = ref(0)
  const treeError = ref<string | null>(null)
  const loadingDocument = ref(false)
  const error = ref<string | null>(null)
  const conflictVersion = ref<number | null>(null)
  const favoritePendingIds = ref(new Set<number>())

  let editSequence = 0
  let acknowledgedTitle = ''
  let activeSavePromise: Promise<void> | null = null
  let commitRequested = false
  let treeLoadSequence = 0
  let visibleTreeLoadSequence = 0
  let documentLoadSequence = 0
  let loadedTreeProjectId: number | null = null
  let treeLoadedAt = 0
  const treeLoadPromises = new Map<number, Promise<ProjectDocumentTreeNode[]>>()

  function resetEditorState() {
    editSequence = 0
    commitRequested = false
    saveState.value = 'idle'
    conflictVersion.value = null
  }

  function syncTreeNode(document: ProjectDocument) {
    const index = treeNodes.value.findIndex((node) => node.id === document.id)
    const node: ProjectDocumentTreeNode = {
      id: document.id,
      contentFormat: document.contentFormat,
      projectId: document.projectId,
      parentDocumentId: document.parentDocumentId,
      title: document.title,
      sortOrder: document.sortOrder,
      version: document.version,
      favorited: document.favorited,
      updatedAt: document.updatedAt
    }
    if (index === -1) treeNodes.value = [...treeNodes.value, node]
    else treeNodes.value[index] = node
  }

  function createOptimisticTreeMove(
    documentId: number,
    parentDocumentId: number | null,
    previousSiblingId: number | null
  ) {
    const movedNode = treeNodes.value.find((node) => node.id === documentId)
    if (!movedNode) throw new Error(`Document ${documentId} is missing from the tree`)

    const sourceParentDocumentId = movedNode.parentDocumentId
    const movedSnapshot = { ...movedNode, parentDocumentId }
    const bySortOrder = (a: ProjectDocumentTreeNode, b: ProjectDocumentTreeNode) =>
      a.sortOrder - b.sortOrder || a.id - b.id
    const targetSiblings = treeNodes.value
      .filter((node) => node.id !== documentId && node.parentDocumentId === parentDocumentId)
      .sort(bySortOrder)
    const insertionIndex = previousSiblingId == null
      ? 0
      : targetSiblings.findIndex((node) => node.id === previousSiblingId) + 1
    if (previousSiblingId != null && insertionIndex === 0) {
      throw new Error(`Previous sibling ${previousSiblingId} is missing from the target parent`)
    }
    targetSiblings.splice(insertionIndex, 0, movedSnapshot)

    const reordered = new Map<number, ProjectDocumentTreeNode>()
    targetSiblings.forEach((node, index) => reordered.set(node.id, { ...node, sortOrder: index }))
    if (sourceParentDocumentId !== parentDocumentId) {
      treeNodes.value
        .filter((node) => node.id !== documentId && node.parentDocumentId === sourceParentDocumentId)
        .sort(bySortOrder)
        .forEach((node, index) => reordered.set(node.id, { ...node, sortOrder: index }))
    }
    return treeNodes.value.map((node) => reordered.get(node.id) ?? node)
  }

  function requestTreeSnapshot(projectId: number, force: boolean) {
    const pending = treeLoadPromises.get(projectId)
    if (pending && !force) return pending
    const request = documentApi.listTree(projectId).finally(() => {
      if (treeLoadPromises.get(projectId) === request) treeLoadPromises.delete(projectId)
    })
    treeLoadPromises.set(projectId, request)
    return request
  }

  function acceptTreeSnapshot(projectId: number, nodes: ProjectDocumentTreeNode[]) {
    const sameSnapshot = loadedTreeProjectId === projectId && treeSnapshotsEqual(treeNodes.value, nodes)
    loadedTreeProjectId = projectId
    treeLoadedAt = Date.now()
    if (sameSnapshot) return
    treeNodes.value = nodes
    // 版本仅随有变化的服务端快照递增，便于诊断树的真实更新次数。
    treeSnapshotVersion.value += 1
  }

  async function loadTree(projectId: number, options: LoadTreeOptions = {}) {
    const visibleSequence = ++visibleTreeLoadSequence
    const sequence = ++treeLoadSequence
    const hasCurrentSnapshot = loadedTreeProjectId === projectId
    if (!options.force && hasCurrentSnapshot && Date.now() - treeLoadedAt < DOCUMENT_TREE_CACHE_TTL_MS) {
      loadingTree.value = false
      refreshingTree.value = false
      treeError.value = null
      return
    }

    loadingTree.value = !hasCurrentSnapshot
    refreshingTree.value = hasCurrentSnapshot
    if (!hasCurrentSnapshot) treeError.value = null
    try {
      const nodes = await requestTreeSnapshot(projectId, options.force === true)
      if (sequence === treeLoadSequence) {
        acceptTreeSnapshot(projectId, nodes)
        treeError.value = null
      }
    } catch (cause) {
      if (visibleSequence === visibleTreeLoadSequence && !hasCurrentSnapshot) {
        treeError.value = cause instanceof Error ? cause.message : String(cause)
      }
      throw cause
    } finally {
      if (visibleSequence === visibleTreeLoadSequence) {
        loadingTree.value = false
        refreshingTree.value = false
      }
    }
  }

  async function loadArchive(projectId: number) {
    archivedTreeNodes.value = await documentApi.listArchive(projectId)
  }

  async function loadDocument(documentId: number) {
    const sequence = ++documentLoadSequence
    // 路由已切换时立即撤下旧正文，禁止在新请求期间显示另一文档的数据。
    activeDocument.value = null
    resetEditorState()
    loadingDocument.value = true
    error.value = null
    try {
      const document = await documentApi.get(documentId)
      if (sequence !== documentLoadSequence) return document
      activeDocument.value = document
      acknowledgedTitle = document.title
      resetEditorState()
      syncTreeNode(document)
      useDocumentFavoriteStore().syncDocument(document)
      return document
    } catch (cause) {
      if (sequence === documentLoadSequence) {
        error.value = cause instanceof Error ? cause.message : String(cause)
      }
      throw cause
    } finally {
      if (sequence === documentLoadSequence) loadingDocument.value = false
    }
  }

  async function createDocument(projectId: number, parentDocumentId: number | null, title: string) {
    const document = await documentApi.create(projectId, { parentDocumentId, title, contentFormat: 'BLOCKNOTE' })
    syncTreeNode(document)
    return document
  }

  function updateDraft(patch: { title?: string; content?: string }) {
    const document = activeDocument.value
    if (!document || saveState.value === 'conflict') return
    const titleChanged = patch.title !== undefined && patch.title !== document.title
    if (!titleChanged) return
    const nextDocument = { ...document, title: patch.title! }
    activeDocument.value = nextDocument
    editSequence += 1
    if (nextDocument.title.trim().length === 0) {
      // 空标题在前端拦截，避免提交必填校验错误。
      saveState.value = 'invalid'
      error.value = null
      return
    }
    saveState.value = 'dirty'
  }

  async function performSave(): Promise<void> {
    const document = activeDocument.value
    if (!document || saveState.value === 'conflict' || saveState.value === 'invalid' ||
      saveState.value === 'idle' || saveState.value === 'saved') return
    const submittedId = document.id
    const submittedSequence = editSequence
    const expectedTitle = acknowledgedTitle
    const submittedTitle = document.title
    saveState.value = 'saving'
    try {
      const saved = await documentCollaborationApi.updateTitle(submittedId, expectedTitle, submittedTitle)
      acknowledgedTitle = saved.title
      const current = activeDocument.value
      if (current?.id !== submittedId) return
      const changedDuringRequest = editSequence !== submittedSequence
      activeDocument.value = changedDuringRequest
        ? { ...current, title: current.title, version: saved.version, updatedAt: saved.updatedAt, lastEditorId: saved.lastEditorId }
        : { ...current, ...saved }
      syncTreeNode(activeDocument.value)
      useDocumentFavoriteStore().syncDocument(activeDocument.value)
      if (changedDuringRequest && activeDocument.value.title.trim().length === 0) {
        saveState.value = 'invalid'
      } else {
        saveState.value = changedDuringRequest ? 'dirty' : 'saved'
      }
    } catch (cause) {
      if (activeDocument.value?.id !== submittedId) return
      if (cause != null && typeof cause === 'object' && 'response' in cause &&
        (cause as { response?: { status?: unknown } }).response?.status === 409) {
        const conflict = getDocumentConflict(cause)
        conflictVersion.value = conflict === null ? null : conflict.currentVersion
        saveState.value = 'conflict'
      } else {
        error.value = cause instanceof Error ? cause.message : String(cause)
        saveState.value = 'failed'
      }
    }
  }

  // 标题仅在输入框失焦或导航提交时保存；输入停顿不会提交草稿。
  function saveNow(): Promise<void> {
    commitRequested = true
    if (activeSavePromise) return activeSavePromise
    activeSavePromise = drainSaveRequests().finally(() => {
      activeSavePromise = null
    })
    return activeSavePromise
  }

  async function drainSaveRequests() {
    while (commitRequested) {
      commitRequested = false
      await performSave()
      if (saveState.value !== 'dirty') {
        commitRequested = false
        return
      }
    }
  }

  async function flushSaves() {
    if (saveState.value !== 'dirty' && saveState.value !== 'saving' && saveState.value !== 'failed') return
    await saveNow()
  }

  async function reloadAfterConflict() {
    const documentId = activeDocument.value?.id
    if (documentId == null) return
    await loadDocument(documentId)
  }

  async function moveDocument(
    projectId: number,
    documentId: number,
    parentDocumentId: number | null,
    previousSiblingId: number | null
  ) {
    const previousTree = treeNodes.value
    // 放下瞬间先更新本地排序，消除网络往返期间的视觉停顿。
    const optimisticTree = createOptimisticTreeMove(documentId, parentDocumentId, previousSiblingId)
    treeNodes.value = optimisticTree
    try {
      await documentApi.move(documentId, { parentDocumentId, previousSiblingId })
    } catch (cause) {
      if (treeNodes.value === optimisticTree) treeNodes.value = previousTree
      throw cause
    }
    try {
      // 放下后在后台获取权威快照；请求期间保留当前树，快照返回时再完整替换。
      await loadTree(projectId, { force: true })
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : String(cause)
      throw cause
    }
  }

  async function archiveDocument(documentId: number) {
    await documentApi.archive(documentId)
    const archivedIds = new Set<number>([documentId])
    let changed = true
    while (changed) {
      changed = false
      for (const node of treeNodes.value) {
        if (node.parentDocumentId != null && archivedIds.has(node.parentDocumentId) && !archivedIds.has(node.id)) {
          archivedIds.add(node.id)
          changed = true
        }
      }
    }
    treeNodes.value = treeNodes.value.filter((node) => !archivedIds.has(node.id))
    if (activeDocument.value && archivedIds.has(activeDocument.value.id)) {
      activeDocument.value = null
      resetEditorState()
    }
    archivedIds.forEach((id) => useDocumentFavoriteStore().removeDocument(id))
  }

  async function restoreDocument(documentId: number, projectId: number) {
    await documentApi.restore(documentId)
    await Promise.all([loadTree(projectId, { force: true }), loadArchive(projectId)])
  }

  async function toggleFavorite(document: Pick<ProjectDocumentTreeNode, 'id' | 'favorited'>) {
    const documentId = document.id
    if (favoritePendingIds.value.has(documentId)) return
    favoritePendingIds.value = new Set(favoritePendingIds.value).add(documentId)
    try {
      const updated = document.favorited
        ? await documentApi.removeFavorite(documentId)
        : await documentApi.addFavorite(documentId)
      syncTreeNode(updated)
      if (activeDocument.value?.id === documentId) activeDocument.value = updated
      useDocumentFavoriteStore().syncDocument(updated)
      return updated
    } finally {
      const next = new Set(favoritePendingIds.value)
      next.delete(documentId)
      favoritePendingIds.value = next
    }
  }

  async function restoreRevision(revisionId: number) {
    const document = activeDocument.value
    if (!document) return
    const restored = await documentApi.restoreRevision(document.id, revisionId, document.version)
    activeDocument.value = restored
    conflictVersion.value = null
    saveState.value = 'saved'
    syncTreeNode(restored)
    useDocumentFavoriteStore().syncDocument(restored)
  }

  function clear() {
    treeLoadSequence += 1
    visibleTreeLoadSequence += 1
    documentLoadSequence += 1
    loadedTreeProjectId = null
    treeLoadedAt = 0
    treeLoadPromises.clear()
    resetEditorState()
    treeNodes.value = []
    archivedTreeNodes.value = []
    treeError.value = null
    loadingTree.value = false
    refreshingTree.value = false
    activeDocument.value = null
    error.value = null
  }

  return {
    treeNodes,
    archivedTreeNodes,
    activeDocument,
    saveState,
    loadingTree,
    refreshingTree,
    treeSnapshotVersion,
    treeError,
    loadingDocument,
    error,
    conflictVersion,
    favoritePendingIds,
    loadTree,
    loadArchive,
    loadDocument,
    createDocument,
    updateDraft,
    saveNow,
    flushSaves,
    reloadAfterConflict,
    moveDocument,
    archiveDocument,
    restoreDocument,
    toggleFavorite,
    restoreRevision,
    clear
  }
})
