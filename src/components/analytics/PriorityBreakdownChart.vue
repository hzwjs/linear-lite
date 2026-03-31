<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PriorityCount } from '../../types/analytics'

const props = defineProps<{
  breakdown: PriorityCount[]
}>()

const { t } = useI18n()

const total = computed(() => props.breakdown.reduce((s, b) => s + b.count, 0))

const priorityColors: Record<string, string> = {
  urgent: '#e5484d',
  high: '#f76b15',
  medium: '#f5d90a',
  low: '#889096'
}
</script>

<template>
  <div class="breakdown-card">
    <h3 class="card-title">{{ t('analytics.priorityBreakdown') }}</h3>
    <div v-if="!breakdown.length" class="card-empty">{{ t('analytics.noData') }}</div>
    <div v-else class="breakdown-list">
      <div v-for="item in breakdown" :key="item.priority" class="breakdown-row">
        <span class="breakdown-name">{{ t(`priority.${item.priority}`, item.priority) }}</span>
        <span class="breakdown-count">{{ item.count }}</span>
        <div class="breakdown-bar">
          <div
            class="breakdown-bar-fill"
            :style="{
              width: total ? (item.count / total * 100) + '%' : '0%',
              background: priorityColors[item.priority] ?? '#6e56cf'
            }"
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
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.breakdown-row {
  display: grid;
  grid-template-columns: 60px 40px 1fr;
  align-items: center;
  gap: 8px;
}
.breakdown-name {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.breakdown-count {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-primary);
  text-align: right;
}
.breakdown-bar {
  height: 6px;
  background: var(--color-bg-hover);
  border-radius: 3px;
  overflow: hidden;
}
.breakdown-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 300ms ease;
}
</style>
