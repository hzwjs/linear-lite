<!--
THESIS: 统计页是一份可操作的项目复盘账本，不是等权图表陈列。
OWN-WORLD: 延续 Linear-Lite 浅色工作区、细分隔线、灰蓝强调与紧凑控件。
STORY: 先判断健康度，再解释交付节奏和风险归属，最后进入任务处理。
FIRST VIEWPORT: 健康结论占主位，四个可下钻指标在同一水平证据带内。
FORM: Operating review ledger；结构候选 5；surface seed 5e74e645。
-->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AlertTriangle, CheckCircle2, TrendingUp } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import AnalyticsFilters from '../components/analytics/AnalyticsFilters.vue'
import AssigneeBreakdownChart from '../components/analytics/AssigneeBreakdownChart.vue'
import StatusBreakdownChart from '../components/analytics/StatusBreakdownChart.vue'
import TaskSnapshotList from '../components/analytics/TaskSnapshotList.vue'
import TrendChart from '../components/analytics/TrendChart.vue'
import { useAnalyticsStore } from '../store/analyticsStore'
import { useProjectStore } from '../store/projectStore'
import type { Granularity, TaskListScope } from '../types/analytics'

const { t } = useI18n()
const projectStore = useProjectStore()
const analyticsStore = useAnalyticsStore()

function toDateStr(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function currentWeekRange(today = new Date()): { from: string; to: string } {
  const weekday = today.getDay()
  const offsetToMonday = weekday === 0 ? -6 : 1 - weekday
  const monday = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offsetToMonday)
  const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6)
  return { from: toDateStr(monday), to: toDateStr(sunday) }
}

function currentMonthRange(today = new Date()): { from: string; to: string } {
  return {
    from: toDateStr(new Date(today.getFullYear(), today.getMonth(), 1)),
    to: toDateStr(new Date(today.getFullYear(), today.getMonth() + 1, 0))
  }
}

function rangeForGranularity(granularity: Granularity, today = new Date()): { from: string; to: string } {
  if (granularity === 'day') {
    const date = toDateStr(today)
    return { from: date, to: date }
  }
  if (granularity === 'week') return currentWeekRange(today)
  if (granularity === 'month') return currentMonthRange(today)
  return { from: `${today.getFullYear()}-01-01`, to: `${today.getFullYear()}-12-31` }
}

const initialRange = rangeForGranularity(analyticsStore.granularity)
const fromDate = ref(initialRange.from)
const toDate = ref(initialRange.to)
const activeMetric = ref<TaskListScope>('completed')

const fromISO = computed(() => `${fromDate.value}T00:00:00`)
const toISO = computed(() => `${toDate.value}T23:59:59`)
const activeProject = computed(() =>
  projectStore.projects.find((project) => project.id === projectStore.activeProjectId)
)

/** 趋势桶是所有区间指标的唯一前端来源，保证卡片、结论和图表口径一致。 */
const periodTotals = computed(() => {
  if (analyticsStore.summary === null) return { created: 0, completed: 0, due: 0 }
  return analyticsStore.summary.trend.reduce(
    (totals, bucket) => ({
      created: totals.created + bucket.createdCount,
      completed: totals.completed + bucket.completedCount,
      due: totals.due + bucket.dueCount
    }),
    { created: 0, completed: 0, due: 0 }
  )
})

const netFlow = computed(() => periodTotals.value.created - periodTotals.value.completed)
const overdueCount = computed(() =>
  analyticsStore.summary === null ? 0 : analyticsStore.summary.currentSnapshot.overdueCount
)

type HealthTone = 'healthy' | 'attention' | 'critical'
const healthTone = computed<HealthTone>(() => {
  if (overdueCount.value > 0) return 'critical'
  if (netFlow.value > 0) return 'attention'
  return 'healthy'
})

const healthTitle = computed(() => t(`analytics.health.${healthTone.value}.title`))
const healthDetail = computed(() => {
  if (healthTone.value === 'critical') {
    return t('analytics.health.critical.detail', { count: overdueCount.value })
  }
  if (healthTone.value === 'attention') {
    return t('analytics.health.attention.detail', { count: netFlow.value })
  }
  return t('analytics.health.healthy.detail', { count: Math.abs(netFlow.value) })
})

const reviewPeriodLabel = computed(() => t(`analytics.period.${analyticsStore.granularity}`))
const rangeLabel = computed(() =>
  fromDate.value === toDate.value ? fromDate.value : `${fromDate.value} — ${toDate.value}`
)

const metricItems = computed(() => [
  {
    key: 'overdue' as const,
    label: t('analytics.overdueNow'),
    value: overdueCount.value,
    context: t('analytics.currentProject'),
    tone: 'critical'
  },
  {
    key: 'completed' as const,
    label: t('analytics.completed'),
    value: periodTotals.value.completed,
    context: reviewPeriodLabel.value,
    tone: 'positive'
  },
  {
    key: 'created' as const,
    label: t('analytics.created'),
    value: periodTotals.value.created,
    context: reviewPeriodLabel.value,
    tone: 'neutral'
  },
  {
    key: 'due' as const,
    label: t('analytics.due'),
    value: periodTotals.value.due,
    context: reviewPeriodLabel.value,
    tone: 'warning'
  }
])

function fetchTasks(page = 1) {
  const projectId = projectStore.activeProjectId
  if (projectId == null) return
  analyticsStore.fetchTasks(projectId, fromISO.value, toISO.value, page, activeMetric.value)
}

function fetchAll() {
  const projectId = projectStore.activeProjectId
  if (projectId == null) return
  analyticsStore.fetchSummary(projectId, fromISO.value, toISO.value)
  fetchTasks(1)
}

function selectMetric(metric: TaskListScope) {
  activeMetric.value = metric
  fetchTasks(1)
}

function changeGranularity(granularity: Granularity) {
  analyticsStore.setGranularity(granularity)
  const range = rangeForGranularity(granularity)
  fromDate.value = range.from
  toDate.value = range.to
  activeMetric.value = 'completed'
  fetchAll()
}

function selectYear(year: number) {
  fromDate.value = `${year}-01-01`
  toDate.value = `${year}-12-31`
  activeMetric.value = 'completed'
  fetchAll()
}

function updateFrom(value: string) {
  fromDate.value = value
  if (analyticsStore.granularity === 'day') toDate.value = value
  activeMetric.value = 'completed'
  fetchAll()
}

function updateTo(value: string) {
  toDate.value = value
  activeMetric.value = 'completed'
  fetchAll()
}

function shiftPeriod(direction: -1 | 1) {
  const current = new Date(`${fromDate.value}T12:00:00`)
  if (analyticsStore.granularity === 'day') current.setDate(current.getDate() + direction)
  else if (analyticsStore.granularity === 'week') current.setDate(current.getDate() + direction * 7)
  else if (analyticsStore.granularity === 'month') {
    // 先归一到月首再偏移，避免 31 日跨月时跳过目标月份。
    current.setDate(1)
    current.setMonth(current.getMonth() + direction)
  }
  else current.setFullYear(current.getFullYear() + direction)

  const range = rangeForGranularity(analyticsStore.granularity, current)
  fromDate.value = range.from
  toDate.value = range.to
  activeMetric.value = 'completed'
  fetchAll()
}

onMounted(fetchAll)
watch(() => projectStore.activeProjectId, fetchAll)
</script>

<template>
  <main id="analytics-main" class="analytics-view">
    <header class="analytics-header">
      <div class="analytics-heading">
        <div class="analytics-title-row">
          <h1 class="analytics-title">{{ t('analytics.title') }}</h1>
          <span v-if="activeProject" class="project-context">{{ activeProject.name }}</span>
        </div>
        <p class="analytics-purpose">{{ t('analytics.purpose') }}</p>
      </div>
      <AnalyticsFilters
        :granularity="analyticsStore.granularity"
        :from="fromDate"
        :to="toDate"
        @update:granularity="changeGranularity"
        @update:from="updateFrom"
        @update:to="updateTo"
        @year-range="selectYear"
        @shift="shiftPeriod"
      />
    </header>

    <div v-if="analyticsStore.summaryLoading && !analyticsStore.summary" class="analytics-loading" role="status">
      <span class="loading-line loading-line--wide" />
      <span class="loading-line" />
      <span class="sr-only">{{ t('analytics.loading') }}</span>
    </div>

    <div v-else-if="analyticsStore.summaryError && !analyticsStore.summary" class="analytics-error" role="alert">
      <div>
        <strong>{{ t('analytics.loadError') }}</strong>
        <p>{{ analyticsStore.summaryError }}</p>
      </div>
      <button type="button" class="retry-button" @click="fetchAll">{{ t('analytics.retry') }}</button>
    </div>

    <template v-else-if="analyticsStore.summary">
      <section class="health-ledger" :data-tone="healthTone" aria-labelledby="health-heading">
        <div class="health-verdict">
          <div class="health-kicker">
            <span class="health-icon" aria-hidden="true">
              <AlertTriangle v-if="healthTone === 'critical'" :size="16" />
              <TrendingUp v-else-if="healthTone === 'attention'" :size="16" />
              <CheckCircle2 v-else :size="16" />
            </span>
            {{ t('analytics.reviewConclusion', { period: reviewPeriodLabel }) }}
          </div>
          <h2 id="health-heading" class="health-title">{{ healthTitle }}</h2>
          <p class="health-detail">{{ healthDetail }}</p>
          <div class="health-meta">
            <span>{{ rangeLabel }}</span>
            <span>{{ t('analytics.netFlowValue', { value: netFlow > 0 ? `+${netFlow}` : netFlow }) }}</span>
          </div>
        </div>

        <div class="metric-ledger" :aria-label="t('analytics.keyMetrics')">
          <button
            v-for="metric in metricItems"
            :key="metric.key"
            type="button"
            class="metric-row"
            :class="[`metric-row--${metric.tone}`, { 'metric-row--active': activeMetric === metric.key }]"
            :aria-pressed="activeMetric === metric.key"
            @click="selectMetric(metric.key)"
          >
            <span class="metric-label-group">
              <span class="metric-label">{{ metric.label }}</span>
              <span class="metric-context">{{ metric.context }}</span>
            </span>
            <span class="metric-value">{{ metric.value }}</span>
          </button>
        </div>
      </section>

      <section class="analysis-section" aria-labelledby="delivery-heading">
        <div class="section-heading">
          <div>
            <h2 id="delivery-heading">{{ t('analytics.deliveryRhythm') }}</h2>
            <p>{{ t('analytics.deliveryRhythmDescription') }}</p>
          </div>
          <span class="section-range">{{ rangeLabel }}</span>
        </div>
        <TrendChart v-if="analyticsStore.showTrend" :trend="analyticsStore.summary.trend" />
        <div v-else class="day-balance">
          <span>{{ t('analytics.created') }} <strong>{{ periodTotals.created }}</strong></span>
          <span>{{ t('analytics.completed') }} <strong>{{ periodTotals.completed }}</strong></span>
          <span>{{ t('analytics.due') }} <strong>{{ periodTotals.due }}</strong></span>
        </div>
      </section>

      <section class="risk-section" aria-labelledby="risk-heading">
        <div class="section-heading">
          <div>
            <h2 id="risk-heading">{{ t('analytics.riskLocation') }}</h2>
            <p>{{ t('analytics.riskLocationDescription') }}</p>
          </div>
        </div>
        <div class="risk-grid">
          <StatusBreakdownChart
            :breakdown="analyticsStore.summary.currentSnapshot.statusBreakdown"
            :total="analyticsStore.summary.currentSnapshot.totalCount"
            :overdue-count="analyticsStore.summary.currentSnapshot.overdueCount"
          />
          <AssigneeBreakdownChart :breakdown="analyticsStore.summary.assigneeBreakdown" />
        </div>
      </section>

      <section class="action-section" aria-labelledby="action-heading">
        <div class="section-heading section-heading--action">
          <div>
            <h2 id="action-heading">{{ t('analytics.actionQueue') }}</h2>
            <p>{{ t('analytics.actionQueueDescription') }}</p>
          </div>
          <span class="active-filter">{{ t(`analytics.metric.${activeMetric}`) }}</span>
        </div>

        <div v-if="analyticsStore.taskPageError" class="analytics-error analytics-error--inline" role="alert">
          <span>{{ analyticsStore.taskPageError }}</span>
          <button type="button" class="retry-button" @click="fetchTasks(analyticsStore.currentPage)">
            {{ t('analytics.retry') }}
          </button>
        </div>
        <TaskSnapshotList
          v-else-if="analyticsStore.taskPage"
          :data="analyticsStore.taskPage"
          :from="fromDate"
          :to="toDate"
          :metric="activeMetric"
          :loading="analyticsStore.taskPageLoading"
          @page="fetchTasks"
        />
      </section>
    </template>
  </main>
</template>

<style scoped>
.analytics-view {
  --analytics-critical: #b42318;
  --analytics-critical-soft: #fff4f2;
  --analytics-critical-border: #f2c6c2;
  --analytics-warning: #9a6700;
  --analytics-warning-soft: #fff8e6;
  --analytics-positive: #277a4b;
  --analytics-positive-soft: #effaf3;
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 28px clamp(24px, 3vw, 48px) 64px;
  background: var(--color-bg-base);
}

.analytics-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32px;
  max-width: 1240px;
  margin: 0 auto 28px;
}

.analytics-heading { min-width: 0; }
.analytics-title-row { display: flex; align-items: center; gap: 10px; }
.analytics-title { margin: 0; font-size: 20px; font-weight: 650; letter-spacing: -0.025em; }
.project-context {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-left: 10px;
  border-left: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 12px;
}
.analytics-purpose { margin: 5px 0 0; color: var(--color-text-muted); font-size: 12px; }

.health-ledger,
.analysis-section,
.risk-section,
.action-section,
.analytics-loading,
.analytics-error {
  max-width: 1240px;
  margin-inline: auto;
}

.health-ledger {
  display: grid;
  grid-template-columns: minmax(300px, 0.9fr) minmax(520px, 1.35fr);
  border-block: 1px solid var(--color-border);
  margin-bottom: 34px;
}

.health-verdict {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 220px;
  padding: 28px 32px 28px 0;
  border-right: 1px solid var(--color-border);
}
.health-kicker { display: flex; align-items: center; gap: 7px; color: var(--color-text-muted); font-size: 12px; }
.health-icon { display: inline-flex; color: var(--analytics-positive); }
.health-ledger[data-tone='attention'] .health-icon { color: var(--analytics-warning); }
.health-ledger[data-tone='critical'] .health-icon { color: var(--analytics-critical); }
.health-title { max-width: 19ch; margin: 16px 0 8px; font-size: 28px; line-height: 1.12; letter-spacing: -0.04em; }
.health-detail { max-width: 52ch; margin: 0; color: var(--color-text-secondary); font-size: 14px; line-height: 1.55; }
.health-meta { display: flex; gap: 18px; margin-top: 24px; color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; }

.metric-ledger { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.metric-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 110px;
  padding: 22px 24px;
  border-bottom: 1px solid var(--color-border);
  text-align: left;
  transition: background-color var(--transition-fast), box-shadow var(--transition-fast);
}
.metric-row:nth-child(odd) { border-right: 1px solid var(--color-border); }
.metric-row:nth-last-child(-n + 2) { border-bottom: 0; }
.metric-row:hover { background: var(--color-bg-subtle); }
.metric-row:focus-visible { position: relative; outline: 2px solid var(--color-accent); outline-offset: -2px; z-index: 1; }
.metric-row--active { background: var(--color-accent-muted); box-shadow: inset 0 -2px var(--color-accent); }
.metric-row--critical.metric-row--active { background: var(--analytics-critical-soft); box-shadow: inset 0 -2px var(--analytics-critical); }
.metric-label-group { display: flex; flex-direction: column; gap: 5px; }
.metric-label { color: var(--color-text-secondary); font-size: 13px; font-weight: 600; }
.metric-context { color: var(--color-text-muted); font-size: var(--font-size-caption); }
.metric-value { color: var(--color-text-primary); font-size: 28px; font-weight: 650; letter-spacing: -0.04em; font-variant-numeric: tabular-nums; }
.metric-row--critical .metric-value { color: var(--analytics-critical); }

.analysis-section,
.risk-section { margin-bottom: 36px; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 14px; }
.section-heading h2 { margin: 0; font-size: 14px; font-weight: 650; }
.section-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.section-range,
.active-filter { color: var(--color-text-muted); font-size: var(--font-size-caption); font-variant-numeric: tabular-nums; }
.active-filter { padding: 4px 8px; border: 1px solid var(--color-accent-muted-border); border-radius: var(--radius-sm); color: var(--color-accent); background: var(--color-accent-muted); }
.day-balance { display: grid; grid-template-columns: repeat(3, 1fr); border-block: 1px solid var(--color-border); }
.day-balance span { padding: 18px 20px; color: var(--color-text-muted); font-size: 12px; }
.day-balance span + span { border-left: 1px solid var(--color-border); }
.day-balance strong { float: right; color: var(--color-text-primary); font-size: 18px; font-variant-numeric: tabular-nums; }
.risk-grid { display: grid; grid-template-columns: minmax(300px, 0.8fr) minmax(460px, 1.2fr); border-block: 1px solid var(--color-border); }
.risk-grid > :first-child { border-right: 1px solid var(--color-border); }

.analytics-loading { display: flex; flex-direction: column; gap: 12px; padding: 32px 0; }
.loading-line { width: 42%; height: 12px; border-radius: var(--radius-sm); background: var(--color-bg-muted); animation: loading-pulse 1.2s ease-in-out infinite alternate; }
.loading-line--wide { width: 72%; height: 28px; }
.analytics-error { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 16px; border: 1px solid var(--analytics-critical-border); background: var(--analytics-critical-soft); color: var(--analytics-critical); }
.analytics-error strong { font-size: 13px; }
.analytics-error p { margin: 3px 0 0; font-size: 12px; }
.analytics-error--inline { margin-bottom: 10px; }
.retry-button { min-height: 32px; padding: 5px 12px; border: 1px solid currentColor; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; }
.retry-button:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }

@keyframes loading-pulse { to { opacity: 0.42; } }

@media (max-width: 980px) {
  .analytics-header { flex-direction: column; gap: 16px; }
  .health-ledger { grid-template-columns: 1fr; }
  .health-verdict { min-height: 0; padding-right: 0; border-right: 0; border-bottom: 1px solid var(--color-border); }
  .risk-grid { grid-template-columns: 1fr; }
  .risk-grid > :first-child { border-right: 0; border-bottom: 1px solid var(--color-border); }
}

@media (max-width: 640px) {
  .analytics-view { padding: 20px 16px 48px; }
  .metric-ledger { grid-template-columns: 1fr; }
  .metric-row, .metric-row:nth-child(odd), .metric-row:nth-last-child(-n + 2) { min-height: 82px; border-right: 0; border-bottom: 1px solid var(--color-border); }
  .metric-row:last-child { border-bottom: 0; }
  .health-meta, .section-heading { align-items: flex-start; flex-direction: column; gap: 8px; }
  .day-balance { grid-template-columns: 1fr; }
  .day-balance span + span { border-left: 0; border-top: 1px solid var(--color-border); }
}

@media (prefers-reduced-motion: reduce) {
  .loading-line { animation: none; }
  .metric-row { transition: none; }
}
</style>
