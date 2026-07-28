import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Task, Status, Priority } from '../types/domain'

/** 处理人筛选项：'unassigned' 无负责人；number 为 assigneeId */
export type AssigneeFilterItem = 'unassigned' | number
import { taskApi } from '../services/api/task'
import type { TaskAncestorStateChange } from '../services/api/task'
import type { TaskLabelWriteItem, UpdateTaskRequest } from '../services/api/types'
import { useProjectStore } from './projectStore'
import { useFavoriteStore } from './favoriteStore'
import { toApiDateTime } from '../utils/taskDate'
import { translate } from '../utils/i18n'

const TASK_SNAPSHOT_PREFIX = 'linear-lite:tasks:v1:'

function taskSnapshotKey(projectId: number): string {
  return `${TASK_SNAPSHOT_PREFIX}${projectId}`
}

function readTaskSnapshot(projectId: number): Task[] | null {
  try {
    const raw = localStorage.getItem(taskSnapshotKey(projectId))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return null
    return parsed as Task[]
  } catch {
    return null
  }
}

function writeTaskSnapshot(projectId: number, list: Task[]): void {
  try {
    localStorage.setItem(taskSnapshotKey(projectId), JSON.stringify(list))
  } catch {
    // Cache persistence is best-effort. The network response remains source of truth.
  }
}

function createOptimisticTaskId(projectId: number): string {
  return `optimistic-${projectId}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 任务状态。后端 API 仍是最终来源；项目任务快照只用于刷新期间先渲染本地可用数据。
 */
export const useTaskStore = defineStore('taskStore', () => {
  const tasks = ref<Task[]>([])
  const taskByKeyCache = ref<Record<string, Task>>({})
  const taskListSnapshots = new Map<number, Task[]>()
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
  type TaskUpdatePatch = Partial<Omit<Task, 'id' | 'createdAt' | 'labels'>> & {
    clearAssignee?: boolean
    clearPlannedStart?: boolean
    clearDueDate?: boolean
    clearParent?: boolean
    labels?: TaskLabelWriteItem[]
  }

  type DrainResult =
    | { ok: true; task: Task }
    | { ok: false; reason: 'timeout' | 'save_failed'; task: Task; lastError?: Error }

  type LaneIntent = {
    resolve: (task: Task) => void
    reject: (error: unknown) => void
  }

  type TaskSaveLane = {
    ackBase: Task | null
    inFlightPatch: TaskUpdatePatch | null
    pendingPatch: TaskUpdatePatch | null
    inFlightIntents: LaneIntent[]
    pendingIntents: LaneIntent[]
    inFlightPromise: Promise<void> | null
    lastError?: Error
    hasUnsavedFailure: boolean
  }

  const saveLanes = new Map<string, TaskSaveLane>()

  function mergeTaskWithCachedDetail(task: Task): Task {
    const cached = taskByKeyCache.value[task.id]
    if (!cached) return task
    if (task.description !== undefined || cached.description === undefined) return task
    return { ...cached, ...task, description: cached.description }
  }

  const currentTask = computed(() => {
    if (!currentTaskId.value) return null
    const row = tasks.value.find((t) => t.id === currentTaskId.value)
    if (row) return mergeTaskWithCachedDetail(row)
    return taskByKeyCache.value[currentTaskId.value] ?? null
  })

  function cacheTask(task: Task) {
    const next = mergeTaskWithCachedDetail(task)
    taskByKeyCache.value = {
      ...taskByKeyCache.value,
      [next.id]: next
    }
  }

  function rememberProjectTasks(projectId: number, list: Task[]) {
    const cloned = list.map(cloneTask)
    taskListSnapshots.set(projectId, cloned)
    writeTaskSnapshot(projectId, cloned)
  }

  function getProjectSnapshot(projectId: number): Task[] | null {
    const cached = taskListSnapshots.get(projectId)
    if (cached != null) return cached.map(cloneTask)
    const persisted = readTaskSnapshot(projectId)
    if (persisted == null) return null
    taskListSnapshots.set(projectId, persisted.map(cloneTask))
    return persisted.map(cloneTask)
  }

  function syncCurrentProjectSnapshot() {
    const projectId = useProjectStore().activeProjectId
    if (projectId == null) return
    rememberProjectTasks(projectId, tasks.value)
  }

  function cloneTask(task: Task): Task {
    return {
      ...task,
      labels: task.labels?.map((l) => ({ ...l }))
    }
  }

  function normalizePatch(patch: TaskUpdatePatch): TaskUpdatePatch {
    const next: TaskUpdatePatch = { ...patch }
    if (next.clearAssignee === true) delete next.assigneeId
    if (next.clearDueDate === true) delete next.dueDate
    if (next.clearPlannedStart === true) delete next.plannedStartDate
    if (next.clearParent === true) delete next.parentId
    return next
  }

  function applyPatchToTask(base: Task, patchInput: TaskUpdatePatch): Task {
    const patch = normalizePatch(patchInput)
    const next: Task = { ...base, updatedAt: Date.now() }
    if (patch.title !== undefined) next.title = patch.title
    if (patch.description !== undefined) next.description = patch.description
    if (patch.status !== undefined) next.status = patch.status
    if (patch.priority !== undefined) next.priority = patch.priority
    if (patch.clearAssignee === true) {
      next.assigneeId = undefined
      next.assigneeDisplayName = undefined
    } else if (patch.assigneeId !== undefined) {
      next.assigneeId = patch.assigneeId
      if (patch.assigneeId != null) next.assigneeDisplayName = undefined
    }
    if (patch.clearDueDate === true) next.dueDate = undefined
    else if (patch.dueDate !== undefined) next.dueDate = patch.dueDate
    if (patch.clearPlannedStart === true) next.plannedStartDate = undefined
    else if (patch.plannedStartDate !== undefined) next.plannedStartDate = patch.plannedStartDate
    if (patch.clearParent === true) next.parentId = null
    else if (patch.parentId !== undefined) next.parentId = patch.parentId
    if (patch.progressPercent !== undefined) next.progressPercent = patch.progressPercent
    if (patch.projectId !== undefined) next.projectId = patch.projectId
    if (patch.creatorId !== undefined) next.creatorId = patch.creatorId
    if (patch.completedAt !== undefined) next.completedAt = patch.completedAt
    if (patch.labels !== undefined) {
      next.labels = patch.labels.flatMap((l) => {
        if ('id' in l) {
          const numericId = Number(l.id)
          const existingName = base.labels?.find((label) => label.id === numericId)?.name
          const fallbackName =
            typeof (l as { name?: unknown }).name === 'string' &&
            (l as { name?: string }).name?.trim()
              ? (l as { name?: string }).name!.trim()
              : String(numericId)
          return [{ id: numericId, name: existingName ?? fallbackName }]
        }
        const trimmed = l.name.trim()
        if (!trimmed) return []
        // name-only 标签在服务端返回前没有真实 id，按名称生成稳定负数占位。
        return [{ id: toOptimisticLabelId(trimmed), name: trimmed }]
      })
    }
    const completionGroupTouched = patch.status !== undefined || patch.progressPercent !== undefined
    if (completionGroupTouched && next.status !== 'done') {
      next.completedAt = null
    }
    return next
  }

  function mergePatches(
    current: TaskUpdatePatch | null,
    incoming: TaskUpdatePatch
  ): TaskUpdatePatch {
    return normalizePatch({ ...(current ?? {}), ...incoming })
  }

  function toOptimisticLabelId(name: string): number {
    let hash = 0
    for (let i = 0; i < name.length; i++) {
      hash = ((hash << 5) - hash + name.charCodeAt(i)) | 0
    }
    const normalized = Math.abs(hash) || 1
    return -normalized
  }

  function getTaskIndexById(id: string): number {
    return tasks.value.findIndex((t) => t.id === id)
  }

  function upsertTaskRow(id: string, next: Task) {
    const index = getTaskIndexById(id)
    if (index === -1) return
    const prev = tasks.value[index]
    if (prev == null) return
    tasks.value[index] = next
    cacheTask(next)
    recomputeParentSubIssueProgress(prev.parentId)
    recomputeParentSubIssueProgress(next.parentId)
    if (next.favorited) useFavoriteStore().syncTask(next)
    syncCurrentProjectSnapshot()
  }

  function getOrCreateLane(id: string): TaskSaveLane {
    let lane = saveLanes.get(id)
    if (lane) return lane
    const index = getTaskIndexById(id)
    lane = {
      ackBase: index === -1 ? null : cloneTask(tasks.value[index]!),
      inFlightPatch: null,
      pendingPatch: null,
      inFlightIntents: [],
      pendingIntents: [],
      inFlightPromise: null,
      hasUnsavedFailure: false
    }
    saveLanes.set(id, lane)
    return lane
  }

  function syncLaneRow(id: string) {
    const lane = saveLanes.get(id)
    if (!lane || lane.ackBase == null) return
    let next = cloneTask(lane.ackBase)
    if (lane.inFlightPatch != null) next = applyPatchToTask(next, lane.inFlightPatch)
    if (lane.pendingPatch != null) next = applyPatchToTask(next, lane.pendingPatch)
    upsertTaskRow(id, next)
  }

  function toParentId(value: TaskUpdatePatch['parentId']): number | null | undefined {
    if (value === undefined) return undefined
    if (value === null) return null
    const n = Number(value)
    return Number.isFinite(n) ? n : undefined
  }

  function toUpdateRequest(patchInput: TaskUpdatePatch): UpdateTaskRequest {
    const patch = normalizePatch(patchInput)
    const parentId = toParentId(patch.parentId)
    const body: UpdateTaskRequest = {
      title: patch.title,
      description: patch.description,
      status: patch.status,
      priority: patch.priority,
      assigneeId: patch.assigneeId,
      clearAssignee: patch.clearAssignee,
      dueDate: toApiDateTime(patch.dueDate),
      clearDueDate: patch.clearDueDate,
      clearParent: patch.clearParent,
      plannedStartDate: toApiDateTime(patch.plannedStartDate),
      clearPlannedStart: patch.clearPlannedStart,
      progressPercent: patch.progressPercent,
      ...(patch.labels !== undefined ? { labels: patch.labels } : {})
    }
    if (patch.clearParent === true) {
      delete body.parentId
    } else if (parentId !== undefined) {
      body.parentId = parentId
    }
    return body
  }

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
    const terminalStatuses = new Set<Status>(['done', 'canceled', 'duplicate'])
    const completedChildren = children.filter((task) => terminalStatuses.has(task.status)).length
    const parent = tasks.value[parentIndex]
    if (!parent) return

    tasks.value[parentIndex] = {
      ...parent,
      subIssueCount: children.length,
      completedSubIssueCount: completedChildren
    }
  }

  function mergeAutoCompletedAncestors(changes: TaskAncestorStateChange[]) {
    for (const change of changes) {
      const index = getTaskIndexById(change.id)
      if (index === -1) continue
      const current = tasks.value[index]
      if (current == null) continue
      const next: Task = {
        ...current,
        status: change.status,
        progressPercent: change.progressPercent,
        completedAt: change.completedAt,
        updatedAt: change.updatedAt
      }
      const lane = saveLanes.get(change.id)
      if (lane?.ackBase != null) {
        // Only acknowledge fields authored by the server-side ancestor transition;
        // unrelated optimistic edits in the current row must remain rollback-capable.
        lane.ackBase = {
          ...lane.ackBase,
          status: change.status,
          progressPercent: change.progressPercent,
          completedAt: change.completedAt,
          updatedAt: change.updatedAt
        }
        syncLaneRow(change.id)
        continue
      }
      tasks.value[index] = next
      cacheTask(next)
      recomputeParentSubIssueProgress(next.parentId)
    }
    syncCurrentProjectSnapshot()
  }

  async function fetchTasks() {
    const projectStore = useProjectStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      saveLanes.clear()
      tasks.value = []
      error.value = null
      isLoading.value = false
      return
    }
    /** 本次请求对应的项目；切换项目后迟到的响应不得写回列表或关掉新请求的 loading */
    const requestedProjectId = projectId
    isLoading.value = true
    error.value = null
    saveLanes.clear()
    const snapshot = getProjectSnapshot(requestedProjectId)
    if (snapshot != null) {
      tasks.value = snapshot
      for (const task of snapshot) cacheTask(task)
    } else {
      tasks.value = []
    }
    try {
      const list = await taskApi.list(requestedProjectId, { topLevelOnly: false })
      if (useProjectStore().activeProjectId !== requestedProjectId) return
      const mergedList = list.map(mergeTaskWithCachedDetail)
      tasks.value = mergedList
      rememberProjectTasks(requestedProjectId, mergedList)
      for (const task of mergedList) {
        cacheTask(task)
        const lane = saveLanes.get(task.id)
        if (lane) lane.ackBase = cloneTask(task)
      }
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

  async function fetchTaskByKey(taskKey: string): Promise<Task> {
    const task = await taskApi.get(taskKey)
    cacheTask(task)
    const index = tasks.value.findIndex((item) => item.id === task.id)
    if (index === -1) {
      tasks.value = [task, ...tasks.value]
    } else {
      tasks.value[index] = task
    }
    const lane = saveLanes.get(task.id)
    if (lane != null && lane.inFlightPatch == null && lane.pendingPatch == null) {
      lane.ackBase = cloneTask(task)
      lane.hasUnsavedFailure = false
    }
    return task
  }

  /** 拉取指定父任务的子任务（parentId 为父任务数据库 id） */
  async function fetchSubIssues(parentNumericId: number, projectIdOverride?: number | null): Promise<Task[]> {
    const projectStore = useProjectStore()
    const projectId = projectIdOverride ?? projectStore.activeProjectId
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
    const createdAt = Date.now()
    const optimisticTask: Task = {
      id: createOptimisticTaskId(projectId),
      title: data.title,
      description: data.description,
      status: data.status,
      priority: data.priority,
      projectId,
      creatorId: data.creatorId,
      assigneeId: data.assigneeId ?? null,
      dueDate: data.dueDate,
      plannedStartDate: data.plannedStartDate,
      parentId: data.parentId != null ? String(data.parentId) : undefined,
      progressPercent: data.progressPercent ?? 0,
      completedAt: data.completedAt,
      createdAt,
      updatedAt: createdAt,
      favorited: false,
      labels: data.labels?.map((l) => ({ ...l }))
    }
    tasks.value = [optimisticTask, ...tasks.value]
    cacheTask(optimisticTask)
    syncCurrentProjectSnapshot()
    try {
      const mutation = await taskApi.create({
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
      const newTask = mutation.task
      const optimisticIndex = tasks.value.findIndex((task) => task.id === optimisticTask.id)
      if (optimisticIndex === -1) {
        tasks.value = [newTask, ...tasks.value]
      } else {
        tasks.value[optimisticIndex] = newTask
      }
      cacheTask(newTask)
      recomputeParentSubIssueProgress(newTask.parentId)
      mergeAutoCompletedAncestors(mutation.autoCompletedAncestors)
      syncCurrentProjectSnapshot()
      return newTask
    } catch (err: unknown) {
      tasks.value = tasks.value.filter((task) => task.id !== optimisticTask.id)
      syncCurrentProjectSnapshot()
      error.value =
        err instanceof Error
          ? err.message
          : translate('taskStore.errors.createFailed', undefined, 'Failed to create task.')
      throw err
    }
  }

  /**
   * 同步合并到内存任务列表（不请求网络）。
   */
  function applyLocalTaskPatch(id: string, updates: TaskUpdatePatch) {
    const index = getTaskIndexById(id)
    if (index === -1) return
    const current = tasks.value[index]
    if (current == null) return
    const next = applyPatchToTask(current, updates)
    upsertTaskRow(id, next)
  }

  async function pumpTaskLane(id: string): Promise<void> {
    const lane = getOrCreateLane(id)
    if (lane.inFlightPromise != null || lane.pendingPatch == null || lane.ackBase == null) return

    lane.inFlightPatch = lane.pendingPatch
    lane.pendingPatch = null
    lane.inFlightIntents = lane.pendingIntents
    lane.pendingIntents = []
    syncLaneRow(id)
    const requestBody = toUpdateRequest(lane.inFlightPatch)

    lane.inFlightPromise = taskApi
      .update(id, requestBody)
      .then((mutation) => {
        const updated = mutation.task
        mergeAutoCompletedAncestors(mutation.autoCompletedAncestors)
        lane.ackBase = cloneTask(updated)
        lane.hasUnsavedFailure = false
        lane.lastError = undefined
        const favorite = updated.favorited ?? tasks.value[getTaskIndexById(id)]?.favorited ?? false
        lane.ackBase.favorited = favorite
        syncLaneRow(id)
        const index = getTaskIndexById(id)
        const row = index === -1 ? lane.ackBase : tasks.value[index]!
        const intents = lane.inFlightIntents.splice(0)
        intents.forEach((intent) => intent.resolve(row))
      })
      .catch((err: unknown) => {
        const resolvedError =
          err instanceof Error
            ? err
            : new Error(translate('taskStore.errors.updateFailed', undefined, 'Failed to update task.'))
        lane.lastError = resolvedError
        lane.hasUnsavedFailure = true
        const intents = lane.inFlightIntents.splice(0)
        intents.forEach((intent) => intent.reject(resolvedError))
      })
      .finally(() => {
        lane.inFlightPatch = null
        lane.inFlightPromise = null
        syncLaneRow(id)
      })

    await lane.inFlightPromise
    if (lane.pendingPatch != null) {
      await pumpTaskLane(id)
    }
  }

  function enqueueTaskUpdate(
    id: string,
    updates: TaskUpdatePatch
  ): Promise<Task> {
    const lane = getOrCreateLane(id)
    if (lane.ackBase == null) {
      const index = getTaskIndexById(id)
      if (index === -1) {
        const cached = taskByKeyCache.value[id]
        if (cached == null) {
          return Promise.reject(new Error(`Task not found: ${id}`))
        }
        lane.ackBase = cloneTask(cached)
      } else {
        lane.ackBase = cloneTask(tasks.value[index]!)
      }
    }
    lane.pendingPatch = mergePatches(lane.pendingPatch, updates)
    const p = new Promise<Task>((resolve, reject) => {
      lane.pendingIntents.push({ resolve, reject })
    })
    syncLaneRow(id)
    void pumpTaskLane(id)
    return p
  }

  async function updateTask(id: string, updates: TaskUpdatePatch) {
    return enqueueTaskUpdate(id, updates)
  }

  function flushTask(id: string): void {
    void pumpTaskLane(id)
  }

  async function drainTask(id: string, opts?: { timeoutMs?: number }): Promise<DrainResult> {
    const lane = saveLanes.get(id)
    const index = getTaskIndexById(id)
    if (!lane || index === -1) {
      const fallback = index === -1 ? taskByKeyCache.value[id] : tasks.value[index]
      if (fallback == null) {
        throw new Error(`Task not found: ${id}`)
      }
      return { ok: true, task: fallback }
    }
    const timeoutMs = opts?.timeoutMs ?? 5000
    const startedAt = Date.now()
    while (true) {
      const idle = lane.inFlightPatch == null && lane.pendingPatch == null && lane.inFlightPromise == null
      const currentIndex = getTaskIndexById(id)
      const currentTask = currentIndex === -1 ? lane.ackBase : tasks.value[currentIndex] ?? lane.ackBase
      if (idle) {
        if (currentTask == null) {
          throw new Error(`Task not found: ${id}`)
        }
        if (lane.hasUnsavedFailure) {
          return {
            ok: false,
            reason: 'save_failed',
            task: currentTask,
            lastError: lane.lastError
          }
        }
        return { ok: true, task: currentTask }
      }
      if (Date.now() - startedAt >= timeoutMs) {
        if (currentTask == null) {
          throw new Error(`Task not found: ${id}`)
        }
        return {
          ok: false,
          reason: 'timeout',
          task: currentTask,
          lastError: lane.lastError
        }
      }
      if (lane.inFlightPromise != null) {
        await lane.inFlightPromise
      } else {
        await new Promise((resolve) => setTimeout(resolve, 10))
      }
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
    taskByKeyCache,
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
    fetchTaskByKey,
    fetchSubIssues,
    createTask,
    applyLocalTaskPatch,
    enqueueTaskUpdate,
    updateTask,
    flushTask,
    drainTask,
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
