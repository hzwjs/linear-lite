<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TrendBucket } from '../../types/analytics'

const props = defineProps<{
  trend: TrendBucket[]
}>()

const { t } = useI18n()

const maxCount = computed(() => {
  let max = 1
  for (const b of props.trend) {
    max = Math.max(max, b.createdCount, b.completedCount, b.dueCount)
  }
  return max
})

function barHeight(count: number): string {
  return ((count / maxCount.value) * 100) + '%'
}

function formatLabel(dateStr: string): string {
  const d = dateStr.substring(0, 10)
  return d.substring(5) // MM-DD
}
</script>

<template>
  <div class="trend-chart">
    <h3 class="chart-title">{{ t('analytics.trend') }}</h3>
    <div class="chart-legend">
      <span class="legend-item legend-created">{{ t('analytics.created') }}</span>
      <span class="legend-item legend-completed">{{ t('analytics.completed') }}</span>
      <span class="legend-item legend-due">{{ t('analytics.due') }}</span>
    </div>
    <div class="chart-body">
      <div v-if="!trend.length" class="chart-empty">{{ t('analytics.noData') }}</div>
      <div v-else class="chart-bars">
        <div v-for="(bucket, i) in trend" :key="i" class="bar-group">
          <div class="bars">
            <div class="bar bar--created" :style="{ height: barHeight(bucket.createdCount) }" :title="`${t('analytics.created')}: ${bucket.createdCount}`" />
            <div class="bar bar--completed" :style="{ height: barHeight(bucket.completedCount) }" :title="`${t('analytics.completed')}: ${bucket.completedCount}`" />
            <div class="bar bar--due" :style="{ height: barHeight(bucket.dueCount) }" :title="`${t('analytics.due')}: ${bucket.dueCount}`" />
          </div>
          <span class="bar-label">{{ formatLabel(bucket.bucketStart) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trend-chart {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 16px;
}
.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0 0 8px;
}
.chart-legend {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}
.legend-item {
  font-size: 11px;
  color: var(--color-text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}
.legend-item::before {
  content: '';
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
.legend-created::before { background: #6e56cf; }
.legend-completed::before { background: #30a46c; }
.legend-due::before { background: #e5484d; }
.chart-body {
  min-height: 160px;
}
.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 160px;
  color: var(--color-text-muted);
  font-size: 13px;
}
.chart-bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 160px;
  overflow-x: auto;
}
.bar-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  min-width: 36px;
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 140px;
  width: 100%;
}
.bar {
  flex: 1;
  border-radius: 2px 2px 0 0;
  min-height: 2px;
  transition: height 300ms ease;
}
.bar--created { background: #6e56cf; }
.bar--completed { background: #30a46c; }
.bar--due { background: #e5484d; }
.bar-label {
  margin-top: 4px;
  font-size: 10px;
  color: var(--color-text-muted);
  white-space: nowrap;
}
</style>
