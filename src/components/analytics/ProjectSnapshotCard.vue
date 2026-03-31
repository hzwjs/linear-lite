<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ProjectSnapshot } from '../../types/analytics'

defineProps<{
  snapshot: ProjectSnapshot
}>()

const { t } = useI18n()
</script>

<template>
  <div class="snapshot-card">
    <h3 class="snapshot-title">{{ t('analytics.currentSnapshot') }}</h3>
    <div class="snapshot-stats">
      <div class="stat-item">
        <span class="stat-value">{{ snapshot.totalCount }}</span>
        <span class="stat-label">{{ t('analytics.totalTasks') }}</span>
      </div>
      <div class="stat-item stat-item--overdue">
        <span class="stat-value">{{ snapshot.overdueCount }}</span>
        <span class="stat-label">{{ t('analytics.overdue') }}</span>
      </div>
    </div>
    <div class="snapshot-status-list">
      <div v-for="s in snapshot.statusBreakdown" :key="s.status" class="status-row">
        <span class="status-name">{{ t(`status.${s.status}`, s.status) }}</span>
        <span class="status-count">{{ s.count }}</span>
        <div class="status-bar">
          <div
            class="status-bar-fill"
            :style="{ width: snapshot.totalCount ? (s.count / snapshot.totalCount * 100) + '%' : '0%' }"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.snapshot-card {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 16px;
}
.snapshot-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0 0 12px;
}
.snapshot-stats {
  display: flex;
  gap: 24px;
  margin-bottom: 14px;
}
.stat-item {
  display: flex;
  flex-direction: column;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-text-primary);
  line-height: 1.1;
}
.stat-item--overdue .stat-value {
  color: #e5484d;
}
.stat-label {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 2px;
}
.snapshot-status-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.status-row {
  display: grid;
  grid-template-columns: 80px 40px 1fr;
  align-items: center;
  gap: 8px;
}
.status-name {
  font-size: 12px;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-count {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-primary);
  text-align: right;
}
.status-bar {
  height: 6px;
  background: var(--color-bg-hover);
  border-radius: 3px;
  overflow: hidden;
}
.status-bar-fill {
  height: 100%;
  background: var(--color-accent, #6e56cf);
  border-radius: 3px;
  transition: width 300ms ease;
}
</style>
