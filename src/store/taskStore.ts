import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Task, Status, Priority } from '../types/domain'

/** 处理人筛选项：'unassigned' 无负责人；number 为 assigneeId */
export type AssigneeFilterItem = 'unassigned' | number
import { taskApi } from '../services/api/task'
import type { TaskLabelWriteItem } from '../services/api/types'
import { useProjectStore } from './projectStore'
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
  /** 按状态多选筛选，语义为 OR */
  const filterStatusList = ref<Status[]>([])
  /** 按优先级多选筛选，语义为 OR */
  const filterPriorityList = ref<Priority[]>([])
  /** 按负责人多选筛选，语义为 OR */
  const filterAssigneeList = ref<AssigneeFilterItem[]>([])
  /** 选中系统用户负责人时其 username 的小写形式映射，用于匹配仅 assigneeDisplayName 的导入任务 */
  const filterAssigneeUsernameNormMap = ref<Map<number, string>>(new Map())
  /** 按标签 id 多选筛选，语义为 OR（至少命中其一） */
  const filterLabelIds = ref<number[]>([])

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
    const statusList = filterStatusList.value
    if (statusList.length > 0) {
      const wanted = new Set(statusList)
      result = result.filter((t) => wanted.has(t.status))
    }
    const priorityList = filterPriorityList.value
    if (priorityList.length > 0) {
      const wanted = new Set(priorityList)
      result = result.filter((t) => wanted.has(t.priority))
    }
    const assigneeList = filterAssigneeList.value
    if (assigneeList.length > 0) {
      const hasUnassigned = assigneeList.includes('unassigned')
      const userIds = new Set(assigneeList.filter((x): x is number => typeof x === 'number'))
      const nameNormMap = filterAssigneeUsernameNormMap.value
      result = result.filter((t) => {
        if (hasUnassigned && t.assigneeId == null && !(t.assigneeDisplayName?.trim())) {
          return true
        }
        if (t.assigneeId != null && userIds.has(Number(t.assigneeId))) {
          return true
        }
        if (t.assigneeId == null && t.assigneeDisplayName?.trim()) {
          const ext = t.assigneeDisplayName.trim().toLowerCase()
          for (const uid of userIds) {
            const norm = nameNormMap.get(uid)
            if (norm && ext === norm) return true
          }
        }
        return false
      })
    }
    const labelIds = filterLabelIds.value
    if (labelIds.length > 0) {
      const wanted = new Set(labelIds)
      result = result.filter((t) => t.labels?.some((l) => wanted.has(l.id)))
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
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      tasks.value = []
      error.value = null
      isLoading.value = false
      return
    }
    /** 本次请求对应的项目；切换项目后迟到的响应不得写回列表或关掉新请求的 loading */
    const requestedProjectId = projectId
    isLoading.value = true
    error.value = null
    tasks.value = []
    try {
      const list = await taskApi.list(requestedProjectId, { topLevelOnly: false })
      if (useProjectStore().activeProjectId !== requestedProjectId) return
      tasks.value = list
    } catch (err: unknown) {
      if (useProjectStore().activeProjectId !== requestedProjectId) return
      error.value =
        err instanceof Error
          ? err.message
          : translate('taskStore.errors.loadFailed', undefined, 'Failed to load tasks.')
    } finally {
      if (useProjectStore().activeProjectId === requestedProjectId) {
        isLoading.value = false
      }
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
    updates: Partial<Omit<Task, 'id' | 'createdAt' | 'labels'>> & {
      clearAssignee?: boolean
      clearPlannedStart?: boolean
      clearDueDate?: boolean
      labels?: TaskLabelWriteItem[]
    }
  ) {
    const { labels: labelsPayload, ...patchForLocal } = updates
    applyLocalTaskPatch(id, patchForLocal)
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
        progressPercent: updates.progressPercent,
        ...(labelsPayload !== undefined ? { labels: labelsPayload } : {})
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

  /** 项目内删除标签定义后，从内存中所有该项目的任务上摘掉该标签 */
  function stripProjectLabelFromTasks(projectId: number, labelId: number) {
    for (let i = 0; i < tasks.value.length; i++) {
      const t = tasks.value[i]
      if (t == null || t.projectId !== projectId) continue
      const labels = t.labels
      if (labels == null || labels.length === 0) continue
      const nextLabels = labels.filter((l) => l.id !== labelId)
      if (nextLabels.length === labels.length) continue
      const next = { ...t, labels: nextLabels, updatedAt: Date.now() }
      tasks.value[i] = next
      recomputeParentSubIssueProgress(t.parentId)
      if (next.favorited) {
        useFavoriteStore().syncTask(next)
      }
    }
    filterLabelIds.value = filterLabelIds.value.filter((id) => id !== labelId)
  }

  function toggleFilterStatus(status: Status) {
    const cur = filterStatusList.value
    const i = cur.indexOf(status)
    if (i === -1) filterStatusList.value = [...cur, status]
    else filterStatusList.value = cur.filter((s) => s !== status)
  }

  function toggleFilterPriority(priority: Priority) {
    const cur = filterPriorityList.value
    const i = cur.indexOf(priority)
    if (i === -1) filterPriorityList.value = [...cur, priority]
    else filterPriorityList.value = cur.filter((p) => p !== priority)
  }

  function toggleFilterAssignee(item: AssigneeFilterItem, usernameNorm?: string) {
    const cur = filterAssigneeList.value
    const i = cur.indexOf(item)
    if (i === -1) {
      filterAssigneeList.value = [...cur, item]
      if (typeof item === 'number' && usernameNorm) {
        filterAssigneeUsernameNormMap.value.set(item, usernameNorm)
      }
    } else {
      filterAssigneeList.value = cur.filter((x) => x !== item)
      if (typeof item === 'number') {
        filterAssigneeUsernameNormMap.value.delete(item)
      }
    }
  }

  function toggleFilterLabelId(labelId: number) {
    const cur = filterLabelIds.value
    const i = cur.indexOf(labelId)
    if (i === -1) filterLabelIds.value = [...cur, labelId]
    else filterLabelIds.value = cur.filter((id) => id !== labelId)
  }

  function removeFilterLabelId(labelId: number) {
    filterLabelIds.value = filterLabelIds.value.filter((id) => id !== labelId)
  }

  function clearIssueFilters() {
    filterStatusList.value = []
    filterPriorityList.value = []
    filterAssigneeList.value = []
    filterAssigneeUsernameNormMap.value = new Map()
    filterLabelIds.value = []
  }

  return {
    tasks,
    isLoading,
    error,
    currentTaskId,
    searchQuery,
    filterStatusList,
    filterPriorityList,
    filterAssigneeList,
    filterAssigneeUsernameNormMap,
    filterLabelIds,
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
    transitionTask,
    stripProjectLabelFromTasks,
    toggleFilterStatus,
    toggleFilterPriority,
    toggleFilterAssignee,
    toggleFilterLabelId,
    removeFilterLabelId,
    clearIssueFilters
  }
})
