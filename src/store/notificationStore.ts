import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { notificationsApi, type InAppNotificationDto } from '../services/api/notifications'
import { JWT_STORAGE_KEY } from '../services/api/constants'
import { useAuthStore } from './authStore'

function streamUrl(token: string): string {
  const q = new URLSearchParams({ access_token: token })
  return `${typeof window !== 'undefined' ? window.location.origin : ''}/api/me/notifications/stream?${q.toString()}`
}

export const useNotificationStore = defineStore('notificationStore', () => {
  const unreadCount = ref(0)
  const items = ref<InAppNotificationDto[]>([])
  const loading = ref(false)
  let eventSource: EventSource | null = null

  async function refreshUnread() {
    try {
      unreadCount.value = await notificationsApi.unreadCount()
    } catch {
      unreadCount.value = 0
    }
  }

  async function fetchList() {
    loading.value = true
    try {
      items.value = await notificationsApi.list({ limit: 40 })
    } catch {
      items.value = []
    } finally {
      loading.value = false
    }
  }

  function disconnectStream() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function connectStream() {
    disconnectStream()
    const token = localStorage.getItem(JWT_STORAGE_KEY)
    if (!token) return
    try {
      const es = new EventSource(streamUrl(token))
      eventSource = es
      es.addEventListener('notification', () => {
        void refreshUnread()
      })
      es.onerror = () => {
        es.close()
        if (eventSource === es) eventSource = null
      }
    } catch {
      disconnectStream()
    }
  }

  async function markRead(id: number) {
    await notificationsApi.markRead(id)
    await refreshUnread()
    const row = items.value.find((x) => x.id === id)
    if (row) row.readAt = new Date().toISOString()
  }

  async function markAllRead() {
    await notificationsApi.markAllRead()
    await refreshUnread()
    for (const row of items.value) {
      row.readAt = row.readAt ?? new Date().toISOString()
    }
  }

  const authStore = useAuthStore()
  watch(
    () => authStore.isLoggedIn,
    (loggedIn) => {
      if (loggedIn) {
        void refreshUnread()
        connectStream()
      } else {
        disconnectStream()
        unreadCount.value = 0
        items.value = []
      }
    },
    { immediate: true }
  )

  return {
    unreadCount,
    items,
    loading,
    refreshUnread,
    fetchList,
    connectStream,
    disconnectStream,
    markRead,
    markAllRead
  }
})
