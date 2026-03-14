import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Task } from '../types/domain'
import { taskApi } from '../services/api/task'
import { useTaskStore } from './taskStore'

export const useFavoriteStore = defineStore('favoriteStore', () => {
  const favorites = ref<Task[]>([])
  const isLoading = ref(false)

  const favoriteIds = computed(() => new Set(favorites.value.map((task) => task.id)))

  function isFavorite(taskId: string | null | undefined) {
    return !!taskId && favoriteIds.value.has(taskId)
  }

  function upsertFavorite(task: Task) {
    const nextTask = { ...task, favorited: true }
    const index = favorites.value.findIndex((item) => item.id === task.id)
    if (index === -1) {
      favorites.value = [nextTask, ...favorites.value]
      return
    }
    favorites.value[index] = nextTask
  }

  function removeFavorite(taskId: string) {
    favorites.value = favorites.value.filter((task) => task.id !== taskId)
  }

  function syncTask(task: Task) {
    if (task.favorited) {
      upsertFavorite(task)
    } else {
      removeFavorite(task.id)
    }
  }

  function syncTaskStore(task: Task) {
    const taskStore = useTaskStore()
    const index = taskStore.tasks.findIndex((item) => item.id === task.id)
    if (index !== -1) {
      taskStore.tasks[index] = {
        ...taskStore.tasks[index],
        ...task
      }
    }
  }

  async function fetchFavorites() {
    isLoading.value = true
    try {
      favorites.value = await taskApi.listFavorites()
      return favorites.value
    } finally {
      isLoading.value = false
    }
  }

  async function toggleFavorite(task: Task) {
    const updated = isFavorite(task.id)
      ? await taskApi.removeFavorite(task.id)
      : await taskApi.addFavorite(task.id)
    syncTask(updated)
    syncTaskStore(updated)
    return updated
  }

  return {
    favorites,
    isLoading,
    favoriteIds,
    isFavorite,
    upsertFavorite,
    removeFavorite,
    syncTask,
    fetchFavorites,
    toggleFavorite
  }
})
