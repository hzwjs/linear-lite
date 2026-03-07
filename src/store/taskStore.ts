import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Task, Status, Priority } from '../types/domain'
import { taskApi } from '../services/api/task'
import { useProjectStore } from './projectStore'

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

  async function fetchTasks() {
    const projectStore = useProjectStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      tasks.value = []
      return
    }
    isLoading.value = true
    error.value = null
    try {
      tasks.value = await taskApi.list(projectId)
    } catch (err: unknown) {
      error.value =
        err instanceof Error ? err.message : 'Failed to load tasks.'
    } finally {
      isLoading.value = false
    }
  }

  async function createTask(
    data: Omit<Task, 'id' | 'createdAt' | 'updatedAt'>
  ) {
    const projectStore = useProjectStore()
    const projectId = projectStore.activeProjectId
    if (projectId == null) {
      const e = new Error('No project selected.')
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
        dueDate: data.dueDate != null ? new Date(data.dueDate).toISOString() : undefined
      })
      tasks.value = [newTask, ...tasks.value]
      return newTask
    } catch (err: unknown) {
      error.value =
        err instanceof Error ? err.message : 'Failed to create task.'
      throw err
    }
  }

  async function updateTask(
    id: string,
    updates: Partial<Omit<Task, 'id' | 'createdAt'>>
  ) {
    error.value = null
    try {
      const updated = await taskApi.update(id, {
        title: updates.title,
        description: updates.description,
        status: updates.status,
        priority: updates.priority,
        assigneeId: updates.assigneeId,
        dueDate:
          updates.dueDate != null
            ? new Date(updates.dueDate).toISOString()
            : undefined
      })
      const index = tasks.value.findIndex((t) => t.id === id)
      if (index !== -1) tasks.value[index] = updated
      return updated
    } catch (err: unknown) {
      error.value =
        err instanceof Error ? err.message : 'Failed to update task.'
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
    createTask,
    updateTask,
    transitionTask
  }
})
