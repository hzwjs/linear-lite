import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Task, Status, Priority } from '../types/domain'
import { taskApi } from '../services/api/task'
import { useProjectStore } from './projectStore'
import { useViewModeStore } from './viewModeStore'
import { useFavoriteStore } from './favoriteStore'
import { toApiDateTime } from '../utils/taskDate'
import { translate } from '../utils/i18n'

/**
 * 任务状态。数据源为后端 API（按 activeProjectId 过滤），不再使用 localStorage。
 */
export const useTaskStore = defineStore('taskStore', () => {
  const tasks = ref<Task[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const currentTaskId = ref<string | null>(null)
  const searchQuery = ref('')
  const filterStatus = ref<Status | null>(null)
  const filterPriority = ref<Priority | null>(null)

  const currentTask = computed(() => {
    if (!currentTaskId.value) return null
    return tasks.value.find((t) => t.id === currentTaskId.value) ?? null
  })

  const filteredTasks = computed(() => {
    let result = [...tasks.value].sort((a, b) => b.updatedAt - a.updatedAt)
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      result = result.filter((t) => t.title.toLowerCase().includes(q))
    }
    if (filterStatus.value) {
      result = result.filter((t) => t.status === filterStatus.value)
    }
    if (filterPriority.value) {
      result = result.filter((t) => t.priority === filterPriority.value)
    }
    return result
  })

  const groupedTasks = computed(() => {
    const list = filteredTasks.value
    return {
      todo: list.filter((t) => t.status === 'todo'),
      in_progress: list.filter((t) => t.status === 'in_progress'),
      done: list.filter((t) => t.status === 'done')
    }
  })

  const isEmpty = computed(() => tasks.value.length === 0)
  const isFilterEmpty = computed(
    () => tasks.value.length > 0 && filteredTasks.value.length === 0
  )

  function recomputeParentSubIssueProgress(parentNumericId: string | number | null | undefined) {
    if (parentNumericId == null) return
    const parentIdStr = String(parentNumericId)
    const parentIndex = tasks.value.findIndex((task) => String(task.numericId) === parentIdStr)
    if (parentIndex === -1) return

    const children = tasks.value.filter((task) => task.parentId != null && String(task.parentId) === parentIdStr)
    const completedChildren = children.filter((task) => task.status === 'done').length
    const parent = tasks.value[parentIndex]
    if (!parent) return

    tasks.value[parentIndex] = {
      ...parent,
      subIssueCount: children.length,
      completedSubIssueCount: completedChildren
    }
  }

  async function fetchTasks() {
    const projectStore = useProjectStore()
    const viewModeStore = useViewModeStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      tasks.value = []
      error.value = null
      isLoading.value = false
      return
    }
    isLoading.value = true
    error.value = null
    try {
      const showSubIssues = viewModeStore.viewConfig.showSubIssues
      tasks.value = await taskApi.list(projectId, {
        topLevelOnly: !showSubIssues
      })
    } catch (err: unknown) {
      error.value =
        err instanceof Error
          ? err.message
          : translate('taskStore.errors.loadFailed', undefined, 'Failed to load tasks.')
    } finally {
      isLoading.value = false
    }
  }

  /** 拉取指定父任务的子任务（parentId 为父任务数据库 id） */
  async function fetchSubIssues(parentNumericId: number): Promise<Task[]> {
    const projectStore = useProjectStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) return []
    const list = await taskApi.list(projectId, { parentId: parentNumericId })
    return list
  }

  /** parentId 为父任务数据库 id（number），非 task_key */
  async function createTask(
    data: Omit<Task, 'id' | 'createdAt' | 'updatedAt' | 'parentId'> & { parentId?: number | null }
  ) {
    const projectStore = useProjectStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      const e = new Error(translate('taskStore.errors.noProject', undefined, 'No project selected.'))
      error.value = e.message
      throw e
    }
    error.value = null
    try {
      const newTask = await taskApi.create({
        projectId,
        title: data.title,
        description: data.description,
        status: data.status,
        priority: data.priority,
        assigneeId: data.assigneeId ?? null,
        dueDate: toApiDateTime(data.dueDate),
        plannedStartDate: toApiDateTime(data.plannedStartDate),
        parentId: data.parentId ?? undefined,
        progressPercent: data.progressPercent ?? 0
      })
      tasks.value = [newTask, ...tasks.value]
      return newTask
    } catch (err: unknown) {
      error.value =
        err instanceof Error
          ? err.message
          : translate('taskStore.errors.createFailed', undefined, 'Failed to create task.')
      throw err
    }
  }

  /**
   * 同步合并到内存任务列表（不请求网络）。updateTask 会先调用此方法再 PUT，避免防抖未触发时关抽屉已丢变更。
   */
  function applyLocalTaskPatch(
    id: string,
    updates: Partial<Omit<Task, 'id' | 'createdAt'>> & {
      clearAssignee?: boolean
      clearPlannedStart?: boolean
      clearDueDate?: boolean
    }
  ) {
    const index = tasks.value.findIndex((t) => t.id === id)
    if (index === -1) return
    const prev = tasks.value[index]
    if (prev === undefined) return
    const next: Task = { ...prev, updatedAt: Date.now() }
    if (updates.title !== undefined) next.title = updates.title
    if (updates.description !== undefined) next.description = updates.description
    if (updates.status !== undefined) next.status = updates.status
    if (updates.priority !== undefined) next.priority = updates.priority
    if (updates.clearAssignee === true) {
      next.assigneeId = undefined
      next.assigneeDisplayName = undefined
    } else if (updates.assigneeId !== undefined) {
      next.assigneeId = updates.assigneeId
      if (updates.assigneeId != null) {
        next.assigneeDisplayName = undefined
      }
    }
    if (updates.clearDueDate === true) {
      next.dueDate = undefined
    } else if (updates.dueDate !== undefined) {
      next.dueDate = updates.dueDate
    }
    if (updates.clearPlannedStart === true) {
      next.plannedStartDate = undefined
    } else if (updates.plannedStartDate !== undefined) {
      next.plannedStartDate = updates.plannedStartDate
    }
    if (updates.parentId !== undefined) next.parentId = updates.parentId
    if (updates.progressPercent !== undefined) next.progressPercent = updates.progressPercent
    if (updates.projectId !== undefined) next.projectId = updates.projectId
    if (updates.creatorId !== undefined) next.creatorId = updates.creatorId
    if (updates.completedAt !== undefined) next.completedAt = updates.completedAt
    tasks.value[index] = next
    recomputeParentSubIssueProgress(prev.parentId)
    recomputeParentSubIssueProgress(next.parentId)
    if (next.favorited) {
      useFavoriteStore().syncTask(next)
    }
  }

  async function updateTask(
    id: string,
    updates: Partial<Omit<Task, 'id' | 'createdAt'>> & {
      clearAssignee?: boolean
      clearPlannedStart?: boolean
      clearDueDate?: boolean
    }
  ) {
    applyLocalTaskPatch(id, updates)
    error.value = null
    try {
      const existing = tasks.value.find((t) => t.id === id) ?? null
      const updated = await taskApi.update(id, {
        title: updates.title,
        description: updates.description,
        status: updates.status,
        priority: updates.priority,
        assigneeId: updates.assigneeId,
        clearAssignee: updates.clearAssignee,
        dueDate: toApiDateTime(updates.dueDate),
        clearDueDate: updates.clearDueDate,
        parentId: updates.parentId,
        plannedStartDate: toApiDateTime(updates.plannedStartDate),
        clearPlannedStart: updates.clearPlannedStart,
        progressPercent: updates.progressPercent
      })
      const index = tasks.value.findIndex((t) => t.id === id)
      const merged = {
        ...existing,
        ...updated,
        favorited: updated.favorited ?? existing?.favorited ?? false
      }
      if (index !== -1) tasks.value[index] = merged
      recomputeParentSubIssueProgress(existing?.parentId)
      recomputeParentSubIssueProgress(updated.parentId)
      if (merged.favorited) {
        useFavoriteStore().syncTask(merged)
      }
      return merged
    } catch (err: unknown) {
      error.value =
        err instanceof Error
          ? err.message
          : translate('taskStore.errors.updateFailed', undefined, 'Failed to update task.')
      throw err
    }
  }

  async function transitionTask(id: string, newStatus: Status) {
    return updateTask(id, { status: newStatus })
  }

  return {
    tasks,
    isLoading,
    error,
    currentTaskId,
    searchQuery,
    filterStatus,
    filterPriority,
    currentTask,
    filteredTasks,
    groupedTasks,
    isEmpty,
    isFilterEmpty,
    fetchTasks,
    fetchSubIssues,
    createTask,
    applyLocalTaskPatch,
    updateTask,
    transitionTask
  }
})
