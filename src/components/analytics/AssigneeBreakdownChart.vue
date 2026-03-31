<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AssigneeCount } from '../../types/analytics'

const props = defineProps<{
  breakdown: AssigneeCount[]
}>()

const { t } = useI18n()

const maxTotal = computed(() => Math.max(1, ...props.breakdown.map((b) => b.totalCount)))
</script>

<template>
  <div class="breakdown-card">
    <h3 class="card-title">{{ t('analytics.assigneeBreakdown') }}</h3>
    <div v-if="!breakdown.length" class="card-empty">{{ t('analytics.noData') }}</div>
    <div v-else class="breakdown-list">
      <div v-for="item in breakdown" :key="item.assigneeId ?? 'unassigned'" class="assignee-card">
        <div class="assignee-head">
          <span class="assignee-name">{{ item.assigneeName || t('analytics.unassigned') }}</span>
          <span class="stat stat--total">{{ item.totalCount }}</span>
        </div>
        <div class="assignee-stats">
          <span class="stat stat--completed" :title="t('analytics.completed')">{{ item.completedCount }}</span>
          <span class="stat stat--progress" :title="'In progress'">{{ item.inProgressCount }}</span>
        </div>
        <div class="assignee-bar">
          <div
            class="bar-fill bar-fill--completed"
            :style="{ width: (item.completedCount / maxTotal * 100) + '%' }"
          />
          <div
            class="bar-fill bar-fill--progress"
            :style="{ width: (item.inProgressCount / maxTotal * 100) + '%' }"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.breakdown-card {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 16px;
}
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0 0 12px;
}
.card-empty {
  color: var(--color-text-muted);
  font-size: 13px;
  padding: 12px 0;
}
.breakdown-list {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(170px, 1fr);
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.assignee-card {
  border: 1px solid var(--color-border-subtle, var(--color-border));
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  padding: 10px;
  min-height: 66px;
}
.assignee-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.assignee-name {
  font-size: 12px;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assignee-stats {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
}
.stat {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 4px;
  border-radius: 3px;
}
.stat--total { color: var(--color-text-primary); background: var(--color-bg-hover); }
.stat--completed { color: #30a46c; }
.stat--progress { color: #6e56cf; }
.assignee-bar {
  display: flex;
  height: 6px;
  background: var(--color-bg-hover);
  border-radius: 3px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  transition: width 300ms ease;
}
.bar-fill--completed { background: #30a46c; }
.bar-fill--progress { background: #6e56cf; }
</style>
