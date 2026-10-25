import type { TaskScope } from './taskScope'

const VIEW_PREF_KEY = 'linear-lite-view-v2'

export type ViewType = 'board' | 'list' | 'gantt'
export type GroupBy = 'status' | 'priority' | 'assignee' | 'project' | 'none'
export type OrderBy = 'updatedAt' | 'createdAt' | 'priority' | 'dueDate' | 'title'
export type OrderDirection = 'asc' | 'desc'
export type VisibleProperty =
  | 'priority'
  | 'assignee'
  | 'project'
  | 'dueDate'
  | 'plannedStart'
  | 'updatedAt'
  | 'status'
  | 'id'
  | 'progress'
  | 'labels'

/** 当前配置版本；旧结构使用不同存储键，不执行字段兼容读取。 */
export const VIEW_PREF_VERSION = 5

export interface ViewConfig {
  layout: ViewType
  groupBy: GroupBy
  /** 列表各任务范围共用分组选择，与看板分开保存。 */
  listGroupBy: GroupBy
  orderBy: OrderBy
  orderDirection: OrderDirection
  visibleProperties: VisibleProperty[]
  showEmptyGroups: boolean
  scope: TaskScope
  /** Phase 7: 列表是否显示子任务行 */
  showSubIssues: boolean
  /** Phase 7: 是否展示多级子任务（否则仅直接子任务） */
  nestedSubIssues: boolean
  /** 本地视图配置版本 */
  viewPrefVersion?: number
}

const KNOWN_VISIBLE: readonly VisibleProperty[] = [
  'priority',
  'assignee',
  'project',
  'dueDate',
  'plannedStart',
  'updatedAt',
  'status',
  'id',
  'progress',
  'labels'
] as const

export const DEFAULT_VIEW_CONFIG: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  listGroupBy: 'none',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['assignee', 'dueDate', 'labels', 'plannedStart', 'priority', 'progress'],
  showEmptyGroups: false,
  /** 默认与命令栏「进行中」一致：隐藏已完成/已取消/重复及待规划（backlog） */
  scope: 'active',
  showSubIssues: true,
  nestedSubIssues: true,
  viewPrefVersion: VIEW_PREF_VERSION
}

function sanitizeVisibleProperties(value: unknown): VisibleProperty[] {
  if (!Array.isArray(value)) return [...DEFAULT_VIEW_CONFIG.visibleProperties]
  const filtered = value.filter((x): x is VisibleProperty =>
    (KNOWN_VISIBLE as readonly string[]).includes(x as string)
  )
  return filtered.length > 0 ? filtered : [...DEFAULT_VIEW_CONFIG.visibleProperties]
}

function isViewType(value: unknown): value is ViewType {
  return value === 'board' || value === 'list' || value === 'gantt'
}

export function normalizeViewConfig(value: unknown): ViewConfig {
  if (!value || typeof value !== 'object') return { ...DEFAULT_VIEW_CONFIG }

  const candidate = value as Partial<ViewConfig>

  return {
    layout: isViewType(candidate.layout) ? candidate.layout : DEFAULT_VIEW_CONFIG.layout,
    groupBy: candidate.groupBy ?? DEFAULT_VIEW_CONFIG.groupBy,
    listGroupBy: candidate.listGroupBy ?? DEFAULT_VIEW_CONFIG.listGroupBy,
    orderBy: candidate.orderBy ?? DEFAULT_VIEW_CONFIG.orderBy,
    orderDirection: candidate.orderDirection ?? DEFAULT_VIEW_CONFIG.orderDirection,
    visibleProperties: sanitizeVisibleProperties(candidate.visibleProperties),
    showEmptyGroups: candidate.showEmptyGroups ?? DEFAULT_VIEW_CONFIG.showEmptyGroups,
    scope: candidate.scope === 'all' || candidate.scope === 'backlog' || candidate.scope === 'active'
      ? candidate.scope : DEFAULT_VIEW_CONFIG.scope,
    showSubIssues: candidate.showSubIssues ?? DEFAULT_VIEW_CONFIG.showSubIssues,
    nestedSubIssues: candidate.nestedSubIssues ?? DEFAULT_VIEW_CONFIG.nestedSubIssues,
    viewPrefVersion: VIEW_PREF_VERSION
  }
}

export function getStoredViewConfig(): ViewConfig {
  try {
    const raw = localStorage.getItem(VIEW_PREF_KEY)
    if (!raw) return { ...DEFAULT_VIEW_CONFIG }

    return normalizeViewConfig(JSON.parse(raw))
  } catch (_) {
    return { ...DEFAULT_VIEW_CONFIG }
  }
}

export function setStoredViewConfig(config: ViewConfig) {
  try {
    localStorage.setItem(VIEW_PREF_KEY, JSON.stringify(config))
  } catch (_) {}
}
