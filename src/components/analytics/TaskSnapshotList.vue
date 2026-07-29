<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TaskListScope, TaskSnapshotPageResponse } from '../../types/analytics'

const props = defineProps<{
  data: TaskSnapshotPageResponse
  from: string
  to: string
  metric: TaskListScope
  loading: boolean
}>()

const emit = defineEmits<{ page: [value: number] }>()
const { t } = useI18n()

const totalPages = computed(() => Math.max(1, Math.ceil(props.data.total / props.data.pageSize)))
const rangeLabel = computed(() =>
  props.metric === 'overdue'
    ? t('analytics.currentProject')
    : props.from === props.to
      ? props.from
      : `${props.from} — ${props.to}`
)

function assigneeName(assigneeId: number | null, name: string): string {
  return assigneeId === null ? t('analytics.unassigned') : name
}

function dueDate(value: string | null): string {
  return value === null ? t('common.none') : value.slice(0, 10)
}
</script>

<template>
  <div class="task-ledger" :aria-busy="loading" data-testid="task-snapshot-list">
    <div class="ledger-meta">
      <span>{{ t(`analytics.metric.${metric}`) }}</span>
      <span>{{ rangeLabel }}</span>
      <span>{{ t('analytics.totalItems', { total: data.total }) }}</span>
    </div>

    <div v-if="!data.items.length && !loading" class="ledger-empty">
      <strong>{{ t('analytics.noMatchingTasks') }}</strong>
      <span>{{ t('analytics.noMatchingTasksDescription') }}</span>
    </div>

    <div v-else class="table-scroll">
      <table class="task-table">
        <thead>
          <tr>
            <th>{{ t('analytics.task') }}</th>
            <th>{{ t('common.status') }}</th>
            <th>{{ t('common.priority') }}</th>
            <th>{{ t('common.assignee') }}</th>
            <th>{{ t('common.created') }}</th>
            <th>{{ t('common.dueDate') }}</th>
          </tr>
        </thead>
        <tbody :class="{ 'table-body--loading': loading }">
          <tr v-for="item in data.items" :key="item.taskKey">
            <td class="task-cell">
              <RouterLink class="task-link" :to="`/tasks/${item.taskKey}`">
                <span class="task-key">{{ item.taskKey }}</span>
                <span class="task-title">{{ item.title }}</span>
              </RouterLink>
            </td>
            <td><span class="status-label">{{ t(`status.${item.status}`) }}</span></td>
            <td>{{ t(`priority.${item.priority}`) }}</td>
            <td>{{ assigneeName(item.assigneeId, item.assigneeName) }}</td>
            <td class="date-cell">{{ item.createdAt.slice(0, 10) }}</td>
            <td class="date-cell" :class="{ 'date-cell--overdue': metric === 'overdue' }">{{ dueDate(item.dueDate) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="table-loading" role="status">{{ t('analytics.loading') }}</div>
    </div>

    <footer v-if="data.total > 0" class="pagination">
      <span>{{ t('analytics.pageOf', { page: data.page, total: totalPages }) }}</span>
      <div class="page-actions">
        <button type="button" :disabled="data.page <= 1 || loading" @click="emit('page', data.page - 1)">
          {{ t('analytics.prevPage') }}
        </button>
        <button type="button" :disabled="data.page >= totalPages || loading" @click="emit('page', data.page + 1)">
          {{ t('analytics.nextPage') }}
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.task-ledger { position: relative; border-block: 1px solid var(--color-border); }
.ledger-meta { display: flex; align-items: center; gap: 16px; min-height: 38px; padding: 0 10px; border-bottom: 1px solid var(--color-border-subtle); color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; }
.ledger-meta span + span { padding-left: 16px; border-left: 1px solid var(--color-border); }
.table-scroll { position: relative; overflow-x: auto; }
.task-table { width: 100%; min-width: 760px; border-collapse: collapse; table-layout: fixed; }
.task-table th { padding: 8px 10px; border-bottom: 1px solid var(--color-border); color: var(--color-text-muted); font-size: var(--font-size-caption); font-weight: 600; text-align: left; }
.task-table th:first-child { width: 40%; }
.task-table th:nth-child(2) { width: 11%; }
.task-table th:nth-child(3) { width: 10%; }
.task-table th:nth-child(4) { width: 14%; }
.task-table th:nth-child(5), .task-table th:nth-child(6) { width: 12.5%; }
.task-table td { height: 44px; padding: 7px 10px; border-bottom: 1px solid var(--color-border-subtle); color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.task-table tbody tr:last-child td { border-bottom: 0; }
.task-table tbody tr:hover { background: var(--color-bg-subtle); }
.task-cell { min-width: 0; }
.task-link { display: flex; align-items: center; gap: 9px; min-width: 0; color: inherit; text-decoration: none; }
.task-link:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; border-radius: var(--radius-xs); }
.task-key { flex: 0 0 auto; color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; }
.task-title { overflow: hidden; color: var(--color-text-primary); font-size: 12px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.status-label { display: inline-flex; align-items: center; min-height: 21px; padding: 2px 6px; border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-secondary); }
.date-cell { color: var(--color-text-muted); font-variant-numeric: tabular-nums; white-space: nowrap; }
.date-cell--overdue { color: var(--analytics-critical); font-weight: 600; }
.table-body--loading { opacity: 0.38; }
.table-loading { position: absolute; inset: 34px 0 0; display: grid; place-items: center; background: rgba(255, 255, 255, 0.62); color: var(--color-text-muted); font-size: 12px; }
.ledger-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; min-height: 150px; color: var(--color-text-muted); font-size: var(--font-size-caption); }
.ledger-empty strong { color: var(--color-text-secondary); font-size: 13px; }
.pagination { display: flex; align-items: center; justify-content: space-between; min-height: 44px; padding: 0 10px; border-top: 1px solid var(--color-border); color: var(--color-text-muted); font-size: var(--font-size-caption); }
.page-actions { display: flex; gap: 6px; }
.page-actions button { min-height: 30px; padding: 4px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.page-actions button:hover:not(:disabled) { background: var(--color-bg-hover); }
.page-actions button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.page-actions button:disabled { cursor: not-allowed; opacity: 0.4; }

@media (max-width: 640px) { .ledger-meta { overflow-x: auto; white-space: nowrap; } }
</style>
