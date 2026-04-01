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

/** 递增：用于在本地配置中一次性补全新增的可见列，避免老数据缺少 progress 等字段 */
export const VIEW_PREF_VERSION = 2

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
  /** 本地视图偏好版本，供迁移逻辑使用 */
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
  'progress'
] as const

/** v2：在引入计划开始/进度列之前保存的配置不会包含这两项；迁移一次后写入 viewPrefVersion */
const V2_APPEND_VISIBLE: readonly VisibleProperty[] = ['plannedStart', 'progress']

export const DEFAULT_VIEW_CONFIG: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['assignee', 'dueDate', 'plannedStart', 'priority', 'progress'],
  showEmptyGroups: false,
  completedVisibility: 'all',
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

function migrateVisibleProperties(
  visible: VisibleProperty[],
  storedVersion: number
): { visibleProperties: VisibleProperty[]; viewPrefVersion: number } {
  let next = [...visible]
  let version = storedVersion
  if (version < VIEW_PREF_VERSION) {
    for (const p of V2_APPEND_VISIBLE) {
      if (!next.includes(p)) next.push(p)
    }
    version = VIEW_PREF_VERSION
  }
  return { visibleProperties: next, viewPrefVersion: version }
}

function isViewType(value: unknown): value is ViewType {
  return value === 'board' || value === 'list'
}

function normalizeConfig(value: unknown): ViewConfig {
  if (!value || typeof value !== 'object') return { ...DEFAULT_VIEW_CONFIG }

  const candidate = value as Partial<ViewConfig>
  const storedVersion =
    typeof candidate.viewPrefVersion === 'number' ? candidate.viewPrefVersion : 0
  let visibleProperties = sanitizeVisibleProperties(candidate.visibleProperties)
  const migrated = migrateVisibleProperties(visibleProperties, storedVersion)
  visibleProperties = migrated.visibleProperties

  return {
    layout: isViewType(candidate.layout) ? candidate.layout : DEFAULT_VIEW_CONFIG.layout,
    groupBy: candidate.groupBy ?? DEFAULT_VIEW_CONFIG.groupBy,
    orderBy: candidate.orderBy ?? DEFAULT_VIEW_CONFIG.orderBy,
    orderDirection: candidate.orderDirection ?? DEFAULT_VIEW_CONFIG.orderDirection,
    visibleProperties,
    showEmptyGroups: candidate.showEmptyGroups ?? DEFAULT_VIEW_CONFIG.showEmptyGroups,
    completedVisibility: candidate.completedVisibility ?? DEFAULT_VIEW_CONFIG.completedVisibility,
    showSubIssues: candidate.showSubIssues ?? DEFAULT_VIEW_CONFIG.showSubIssues,
    nestedSubIssues: candidate.nestedSubIssues ?? DEFAULT_VIEW_CONFIG.nestedSubIssues,
    viewPrefVersion: migrated.viewPrefVersion
  }
}

export function getStoredViewConfig(): ViewConfig {
  try {
    const raw = localStorage.getItem(VIEW_PREF_KEY)
    if (!raw) return { ...DEFAULT_VIEW_CONFIG }

    if (raw === 'board' || raw === 'list') {
      return {
        ...DEFAULT_VIEW_CONFIG,
        layout: raw,
        viewPrefVersion: VIEW_PREF_VERSION
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
