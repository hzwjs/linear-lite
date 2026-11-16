import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DocumentContentFormat } from '../types/document'

export interface DocumentTabItem {
  id: number
  projectId: number
  title: string
  contentFormat: DocumentContentFormat
  pinned?: boolean
}

const STORAGE_KEY_PREFIX = 'linear-lite:doc-tabs:'

function getStorageKey(projectId: number): string {
  return `${STORAGE_KEY_PREFIX}${projectId}`
}

function loadTabsFromStorage(projectId: number): DocumentTabItem[] {
  try {
    const raw = localStorage.getItem(getStorageKey(projectId))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is DocumentTabItem =>
        typeof item === 'object' &&
        item != null &&
        Number.isInteger(item.id) &&
        Number.isInteger(item.projectId) &&
        item.projectId === projectId &&
        typeof item.title === 'string' &&
        (item.contentFormat === 'BLOCKNOTE' || item.contentFormat === 'HTML')
      )
      .map((item) => ({
        ...item,
        pinned: Boolean(item.pinned)
      }))
  } catch {
    return []
  }
}

function saveTabsToStorage(projectId: number, tabs: DocumentTabItem[]) {
  try {
    localStorage.setItem(getStorageKey(projectId), JSON.stringify(tabs))
  } catch {
    // 忽略配额超限或私密模式错误
  }
}

export const useDocumentTabsStore = defineStore('documentTabsStore', () => {
  const tabsByProject = ref<Record<number, DocumentTabItem[]>>({})

  function getTabs(projectId: number): DocumentTabItem[] {
    const existing = tabsByProject.value[projectId]
    if (!existing) {
      const loaded = loadTabsFromStorage(projectId)
      tabsByProject.value[projectId] = loaded
      return loaded
    }
    return existing
  }

  function openTab(tab: DocumentTabItem) {
    const projectTabs = getTabs(tab.projectId)
    const existingIndex = projectTabs.findIndex((t) => t.id === tab.id)
    if (existingIndex !== -1) {
      const current = projectTabs[existingIndex]
      if (current) {
        projectTabs[existingIndex] = {
          ...current,
          title: tab.title || current.title,
          contentFormat: tab.contentFormat || current.contentFormat,
          pinned: tab.pinned !== undefined ? tab.pinned : current.pinned
        }
      }
    } else {
      if (tab.pinned) {
        let lastPinnedIndex = -1
        for (let i = projectTabs.length - 1; i >= 0; i--) {
          if (projectTabs[i]?.pinned) {
            lastPinnedIndex = i
            break
          }
        }
        projectTabs.splice(lastPinnedIndex + 1, 0, { ...tab, pinned: true })
      } else {
        projectTabs.push({ ...tab, pinned: Boolean(tab.pinned) })
      }
    }
    saveTabsToStorage(tab.projectId, projectTabs)
  }

  function togglePinTab(projectId: number, documentId: number): boolean {
    const projectTabs = getTabs(projectId)
    const index = projectTabs.findIndex((t) => t.id === documentId)
    if (index === -1) return false
    const target = projectTabs[index]!
    const nextPinned = !target.pinned
    target.pinned = nextPinned

    const pinnedList = projectTabs.filter((t) => t.pinned)
    const unpinnedList = projectTabs.filter((t) => !t.pinned)
    tabsByProject.value[projectId] = [...pinnedList, ...unpinnedList]
    saveTabsToStorage(projectId, tabsByProject.value[projectId])
    return nextPinned
  }

  function moveTab(
    projectId: number,
    sourceId: number,
    targetId: number,
    place: 'before' | 'after' = 'before'
  ) {
    if (sourceId === targetId) return
    const projectTabs = getTabs(projectId)
    const fromIndex = projectTabs.findIndex((t) => t.id === sourceId)
    const toIndex = projectTabs.findIndex((t) => t.id === targetId)
    if (fromIndex === -1 || toIndex === -1) return

    const sourceTab = projectTabs[fromIndex]!
    const targetTab = projectTabs[toIndex]!

    // 拖动时保持固定区域与普通区域的一致性
    if (sourceTab.pinned !== targetTab.pinned) {
      sourceTab.pinned = targetTab.pinned
    }

    projectTabs.splice(fromIndex, 1)
    const newTargetIndex = projectTabs.findIndex((t) => t.id === targetId)
    const insertIndex = place === 'before' ? newTargetIndex : newTargetIndex + 1
    projectTabs.splice(insertIndex, 0, sourceTab)

    saveTabsToStorage(projectId, projectTabs)
  }

  function closeTab(projectId: number, documentId: number): number | null {
    const projectTabs = getTabs(projectId)
    const index = projectTabs.findIndex((t) => t.id === documentId)
    if (index === -1) return null

    projectTabs.splice(index, 1)
    saveTabsToStorage(projectId, projectTabs)

    if (projectTabs.length === 0) return null
    const nextIndex = Math.min(index, projectTabs.length - 1)
    return projectTabs[nextIndex]?.id ?? null
  }

  function closeOtherTabs(projectId: number, documentId: number) {
    const projectTabs = getTabs(projectId)
    const current = projectTabs.find((t) => t.id === documentId)
    if (!current) return
    // 保留当前右击/聚焦的页签以及所有已固定的页签
    tabsByProject.value[projectId] = projectTabs.filter((t) => t.id === documentId || t.pinned)
    saveTabsToStorage(projectId, tabsByProject.value[projectId])
  }

  function closeTabsToRight(projectId: number, documentId: number): number | null {
    const projectTabs = getTabs(projectId)
    const index = projectTabs.findIndex((t) => t.id === documentId)
    if (index === -1) return null

    // 仅关闭目标右侧的未固定页签；保留所有固定页签和目标左侧页签
    const retained: DocumentTabItem[] = []
    for (let i = 0; i < projectTabs.length; i++) {
      const tab = projectTabs[i]!
      if (i <= index || tab.pinned) {
        retained.push(tab)
      }
    }

    tabsByProject.value[projectId] = retained
    saveTabsToStorage(projectId, retained)
    return documentId
  }

  function closeAllTabs(projectId: number): number | null {
    const projectTabs = getTabs(projectId)
    // 关闭所有未固定页签，保留已固定页签
    const retained = projectTabs.filter((t) => t.pinned)
    tabsByProject.value[projectId] = retained
    saveTabsToStorage(projectId, retained)
    return retained[0]?.id ?? null
  }

  function updateTabTitle(projectId: number, documentId: number, title: string) {
    const projectTabs = getTabs(projectId)
    const target = projectTabs.find((t) => t.id === documentId)
    if (target && target.title !== title) {
      target.title = title
      saveTabsToStorage(projectId, projectTabs)
    }
  }

  function syncTabsWithTree(
    projectId: number,
    treeNodes: Array<{ id: number; title: string; contentFormat: DocumentContentFormat }>
  ) {
    const projectTabs = getTabs(projectId)
    const nodeMap = new Map(treeNodes.map((n) => [n.id, n]))
    let changed = false

    for (const tab of projectTabs) {
      const node = nodeMap.get(tab.id)
      if (node) {
        if (tab.title !== node.title || tab.contentFormat !== node.contentFormat) {
          tab.title = node.title
          tab.contentFormat = node.contentFormat
          changed = true
        }
      }
    }

    if (changed) {
      saveTabsToStorage(projectId, projectTabs)
    }
  }

  function removeTab(projectId: number, documentId: number) {
    const projectTabs = getTabs(projectId)
    const index = projectTabs.findIndex((t) => t.id === documentId)
    if (index !== -1) {
      projectTabs.splice(index, 1)
      saveTabsToStorage(projectId, projectTabs)
    }
  }

  return {
    tabsByProject,
    getTabs,
    openTab,
    togglePinTab,
    moveTab,
    closeTab,
    closeOtherTabs,
    closeTabsToRight,
    closeAllTabs,
    updateTabTitle,
    syncTabsWithTree,
    removeTab
  }
})
