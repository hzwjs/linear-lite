import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DocumentContentFormat } from '../types/document'

export interface DocumentTabItem {
  id: number
  projectId: number
  title: string
  contentFormat: DocumentContentFormat
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
    return parsed.filter((item): item is DocumentTabItem =>
      typeof item === 'object' &&
      item != null &&
      Number.isInteger(item.id) &&
      Number.isInteger(item.projectId) &&
      item.projectId === projectId &&
      typeof item.title === 'string' &&
      (item.contentFormat === 'BLOCKNOTE' || item.contentFormat === 'HTML')
    )
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
          contentFormat: tab.contentFormat || current.contentFormat
        }
      }
    } else {
      projectTabs.push({ ...tab })
    }
    saveTabsToStorage(tab.projectId, projectTabs)
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
    tabsByProject.value[projectId] = [current]
    saveTabsToStorage(projectId, tabsByProject.value[projectId])
  }

  function closeAllTabs(projectId: number) {
    tabsByProject.value[projectId] = []
    saveTabsToStorage(projectId, [])
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
    closeTab,
    closeOtherTabs,
    closeAllTabs,
    updateTabTitle,
    syncTabsWithTree,
    removeTab
  }
})
