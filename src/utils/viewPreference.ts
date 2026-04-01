const VIEW_PREF_KEY = 'linear-lite-view'

export type ViewType = 'board' | 'list'
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
export type CompletedVisibility = 'all' | 'open_only'

export interface ViewConfig {
  layout: ViewType
  groupBy: GroupBy
  orderBy: OrderBy
  orderDirection: OrderDirection
  visibleProperties: VisibleProperty[]
  showEmptyGroups: boolean
  completedVisibility: CompletedVisibility
  /** Phase 7: 列表是否显示子任务行 */
  showSubIssues: boolean
  /** Phase 7: 是否展示多级子任务（否则仅直接子任务） */
  nestedSubIssues: boolean
}

export const DEFAULT_VIEW_CONFIG: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['assignee', 'dueDate', 'plannedStart', 'priority', 'progress', 'updatedAt'],
  showEmptyGroups: false,
  completedVisibility: 'all',
  showSubIssues: true,
  nestedSubIssues: true
}

function isViewType(value: unknown): value is ViewType {
  return value === 'board' || value === 'list'
}

function normalizeConfig(value: unknown): ViewConfig {
  if (!value || typeof value !== 'object') return { ...DEFAULT_VIEW_CONFIG }

  const candidate = value as Partial<ViewConfig>
  return {
    layout: isViewType(candidate.layout) ? candidate.layout : DEFAULT_VIEW_CONFIG.layout,
    groupBy: candidate.groupBy ?? DEFAULT_VIEW_CONFIG.groupBy,
    orderBy: candidate.orderBy ?? DEFAULT_VIEW_CONFIG.orderBy,
    orderDirection: candidate.orderDirection ?? DEFAULT_VIEW_CONFIG.orderDirection,
    visibleProperties: Array.isArray(candidate.visibleProperties)
      ? [...candidate.visibleProperties]
      : [...DEFAULT_VIEW_CONFIG.visibleProperties],
    showEmptyGroups: candidate.showEmptyGroups ?? DEFAULT_VIEW_CONFIG.showEmptyGroups,
    completedVisibility: candidate.completedVisibility ?? DEFAULT_VIEW_CONFIG.completedVisibility,
    showSubIssues: candidate.showSubIssues ?? DEFAULT_VIEW_CONFIG.showSubIssues,
    nestedSubIssues: candidate.nestedSubIssues ?? DEFAULT_VIEW_CONFIG.nestedSubIssues
  }
}

export function getStoredViewConfig(): ViewConfig {
  try {
    const raw = localStorage.getItem(VIEW_PREF_KEY)
    if (!raw) return { ...DEFAULT_VIEW_CONFIG }

    if (raw === 'board' || raw === 'list') {
      return {
        ...DEFAULT_VIEW_CONFIG,
        layout: raw
      }
    }

    return normalizeConfig(JSON.parse(raw))
  } catch (_) {
    return { ...DEFAULT_VIEW_CONFIG }
  }
}

export function setStoredViewConfig(config: ViewConfig) {
  try {
    localStorage.setItem(VIEW_PREF_KEY, JSON.stringify(config))
  } catch (_) {}
}
