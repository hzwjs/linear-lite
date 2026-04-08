const VIEW_PREF_KEY = 'linear-lite-view'

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
export type CompletedVisibility = 'all' | 'open_only'

/** 递增：用于在本地配置中一次性补全新增的可见列，避免老数据缺少 progress / labels 等字段 */
export const VIEW_PREF_VERSION = 4

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
  'progress',
  'labels'
] as const

/** v2：在引入计划开始/进度列之前保存的配置不会包含这两项 */
const V2_APPEND_VISIBLE: readonly VisibleProperty[] = ['plannedStart', 'progress']

/** v3：列表标签列 */
const V3_APPEND_VISIBLE: readonly VisibleProperty[] = ['labels']

export const DEFAULT_VIEW_CONFIG: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['assignee', 'dueDate', 'labels', 'plannedStart', 'priority', 'progress'],
  showEmptyGroups: false,
  /** 默认与命令栏「进行中」一致：隐藏已完成/已取消/重复 */
  completedVisibility: 'open_only',
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
  if (version < 2) {
    for (const p of V2_APPEND_VISIBLE) {
      if (!next.includes(p)) next.push(p)
    }
    version = 2
  }
  if (version < 3) {
    for (const p of V3_APPEND_VISIBLE) {
      if (!next.includes(p)) next.push(p)
    }
    version = 3
  }
  return { visibleProperties: next, viewPrefVersion: version }
}

/** v4：命令栏默认选中「进行中」（仅未完成），历史存盘里的 all 视为旧默认并升级 */
function migrateCompletedVisibilityForScopeTab(
  completedVisibility: CompletedVisibility,
  visibleVersion: number
): { completedVisibility: CompletedVisibility; viewPrefVersion: number } {
  if (visibleVersion >= 4) {
    return { completedVisibility, viewPrefVersion: visibleVersion }
  }
  return { completedVisibility: 'open_only', viewPrefVersion: 4 }
}

function isViewType(value: unknown): value is ViewType {
  return value === 'board' || value === 'list' || value === 'gantt'
}

export function normalizeViewConfig(value: unknown): ViewConfig {
  if (!value || typeof value !== 'object') return { ...DEFAULT_VIEW_CONFIG }

  const candidate = value as Partial<ViewConfig>
  const storedVersion =
    typeof candidate.viewPrefVersion === 'number' ? candidate.viewPrefVersion : 0
  let visibleProperties = sanitizeVisibleProperties(candidate.visibleProperties)
  const migrated = migrateVisibleProperties(visibleProperties, storedVersion)
  visibleProperties = migrated.visibleProperties

  const rawCompleted =
    candidate.completedVisibility === 'all' || candidate.completedVisibility === 'open_only'
      ? candidate.completedVisibility
      : DEFAULT_VIEW_CONFIG.completedVisibility
  const scopeMigrated = migrateCompletedVisibilityForScopeTab(
    rawCompleted,
    migrated.viewPrefVersion
  )

  return {
    layout: isViewType(candidate.layout) ? candidate.layout : DEFAULT_VIEW_CONFIG.layout,
    groupBy: candidate.groupBy ?? DEFAULT_VIEW_CONFIG.groupBy,
    orderBy: candidate.orderBy ?? DEFAULT_VIEW_CONFIG.orderBy,
    orderDirection: candidate.orderDirection ?? DEFAULT_VIEW_CONFIG.orderDirection,
    visibleProperties,
    showEmptyGroups: candidate.showEmptyGroups ?? DEFAULT_VIEW_CONFIG.showEmptyGroups,
    completedVisibility: scopeMigrated.completedVisibility,
    showSubIssues: candidate.showSubIssues ?? DEFAULT_VIEW_CONFIG.showSubIssues,
    nestedSubIssues: candidate.nestedSubIssues ?? DEFAULT_VIEW_CONFIG.nestedSubIssues,
    viewPrefVersion: scopeMigrated.viewPrefVersion
  }
}

export function getStoredViewConfig(): ViewConfig {
  try {
    const raw = localStorage.getItem(VIEW_PREF_KEY)
    if (!raw) return { ...DEFAULT_VIEW_CONFIG }

    if (raw === 'board' || raw === 'list' || raw === 'gantt') {
      return {
        ...DEFAULT_VIEW_CONFIG,
        layout: raw,
        viewPrefVersion: VIEW_PREF_VERSION
      }
    }

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
