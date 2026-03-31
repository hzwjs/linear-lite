import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { analyticsApi } from '../services/api/analytics'
import type {
  Granularity,
  AnalyticsSummaryResponse,
  TaskSnapshotPageResponse
} from '../types/analytics'

export const useAnalyticsStore = defineStore('analyticsStore', () => {
  const summary = ref<AnalyticsSummaryResponse | null>(null)
  const summaryLoading = ref(false)
  const summaryError = ref<string | null>(null)

  const taskPage = ref<TaskSnapshotPageResponse | null>(null)
  const taskPageLoading = ref(false)
  const taskPageError = ref<string | null>(null)

  const granularity = ref<Granularity>('day')
  const currentPage = ref(1)

  const showTrend = computed(() => granularity.value !== 'day')
  const showTaskList = computed(
    () => granularity.value === 'day' || granularity.value === 'week'
  )

  async function fetchSummary(projectId: number, from: string, to: string) {
    summaryLoading.value = true
    summaryError.value = null
    try {
      summary.value = await analyticsApi.getSummary({
        projectId,
        granularity: granularity.value,
        from,
        to
      })
    } catch (e: any) {
      summaryError.value = e?.message ?? '加载统计失败'
    } finally {
      summaryLoading.value = false
    }
  }

  async function fetchTasks(projectId: number, from: string, to: string, page = 1) {
    taskPageLoading.value = true
    taskPageError.value = null
    currentPage.value = page
    try {
      taskPage.value = await analyticsApi.getTasks({
        projectId,
        granularity: granularity.value,
        from,
        to,
        page,
        pageSize: 50
      })
    } catch (e: any) {
      taskPageError.value = e?.message ?? '加载任务列表失败'
    } finally {
      taskPageLoading.value = false
    }
  }

  function setGranularity(g: Granularity) {
    granularity.value = g
  }

  function $reset() {
    summary.value = null
    taskPage.value = null
    summaryError.value = null
    taskPageError.value = null
    currentPage.value = 1
  }

  return {
    summary,
    summaryLoading,
    summaryError,
    taskPage,
    taskPageLoading,
    taskPageError,
    granularity,
    currentPage,
    showTrend,
    showTaskList,
    fetchSummary,
    fetchTasks,
    setGranularity,
    $reset
  }
})
