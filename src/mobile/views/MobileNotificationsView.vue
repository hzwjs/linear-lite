<script setup lang="ts">
import { Bell, CheckCheck, ChevronRight } from 'lucide-vue-next'
import { onMounted } from 'vue'
import { useNotificationStore } from '../../store/notificationStore'
import MobileEmptyState from '../components/MobileEmptyState.vue'

const emit = defineEmits<{ open: [taskKey: string] }>()
const store = useNotificationStore()

function dateLabel(value: string) {
  const date = new Date(value)
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

async function openNotification(id: number, taskKey: string, readAt: string | null) {
  if (!readAt) await store.markRead(id)
  emit('open', taskKey)
}

onMounted(() => store.fetchList())
</script>

<template>
  <main class="mobile-notifications">
    <header class="mobile-screen-header">
      <div><span class="mobile-screen-kicker">收件箱</span><h1>通知</h1></div>
      <button v-if="store.items.some((item) => !item.readAt)" type="button" class="mobile-header-action" @click="store.markAllRead()">
        <CheckCheck :size="18" />全部已读
      </button>
    </header>

    <div v-if="store.loading" class="mobile-loading-list"><div v-for="n in 4" :key="n" class="mobile-loading-row" /></div>
    <MobileEmptyState v-else-if="store.items.length === 0" title="没有新通知" description="任务评论和提及会出现在这里。" />
    <section v-else class="mobile-notification-list">
      <button
        v-for="item in store.items"
        :key="item.id"
        type="button"
        class="mobile-notification-row"
        :class="{ unread: !item.readAt }"
        @click="openNotification(item.id, item.taskKey, item.readAt)"
      >
        <span class="mobile-notification-icon"><Bell :size="18" /></span>
        <span class="mobile-notification-copy">
          <strong>{{ item.summary || '任务有新的动态' }}</strong>
          <span>{{ item.taskKey }} · {{ dateLabel(item.createdAt) }}</span>
        </span>
        <span v-if="!item.readAt" class="mobile-unread-dot" aria-label="未读" />
        <ChevronRight :size="17" aria-hidden="true" />
      </button>
    </section>
  </main>
</template>
