<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectStore } from '../store/projectStore'
import { useAnalyticsStore } from '../store/analyticsStore'
import AnalyticsFilters from '../components/analytics/AnalyticsFilters.vue'
import ProjectSnapshotCard from '../components/analytics/ProjectSnapshotCard.vue'
import TrendChart from '../components/analytics/TrendChart.vue'
import StatusBreakdownChart from '../components/analytics/StatusBreakdownChart.vue'
import AssigneeBreakdownChart from '../components/analytics/AssigneeBreakdownChart.vue'
import PriorityBreakdownChart from '../components/analytics/PriorityBreakdownChart.vue'
import TaskSnapshotList from '../components/analytics/TaskSnapshotList.vue'
import type { Granularity, TaskListScope } from '../types/analytics'

const { t } = useI18n()
const projectStore = useProjectStore()
const analyticsStore = useAnalyticsStore()

// 默认日期范围：当月
const now = new Date()
const monthStart = new Date(now.getFullYear(), now.getMonth(), 1)
const monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 0)

/** 本地日历 YYYY-MM-DD（与 date 输入、后端按日历日 interpret 一致） */
function toDateStr(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 当天所在自然周：周一 00:00 本地、周日 本地（与后端 week 桶 Monday～Sunday 一致） */
function currentWeekRangeFrom(today = new Date()): { from: string; to: string } {
  const dow = today.getDay()
  const offsetToMonday = dow === 0 ? -6 : 1 - dow
  const monday = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offsetToMonday)
  const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6)
  return { from: toDateStr(monday), to: toDateStr(sunday) }
}

/** 当天所在自然月 */
function currentMonthRangeFrom(today = new Date()): { from: string; to: string } {
  const ms = new Date(today.getFullYear(), today.getMonth(), 1)
  const me = new Date(today.getFullYear(), today.getMonth() + 1, 0)
  return { from: toDateStr(ms), to: toDateStr(me) }
}

const fromDate = ref(toDateStr(monthStart))
const toDate = ref(toDateStr(monthEnd))

const fromISO = computed(() => fromDate.value + 'T00:00:00')
const toISO = computed(() => toDate.value + 'T23:59:59')
const isDayGranularity = computed(() => analyticsStore.granularity === 'day')
type DayMetricKey = 'all' | 'created' | 'completed' | 'due'
const activeDayMetric = ref<DayMetricKey>('completed')

/** 与后端 trend 一致：按日桶累加整个 from~to，避免只取末日桶导致指标与列表脱节 */
const dayTrendRangeTotals = computed(() => {
  const trend = analyticsStore.summary?.trend ?? []
  return trend.reduce(
    (acc, b) => ({
      created: acc.created + b.createdCount,
      completed: acc.completed + b.completedCount,
      due: acc.due + b.dueCount
    }),
    { created: 0, completed: 0, due: 0 }
  )
})

/** 区间内「创建」的任务总数，与任务明细列表/状态分布口径一致（created_at 落在范围内） */
const inRangeCreatedTaskCount = computed(() => {
  const rows = analyticsStore.summary?.statusBreakdown ?? []
  return rows.reduce((s, r) => s + r.count, 0)
})

const dayMetricCards = computed(() => [
  {
    key: 'completed' as DayMetricKey,
    label: t('analytics.completedToday'),
    value: dayTrendRangeTotals.value.completed
  },
  {
    key: 'due' as DayMetricKey,
    label: t('analytics.dueToday'),
    value: dayTrendRangeTotals.value.due
  },
  {
    key: 'created' as DayMetricKey,
    label: t('analytics.createdToday'),
    value: dayTrendRangeTotals.value.created
  },
  {
    key: 'all' as DayMetricKey,
    label: t('analytics.allTasks'),
    value: inRangeCreatedTaskCount.value
  }
])

/** 任务明细与指标一致：后端按 taskListScope 筛选，避免「到期数」与「仅按创建时间拉列表」脱节 */
function resolveTaskListScope(): TaskListScope {
  if (!isDayGranularity.value) return 'all'
  const m = activeDayMetric.value
  if (m === 'completed') return 'completed'
  if (m === 'due') return 'due'
  return 'created'
}

function fetchAll() {
  const pid = projectStore.activeProjectId
  if (pid == null) return
  analyticsStore.fetchSummary(pid, fromISO.value, toISO.value)
  if (analyticsStore.showTaskList) {
    analyticsStore.fetchTasks(pid, fromISO.value, toISO.value, 1, resolveTaskListScope())
  }
}

function onDayMetricSelect(key: DayMetricKey) {
  activeDayMetric.value = key
  if (!isDayGranularity.value || !analyticsStore.showTaskList) return
  const pid = projectStore.activeProjectId
  if (pid == null) return
  const scope: TaskListScope =
    key === 'completed' ? 'completed' : key === 'due' ? 'due' : 'created'
  analyticsStore.fetchTasks(pid, fromISO.value, toISO.value, 1, scope)
}

function onGranularityChange(g: Granularity) {
  analyticsStore.setGranularity(g)
  activeDayMetric.value = g === 'day' ? 'completed' : 'all'
  /** 每次切换粒度都按目标粒度的「当前」默认重置，避免其它视图日期残留 */
  const today = new Date()
  if (g === 'day') {
    const d = toDateStr(today)
    fromDate.value = d
    toDate.value = d
  } else if (g === 'week') {
    const w = currentWeekRangeFrom(today)
    fromDate.value = w.from
    toDate.value = w.to
  } else if (g === 'month') {
    const m = currentMonthRangeFrom(today)
    fromDate.value = m.from
    toDate.value = m.to
  } else if (g === 'year') {
    const y = today.getFullYear()
    fromDate.value = `${y}-01-01`
    toDate.value = `${y}-12-31`
  }
  fetchAll()
}

function onYearRangeSelect(y: number) {
  fromDate.value = `${y}-01-01`
  toDate.value = `${y}-12-31`
  onDateChange()
}

function onDateChange() {
  activeDayMetric.value = analyticsStore.granularity === 'day' ? 'completed' : 'all'
  fetchAll()
}

function onPageChange(page: number) {
  const pid = projectStore.activeProjectId
  if (pid == null) return
  analyticsStore.fetchTasks(pid, fromISO.value, toISO.value, page, resolveTaskListScope())
}

onMounted(() => {
  if (analyticsStore.granularity === 'day') {
    const d = toDateStr(new Date())
    fromDate.value = d
    toDate.value = d
  }
  fetchAll()
})
watch(() => projectStore.activeProjectId, fetchAll)
</script>

<template>
  <div class="analytics-view">
    <header class="analytics-header">
      <h1 class="analytics-title">{{ t('analytics.title') }}</h1>
    </header>

    <AnalyticsFilters
      :granularity="analyticsStore.granularity"
      :from="fromDate"
      :to="toDate"
      @update:granularity="onGranularityChange"
      @update:from="(v) => { fromDate = v; if (analyticsStore.granularity === 'day') toDate = v; onDateChange() }"
      @update:to="(v) => { toDate = v; onDateChange() }"
      @year-range="onYearRangeSelect"
    />

    <!-- 加载中 -->
    <div v-if="analyticsStore.summaryLoading" class="analytics-loading">
      {{ t('analytics.loading') }}
    </div>

    <!-- 错误 -->
    <div v-else-if="analyticsStore.summaryError" class="analytics-error">
      <span>{{ analyticsStore.summaryError }}</span>
      <button type="button" @click="fetchAll">{{ t('analytics.retry') }}</button>
    </div>

    <!-- 数据 -->
    <template v-else-if="analyticsStore.summary">
      <div class="analytics-top-row" :class="{ 'analytics-top-row--day': isDayGranularity }">
        <ProjectSnapshotCard
          v-if="!isDayGranularity"
          :snapshot="analyticsStore.summary.currentSnapshot"
        />
        <TrendChart v-if="analyticsStore.showTrend" :trend="analyticsStore.summary.trend" />
        <section v-else class="day-focus-card">
          <h3 class="day-focus-title">{{ t('analytics.dayFocus') }}</h3>
          <div class="day-focus-grid">
            <button
              v-for="card in dayMetricCards"
              :key="card.key"
              type="button"
              class="focus-metric"
              :class="{
                'focus-metric--active': activeDayMetric === card.key,
                'focus-metric--due': card.key === 'due'
              }"
              @click="onDayMetricSelect(card.key)"
            >
              <div class="focus-metric-value">{{ card.value }}</div>
              <div class="focus-metric-label">{{ card.label }}</div>
            </button>
          </div>
        </section>
      </div>

      <div class="analytics-breakdowns">
        <StatusBreakdownChart :breakdown="analyticsStore.summary.statusBreakdown" />
        <AssigneeBreakdownChart :breakdown="analyticsStore.summary.assigneeBreakdown" />
        <PriorityBreakdownChart :breakdown="analyticsStore.summary.priorityBreakdown" />
      </div>

      <!-- 任务明细 -->
      <template v-if="analyticsStore.showTaskList">
        <div v-if="analyticsStore.taskPageLoading" class="analytics-loading">
          {{ t('analytics.loading') }}
        </div>
        <div v-else-if="analyticsStore.taskPageError" class="analytics-error">
          <span>{{ analyticsStore.taskPageError }}</span>
          <button type="button" @click="onPageChange(analyticsStore.currentPage)">{{ t('analytics.retry') }}</button>
        </div>
        <TaskSnapshotList
          v-else-if="analyticsStore.taskPage"
          :data="analyticsStore.taskPage"
          :from="fromDate"
          :to="toDate"
          :metric="activeDayMetric"
          @page="onPageChange"
        />
      </template>
    </template>
  </div>
</template>

<style scoped>
.analytics-view {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.analytics-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.analytics-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
}
.analytics-top-row {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
}
.analytics-top-row--day {
  grid-template-columns: 1fr;
}
@media (max-width: 800px) {
  .analytics-top-row {
    grid-template-columns: 1fr;
  }
}
.day-focus-card {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 16px;
}
.day-focus-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin: 0 0 12px;
}
.day-focus-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.focus-metric {
  text-align: left;
  border: 1px solid var(--color-border-subtle, var(--color-border));
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
}
.focus-metric:hover {
  background: var(--color-bg-hover);
}
.focus-metric--active {
  border-color: #6e56cf;
  background: color-mix(in srgb, #6e56cf 10%, var(--color-bg-base));
}
.focus-metric-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--color-text-primary);
}
.focus-metric--due .focus-metric-value {
  color: #f76b15;
}
.focus-metric-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
}
@media (max-width: 720px) {
  .day-focus-grid {
    grid-template-columns: repeat(4, minmax(100px, 1fr));
    overflow-x: auto;
    padding-bottom: 4px;
  }
}
.analytics-breakdowns {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
@media (max-width: 900px) {
  .analytics-breakdowns {
    grid-template-columns: 1fr;
  }
}
.analytics-loading {
  color: var(--color-text-muted);
  font-size: 14px;
  padding: 32px 0;
  text-align: center;
}
.analytics-error {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #e5484d;
  font-size: 13px;
  padding: 16px 0;
}
.analytics-error button {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
}
</style>
