<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { StatusCount } from '../../types/analytics'

const props = defineProps<{
  breakdown: StatusCount[]
  total: number
  overdueCount: number
}>()

const { t } = useI18n()
const terminalStatuses = new Set(['done', 'canceled', 'duplicate'])
const completedCount = computed(() =>
  props.breakdown.reduce((sum, item) => sum + (terminalStatuses.has(item.status) ? item.count : 0), 0)
)
const activeCount = computed(() => props.total - completedCount.value)

function width(count: number): string {
  return `${props.total === 0 ? 0 : (count / props.total) * 100}%`
}
</script>

<template>
  <article class="workflow-panel">
    <header class="panel-heading">
      <div>
        <h3>{{ t('analytics.workflowSnapshot') }}</h3>
        <p>{{ t('analytics.workflowSnapshotDescription') }}</p>
      </div>
      <span class="panel-total">{{ t('analytics.totalItems', { total }) }}</span>
    </header>

    <div class="workflow-signals">
      <span><strong>{{ activeCount }}</strong>{{ t('analytics.activeTasks') }}</span>
      <span :class="{ 'signal--risk': overdueCount > 0 }"><strong>{{ overdueCount }}</strong>{{ t('analytics.overdue') }}</span>
    </div>

    <div v-if="breakdown.length" class="status-list">
      <div v-for="item in breakdown" :key="item.status" class="status-row">
        <div class="status-copy">
          <span class="status-name">{{ t(`status.${item.status}`) }}</span>
          <span class="status-count">{{ item.count }}</span>
        </div>
        <div class="status-track" aria-hidden="true">
          <span class="status-fill" :style="{ width: width(item.count) }" />
        </div>
      </div>
    </div>
    <div v-else class="panel-empty">{{ t('analytics.noData') }}</div>
  </article>
</template>

<style scoped>
.workflow-panel { padding: 22px 24px 24px 0; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.panel-heading h3 { margin: 0; color: var(--color-text-primary); font-size: 13px; font-weight: 650; }
.panel-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: var(--font-size-caption); line-height: 1.45; }
.panel-total { color: var(--color-text-muted); font-size: var(--font-size-caption); white-space: nowrap; }
.workflow-signals { display: flex; gap: 28px; padding-bottom: 18px; border-bottom: 1px solid var(--color-border-subtle); }
.workflow-signals span { display: flex; align-items: baseline; gap: 7px; color: var(--color-text-muted); font-size: var(--font-size-caption); }
.workflow-signals strong { color: var(--color-text-primary); font-size: 20px; font-weight: 650; font-variant-numeric: tabular-nums; }
.workflow-signals .signal--risk strong { color: var(--analytics-critical); }
.status-list { display: flex; flex-direction: column; gap: 12px; margin-top: 18px; }
.status-copy { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.status-name { color: var(--color-text-secondary); font-size: 12px; }
.status-count { color: var(--color-text-primary); font-size: var(--font-size-caption); font-weight: 600; font-variant-numeric: tabular-nums; }
.status-track { height: 4px; overflow: hidden; border-radius: var(--radius-xs); background: var(--color-bg-hover); }
.status-fill { display: block; height: 100%; border-radius: inherit; background: var(--color-accent); }
.panel-empty { padding: 32px 0; color: var(--color-text-muted); font-size: 12px; }

@media (max-width: 980px) { .workflow-panel { padding-right: 0; } }
</style>
