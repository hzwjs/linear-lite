<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Status } from '../types/domain'
import { getStatusLabel } from '../utils/enumLabels'
import TaskStatusIcon from './TaskStatusIcon.vue'

defineProps<{
  statistics: { total: number; items: Array<{ status: Status; count: number }> }
  selectedStatuses: Status[]
  loading: boolean
}>()
defineEmits<{ select: [status: Status | null] }>()
const { t } = useI18n()
</script>

<template>
  <nav class="status-navigation" :aria-label="t('boardView.statusNavigation')" :aria-busy="loading">
    <button
      type="button"
      class="status-navigation-item status-navigation-total"
      :aria-pressed="selectedStatuses.length === 0"
      @click="$emit('select', null)"
    >
      <span>{{ t('boardView.allStatuses') }}</span>
      <span class="status-navigation-count">{{ statistics.total }}</span>
    </button>
    <span class="status-navigation-divider" aria-hidden="true" />
    <button
      v-for="item in statistics.items"
      :key="item.status"
      type="button"
      class="status-navigation-item"
      :aria-pressed="selectedStatuses.includes(item.status)"
      @click="$emit('select', item.status)"
    >
      <TaskStatusIcon :status="item.status" aria-hidden="true" />
      <span>{{ getStatusLabel(item.status) }}</span>
      <span class="status-navigation-count">{{ item.count }}</span>
    </button>
  </nav>
</template>

<style scoped>
.status-navigation {
  --status-nav-height: 44px;
  --status-nav-item-height: 30px;
  --status-nav-gap: 8px;
  --status-nav-inset: 20px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--status-nav-gap);
  min-height: var(--status-nav-height);
  padding-inline: var(--status-nav-inset);
  overflow-x: auto;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-base);
}
.status-navigation-item {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--status-nav-gap);
  min-height: var(--status-nav-item-height);
  padding: 0 var(--status-nav-gap);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--font-size-caption);
  white-space: nowrap;
  cursor: pointer;
}
.status-navigation-item:hover { background: var(--color-bg-hover); }
.status-navigation-item[aria-pressed='true'] {
  background: var(--color-accent-muted);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.status-navigation-item:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 1px;
}
.status-navigation-count {
  min-width: 1ch;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}
.status-navigation-divider {
  align-self: center;
  flex: 0 0 1px;
  height: 16px;
  background: var(--color-border);
}
</style>
