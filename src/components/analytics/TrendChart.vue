<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TrendBucket } from '../../types/analytics'

const props = defineProps<{ trend: TrendBucket[] }>()
const { t } = useI18n()

const activeBucket = ref<TrendBucket | null>(null)
const tooltipX = ref(0)
const tooltipY = ref(0)

const maxCount = computed(() => {
  const values = props.trend.flatMap((bucket) => [bucket.createdCount, bucket.completedCount, bucket.dueCount])
  return Math.max(1, ...values)
})

const totals = computed(() =>
  props.trend.reduce(
    (result, bucket) => ({
      created: result.created + bucket.createdCount,
      completed: result.completed + bucket.completedCount,
      due: result.due + bucket.dueCount
    }),
    { created: 0, completed: 0, due: 0 }
  )
)

function height(count: number): string {
  return `${Math.max(3, (count / maxCount.value) * 100)}%`
}

function formatDate(value: string): string {
  const date = value.slice(0, 10)
  return date.slice(5)
}

/** Tooltip 使用视口坐标并 Teleport 到 body，避免被图表横向滚动容器裁切。 */
function showTooltip(bucket: TrendBucket, event: MouseEvent | FocusEvent) {
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const pointerX = event instanceof MouseEvent ? event.clientX : rect.left + rect.width / 2
  const pointerY = event instanceof MouseEvent ? event.clientY - 10 : rect.top - 8
  activeBucket.value = bucket
  tooltipX.value = Math.min(window.innerWidth - 110, Math.max(110, pointerX))
  tooltipY.value = pointerY
}

function hideTooltip() {
  activeBucket.value = null
}
</script>

<template>
  <div class="trend-chart">
    <div v-if="!trend.length" class="chart-empty">{{ t('analytics.noData') }}</div>
    <template v-else>
      <div class="chart-summary" aria-hidden="true">
        <span class="legend legend--created">{{ t('analytics.created') }} {{ totals.created }}</span>
        <span class="legend legend--completed">{{ t('analytics.completed') }} {{ totals.completed }}</span>
        <span class="legend legend--due">{{ t('analytics.due') }} {{ totals.due }}</span>
      </div>
      <div class="chart-plot" role="img" :aria-label="t('analytics.trendAccessibleLabel')">
        <div class="chart-grid" aria-hidden="true"><span /><span /><span /></div>
        <div class="bucket-list">
          <div v-for="bucket in trend" :key="bucket.bucketStart" class="bucket">
            <div
              class="bar-group"
              tabindex="0"
              role="group"
              :aria-label="`${formatDate(bucket.bucketStart)}，${t('analytics.created')} ${bucket.createdCount}，${t('analytics.completed')} ${bucket.completedCount}，${t('analytics.due')} ${bucket.dueCount}`"
              @mouseenter="showTooltip(bucket, $event)"
              @mousemove="showTooltip(bucket, $event)"
              @mouseleave="hideTooltip"
              @focus="showTooltip(bucket, $event)"
              @blur="hideTooltip"
              @keydown.esc="hideTooltip"
            >
              <span
                class="bar bar--created"
                :style="{ height: height(bucket.createdCount) }"
                aria-hidden="true"
              />
              <span
                class="bar bar--completed"
                :style="{ height: height(bucket.completedCount) }"
                aria-hidden="true"
              />
              <span
                class="bar bar--due"
                :style="{ height: height(bucket.dueCount) }"
                aria-hidden="true"
              />
            </div>
            <span class="bucket-label">{{ formatDate(bucket.bucketStart) }}</span>
          </div>
        </div>
      </div>
      <table class="sr-table">
        <caption>{{ t('analytics.trendAccessibleLabel') }}</caption>
        <thead><tr><th>{{ t('analytics.dateSingle') }}</th><th>{{ t('analytics.created') }}</th><th>{{ t('analytics.completed') }}</th><th>{{ t('analytics.due') }}</th></tr></thead>
        <tbody><tr v-for="bucket in trend" :key="bucket.bucketStart"><td>{{ formatDate(bucket.bucketStart) }}</td><td>{{ bucket.createdCount }}</td><td>{{ bucket.completedCount }}</td><td>{{ bucket.dueCount }}</td></tr></tbody>
      </table>
    </template>

    <Teleport to="body">
      <div
        v-if="activeBucket"
        class="chart-tooltip"
        role="tooltip"
        :style="{ left: `${tooltipX}px`, top: `${tooltipY}px` }"
      >
        <strong>{{ formatDate(activeBucket.bucketStart) }}</strong>
        <span><i class="tooltip-key tooltip-key--created" />{{ t('analytics.created') }} <b>{{ activeBucket.createdCount }}</b></span>
        <span><i class="tooltip-key tooltip-key--completed" />{{ t('analytics.completed') }} <b>{{ activeBucket.completedCount }}</b></span>
        <span><i class="tooltip-key tooltip-key--due" />{{ t('analytics.due') }} <b>{{ activeBucket.dueCount }}</b></span>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.trend-chart { position: relative; border-block: 1px solid var(--color-border); }
.chart-summary { display: flex; align-items: center; gap: 18px; min-height: 42px; padding: 0 14px; border-bottom: 1px solid var(--color-border-subtle); }
.legend { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; }
.legend::before { content: ''; width: 8px; height: 8px; border-radius: var(--radius-xs); }
.legend--created::before { border: 1px solid #87909d; background: transparent; }
.legend--completed::before { background: var(--color-accent); }
.legend--due::before { background: var(--analytics-warning); }
.chart-plot { position: relative; height: 230px; overflow-x: auto; padding: 20px 14px 0; }
.chart-grid { position: absolute; inset: 20px 14px 28px; display: flex; flex-direction: column; justify-content: space-between; pointer-events: none; }
.chart-grid span { display: block; border-top: 1px dashed var(--color-border-subtle); }
.bucket-list { position: relative; z-index: 1; display: flex; align-items: stretch; gap: 6px; min-width: min-content; height: 100%; }
.bucket { display: grid; grid-template-rows: minmax(0, 1fr) 28px; min-width: 44px; flex: 1; }
.bar-group { display: flex; align-items: flex-end; justify-content: center; gap: 3px; min-height: 0; cursor: crosshair; }
.bar-group:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; border-radius: var(--radius-sm); }
.bar { width: clamp(5px, 1.05vw, 11px); min-height: 3px; border-radius: var(--radius-xs) var(--radius-xs) 0 0; }
.bar--created { border: 1px solid #87909d; background: var(--color-bg-base); }
.bar--completed { background: var(--color-accent); }
.bar--due { background: var(--analytics-warning); }
.bucket-label { align-self: center; color: var(--color-text-muted); font-size: var(--font-size-caption); text-align: center; font-variant-numeric: tabular-nums; white-space: nowrap; }
.chart-empty { padding: 56px 16px; color: var(--color-text-muted); font-size: 12px; text-align: center; }
.sr-table { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
.chart-tooltip {
  position: fixed;
  z-index: 1200;
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
  min-width: 148px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-text-primary);
  color: var(--color-bg-base);
  box-shadow: var(--shadow-popover);
  font-size: var(--font-size-caption);
  pointer-events: none;
  transform: translate(-50%, -100%);
}
.chart-tooltip strong { margin-bottom: 2px; font-size: var(--font-size-body); }
.chart-tooltip span { display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 7px; color: color-mix(in srgb, var(--color-bg-base) 82%, transparent); }
.chart-tooltip b { color: var(--color-bg-base); font-variant-numeric: tabular-nums; }
.tooltip-key { display: block; width: 7px; height: 7px; border-radius: var(--radius-xs); }
.tooltip-key--created { border: 1px solid var(--color-text-muted); }
.tooltip-key--completed { background: var(--color-accent); }
.tooltip-key--due { background: var(--color-status-in-progress); }

</style>
