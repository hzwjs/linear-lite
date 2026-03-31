import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type {
  AnalyticsQuery,
  AnalyticsSummaryResponse,
  TaskSnapshotPageResponse
} from '../../types/analytics'

export const analyticsApi = {
  getSummary(query: AnalyticsQuery): Promise<AnalyticsSummaryResponse> {
    return api
      .get<ApiResponse<AnalyticsSummaryResponse>>('/analytics/summary', { params: query })
      .then((res) => unwrap(res))
  },

  getTasks(
    query: AnalyticsQuery & { page?: number; pageSize?: number }
  ): Promise<TaskSnapshotPageResponse> {
    return api
      .get<ApiResponse<TaskSnapshotPageResponse>>('/analytics/tasks', { params: query })
      .then((res) => unwrap(res))
  }
}
