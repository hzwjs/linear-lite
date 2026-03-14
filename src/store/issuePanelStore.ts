import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Status } from '../types/domain'

export interface ComposerDefaults {
  status?: Status
  projectId?: number
  /** Phase 7: 创建子任务时传入父任务数据库 id（number） */
  parentNumericId?: number
}

export const useIssuePanelStore = defineStore('issuePanelStore', () => {
  const isComposerOpen = ref(false)
  const composerDefaults = ref<ComposerDefaults>({})
  const workspaceTaskId = ref<string | null>(null)
  const selectedTaskId = ref<string | null>(null)

  function openComposer(defaults: ComposerDefaults = {}) {
    isComposerOpen.value = true
    composerDefaults.value = { ...defaults }
    workspaceTaskId.value = null
  }

  function closeComposer() {
    isComposerOpen.value = false
    composerDefaults.value = {}
  }

  function openWorkspace(taskId: string) {
    workspaceTaskId.value = taskId
    selectedTaskId.value = taskId
    closeComposer()
  }

  function closeWorkspace() {
    workspaceTaskId.value = null
  }

  function setSelectedTask(taskId: string | null) {
    selectedTaskId.value = taskId
  }

  function moveSelection(taskIds: string[], direction: -1 | 1) {
    if (taskIds.length === 0) {
      selectedTaskId.value = null
      return
    }

    const currentIndex = selectedTaskId.value == null ? -1 : taskIds.indexOf(selectedTaskId.value)
    if (currentIndex === -1) {
      if (selectedTaskId.value == null) {
        selectedTaskId.value = (direction === 1 ? taskIds[0] : taskIds[taskIds.length - 1]) ?? null
      } else {
        selectedTaskId.value = (direction === 1 ? taskIds[taskIds.length - 1] : taskIds[0]) ?? null
      }
      return
    }

    const nextIndex = Math.min(taskIds.length - 1, Math.max(0, currentIndex + direction))
    selectedTaskId.value = taskIds[nextIndex] ?? null
  }

  function syncSelection(taskIds: string[]) {
    if (selectedTaskId.value && !taskIds.includes(selectedTaskId.value)) {
      selectedTaskId.value = null
    }
  }

  return {
    isComposerOpen,
    composerDefaults,
    workspaceTaskId,
    selectedTaskId,
    openComposer,
    closeComposer,
    openWorkspace,
    closeWorkspace,
    setSelectedTask,
    moveSelection,
    syncSelection
  }
})
