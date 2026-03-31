<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { TaskSnapshotPageResponse } from '../../types/analytics'

const props = defineProps<{
  data: TaskSnapshotPageResponse
  from: string
  to: string
  metric: 'all' | 'created' | 'completed' | 'due'
}>()

const emit = defineEmits<{
  page: [value: number]
}>()

const { t } = useI18n()
const router = useRouter()

function goToTask(taskKey: string) {
  router.push(`/tasks/${taskKey}`)
}

const totalPages = computed(() => Math.max(1, Math.ceil(props.data.total / props.data.pageSize)))
const metricLabelKey = computed(() => {
  if (props.metric === 'created') return 'analytics.createdToday'
  if (props.metric === 'completed') return 'analytics.completedToday'
  if (props.metric === 'due') return 'analytics.dueToday'
  return 'analytics.allTasks'
})

const rangeSubtitle = computed(() =>
  props.from === props.to ? props.from : `${props.from} ~ ${props.to}`
)
</script>

<template>
  <div class="task-snapshot" data-testid="task-snapshot-list">
    <div class="snapshot-head">
      <h3 class="snapshot-title">{{ t('analytics.taskList') }}</h3>
      <p class="snapshot-subtitle">{{ t(metricLabelKey) }} · {{ rangeSubtitle }}</p>
    </div>
    <div v-if="!data.items.length" class="snapshot-empty">{{ t('analytics.noData') }}</div>
    <table v-else class="snapshot-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>{{ t('common.title') }}</th>
          <th>{{ t('common.status') }}</th>
          <th>{{ t('common.priority') }}</th>
          <th>{{ t('common.assignee') }}</th>
          <th>{{ t('common.created') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in data.items" :key="item.taskKey" class="task-row" @click="goToTask(item.taskKey)">
          <td class="cell-key">{{ item.taskKey }}</td>
          <td class="cell-title">{{ item.title }}</td>
          <td>{{ t(`status.${item.status}`, item.status) }}</td>
          <td>{{ t(`priority.${item.priority}`, item.priority) }}</td>
          <td>{{ item.assigneeName || t('analytics.unassigned') }}</td>
          <td class="cell-date">{{ item.createdAt?.substring(0, 10) }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="data.total > 0" class="pagination">
      <span class="page-info">{{ t('analytics.totalItems', { total: data.total }) }}</span>
      <div class="page-buttons">
        <button type="button" :disabled="data.page <= 1" @click="emit('page', data.page - 1)">
          {{ t('analytics.prevPage') }}
        </button>
        <span class="page-current">{{ data.page }} / {{ totalPages }}</span>
        <button type="button" :disabled="data.page >= totalPages" @click="emit('page', data.page + 1)">
          {{ t('analytics.nextPage') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.task-snapshot {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 16px;
}
.snapshot-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0;
}
.snapshot-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.snapshot-subtitle {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
.snapshot-empty {
  color: var(--color-text-muted);
  font-size: 13px;
  padding: 12px 0;
}
.snapshot-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.snapshot-table th {
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 6px 8px;
  border-bottom: 1px solid var(--color-border);
}
.snapshot-table td {
  padding: 8px;
  color: var(--color-text-secondary);
  border-bottom: 1px solid var(--color-border-subtle, var(--color-border));
}
.task-row {
  cursor: pointer;
  transition: background 150ms;
}
.task-row:hover {
  background: var(--color-bg-hover);
}
.cell-key {
  font-weight: 500;
  color: var(--color-text-primary);
  white-space: nowrap;
}
.cell-title {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell-date {
  white-space: nowrap;
  color: var(--color-text-muted);
}
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.page-info {
  font-size: 12px;
}
.page-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-buttons button {
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: background 150ms;
}
.page-buttons button:hover:not(:disabled) {
  background: var(--color-bg-hover);
}
.page-buttons button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.page-current {
  font-weight: 500;
}
</style>
