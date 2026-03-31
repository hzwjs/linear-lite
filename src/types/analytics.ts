export type Granularity = 'day' | 'week' | 'month' | 'year'

export interface AnalyticsQuery {
  projectId: number
  granularity: Granularity
  from: string // ISO datetime
  to: string
}

export interface TrendBucket {
  bucketStart: string
  bucketEnd: string
  createdCount: number
  completedCount: number
  dueCount: number
}

export interface StatusCount {
  status: string
  count: number
}

export interface AssigneeCount {
  assigneeId: number | null
  assigneeName: string
  totalCount: number
  completedCount: number
  inProgressCount: number
}

export interface PriorityCount {
  priority: string
  count: number
}

export interface ProjectSnapshot {
  totalCount: number
  statusBreakdown: StatusCount[]
  overdueCount: number
}

export interface AnalyticsMeta {
  projectId: number
  timezone: string
  bucketUnit: string
  weekStartDay: string
}

export interface AnalyticsSummaryResponse {
  meta: AnalyticsMeta
  trend: TrendBucket[]
  currentSnapshot: ProjectSnapshot
  statusBreakdown: StatusCount[]
  assigneeBreakdown: AssigneeCount[]
  priorityBreakdown: PriorityCount[]
}

export interface TaskSnapshotItem {
  taskKey: string
  title: string
  status: string
  priority: string
  assigneeId: number | null
  assigneeName: string
  createdAt: string
  completedAt: string | null
  dueDate: string | null
}

export interface TaskSnapshotPageResponse {
  items: TaskSnapshotItem[]
  total: number
  page: number
  pageSize: number
}
