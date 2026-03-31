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
import type { Granularity } from '../types/analytics'

const { t } = useI18n()
const projectStore = useProjectStore()
const analyticsStore = useAnalyticsStore()

// 默认日期范围：当月
const now = new Date()
const monthStart = new Date(now.getFullYear(), now.getMonth(), 1)
const monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 0)

function toDateStr(d: Date): string {
  return d.toISOString().substring(0, 10)
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

const filteredTaskPage = computed(() => {
  const page = analyticsStore.taskPage
  if (!page || !isDayGranularity.value || activeDayMetric.value === 'all') {
    return page
  }
  const fromDay = fromDate.value
  const toDay = toDate.value
  const items = page.items.filter((item) => {
    if (activeDayMetric.value === 'created') {
      const createdDay = item.createdAt?.substring(0, 10)
      return createdDay >= fromDay && createdDay <= toDay
    }
    if (activeDayMetric.value === 'completed') {
      const completedDay = item.completedAt?.substring(0, 10)
      return completedDay != null && completedDay >= fromDay && completedDay <= toDay
    }
    const dueDay = item.dueDate?.substring(0, 10)
    return dueDay != null && dueDay >= fromDay && dueDay <= toDay
  })
  return {
    ...page,
    items,
    total: items.length,
    page: 1
  }
})

function fetchAll() {
  const pid = projectStore.activeProjectId
  if (pid == null) return
  analyticsStore.fetchSummary(pid, fromISO.value, toISO.value)
  if (analyticsStore.showTaskList) {
    analyticsStore.fetchTasks(pid, fromISO.value, toISO.value, 1)
  }
}

function onGranularityChange(g: Granularity) {
  analyticsStore.setGranularity(g)
  activeDayMetric.value = g === 'day' ? 'completed' : 'all'
  if (g === 'day') {
    toDate.value = fromDate.value
  }
  fetchAll()
}

function onDateChange() {
  activeDayMetric.value = analyticsStore.granularity === 'day' ? 'completed' : 'all'
  fetchAll()
}

function onPageChange(page: number) {
  const pid = projectStore.activeProjectId
  if (pid == null) return
  analyticsStore.fetchTasks(pid, fromISO.value, toISO.value, page)
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
              @click="activeDayMetric = card.key"
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
          v-else-if="filteredTaskPage"
          :data="filteredTaskPage"
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
