<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AssigneeCount } from '../../types/analytics'

const props = defineProps<{ breakdown: AssigneeCount[] }>()
const { t } = useI18n()

const rows = computed(() => props.breakdown.slice(0, 8))

function displayName(item: AssigneeCount): string {
  return item.assigneeId === null ? t('analytics.unassigned') : item.assigneeName
}

function initials(item: AssigneeCount): string {
  if (item.assigneeId === null) return '—'
  return item.assigneeName.slice(0, 1).toUpperCase()
}

function completionWidth(item: AssigneeCount): string {
  return `${item.totalCount === 0 ? 0 : (item.completedCount / item.totalCount) * 100}%`
}
</script>

<template>
  <article class="assignee-panel">
    <header class="panel-heading">
      <div>
        <h3>{{ t('analytics.teamThroughput') }}</h3>
        <p>{{ t('analytics.teamThroughputDescription') }}</p>
      </div>
      <span class="panel-total">{{ t('analytics.assigneeCount', { count: breakdown.length }) }}</span>
    </header>

    <div v-if="rows.length" class="assignee-list">
      <div v-for="item in rows" :key="item.assigneeId === null ? 'unassigned' : item.assigneeId" class="assignee-row">
        <span class="assignee-avatar" aria-hidden="true">{{ initials(item) }}</span>
        <div class="assignee-main">
          <div class="assignee-copy">
            <span class="assignee-name">{{ displayName(item) }}</span>
            <span class="assignee-numbers">
              {{ t('analytics.assigneeResult', { completed: item.completedCount, total: item.totalCount }) }}
            </span>
          </div>
          <div class="throughput-track" aria-hidden="true">
            <span class="throughput-fill" :style="{ width: completionWidth(item) }" />
          </div>
        </div>
        <span class="in-progress">{{ t('analytics.inProgressCount', { count: item.inProgressCount }) }}</span>
      </div>
    </div>
    <div v-else class="panel-empty">{{ t('analytics.noData') }}</div>
  </article>
</template>

<style scoped>
.assignee-panel { min-width: 0; padding: 22px 0 24px 24px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.panel-heading h3 { margin: 0; color: var(--color-text-primary); font-size: 13px; font-weight: 650; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: var(--font-size-caption); line-height: 1.45; }
.panel-total { color: var(--color-text-muted); font-size: var(--font-size-caption); white-space: nowrap; }
.assignee-list { display: flex; flex-direction: column; max-height: 282px; overflow-y: auto; }
.assignee-row { display: grid; grid-template-columns: 28px minmax(150px, 1fr) auto; align-items: center; gap: 10px; min-height: 50px; border-top: 1px solid var(--color-border-subtle); }
.assignee-row:first-child { border-top: 0; }
.assignee-avatar { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 50%; background: var(--color-bg-muted); color: var(--color-text-secondary); font-size: var(--font-size-caption); font-weight: 650; }
.assignee-main { min-width: 0; }
.assignee-copy { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.assignee-name { overflow: hidden; color: var(--color-text-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.assignee-numbers { color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; white-space: nowrap; }
.throughput-track { height: 3px; overflow: hidden; border-radius: var(--radius-xs); background: var(--color-bg-hover); }
.throughput-fill { display: block; height: 100%; border-radius: inherit; background: var(--analytics-positive); }
.in-progress { color: var(--color-text-muted); font-size: var(--font-size-caption); white-space: nowrap; }
.panel-empty { padding: 32px 0; color: var(--color-text-muted); font-size: 12px; }

@media (max-width: 980px) { .assignee-panel { padding-left: 0; } }
</style>
