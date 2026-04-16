<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Bell } from 'lucide-vue-next'
import { useNotificationStore } from '../store/notificationStore'
import { bodyToPlainText } from '../utils/blockNoteHtml'

const { t } = useI18n()
const router = useRouter()
const store = useNotificationStore()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

watch(open, (v) => {
  if (v) void store.fetchList()
})

function toggle() {
  open.value = !open.value
}

function onClickOutside(ev: MouseEvent) {
  const el = rootRef.value
  if (!el || !open.value) return
  if (!el.contains(ev.target as Node)) open.value = false
}

function detachClickOutside() {
  document.removeEventListener('click', onClickOutside, true)
}

watch(open, (v) => {
  if (v) document.addEventListener('click', onClickOutside, true)
  else detachClickOutside()
})

onUnmounted(() => {
  detachClickOutside()
})

async function onItemClick(id: number, taskKey: string) {
  await store.markRead(id)
  open.value = false
  await router.push({ path: `/tasks/${encodeURIComponent(taskKey)}` })
}

async function onMarkAll() {
  await store.markAllRead()
}
</script>

<template>
  <div ref="rootRef" class="notification-center">
    <button
      type="button"
      class="notification-bell"
      :aria-label="t('notifications.title')"
      :title="t('notifications.title')"
      @click.stop="toggle"
    >
      <Bell class="notification-bell-icon" />
      <span v-if="store.unreadCount > 0" class="notification-badge">{{ store.unreadCount > 99 ? '99+' : store.unreadCount }}</span>
    </button>
    <div v-if="open" class="notification-dropdown" role="menu">
      <div class="notification-dropdown-head">
        <span>{{ t('notifications.title') }}</span>
        <button type="button" class="notification-mark-all" @click="onMarkAll">
          {{ t('notifications.markAllRead') }}
        </button>
      </div>
      <div v-if="store.loading" class="notification-empty">{{ t('common.loading') }}</div>
      <div v-else-if="!store.items.length" class="notification-empty">{{ t('notifications.empty') }}</div>
      <ul v-else class="notification-list">
        <li v-for="n in store.items" :key="n.id">
          <button
            type="button"
            class="notification-item"
            :class="{ unread: !n.readAt }"
            @click="onItemClick(n.id, n.taskKey)"
          >
            <span class="notification-item-type">{{ t('notifications.mentionInIssue', { key: n.taskKey }) }}</span>
            <span v-if="n.summary" class="notification-item-summary">{{ bodyToPlainText(n.summary) }}</span>
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.notification-center {
  position: relative;
}
.notification-bell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-muted, #888);
  cursor: pointer;
}
.notification-bell:hover {
  background: var(--color-bg-hover, rgba(0, 0, 0, 0.06));
  color: var(--color-text, #111);
}
.notification-bell-icon {
  width: 18px;
  height: 18px;
}
.notification-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--color-accent, #5e6ad2);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
}
.notification-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 200;
  width: min(360px, 90vw);
  max-height: 420px;
  overflow: auto;
  border-radius: 8px;
  border: 1px solid var(--color-border, #e5e5e5);
  background: var(--color-bg-elevated, #fff);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}
.notification-dropdown-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border, #eee);
}
.notification-mark-all {
  border: none;
  background: none;
  color: var(--color-accent, #5e6ad2);
  font-size: 12px;
  cursor: pointer;
}
.notification-empty {
  padding: 20px 12px;
  font-size: 13px;
  color: var(--color-text-muted, #888);
  text-align: center;
}
.notification-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.notification-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-bottom: 1px solid var(--color-border, #f0f0f0);
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-size: 13px;
}
.notification-item:hover {
  background: var(--color-bg-hover, rgba(0, 0, 0, 0.04));
}
.notification-item.unread {
  background: rgba(94, 106, 210, 0.06);
}
.notification-item-type {
  font-weight: 600;
  color: var(--color-text, #111);
}
.notification-item-summary {
  margin-top: 4px;
  color: var(--color-text-muted, #666);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
