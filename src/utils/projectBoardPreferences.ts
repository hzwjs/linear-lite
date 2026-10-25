import type { Status, Priority } from '../types/domain'
import type { ViewConfig } from './viewPreference'
import { DEFAULT_VIEW_CONFIG, normalizeViewConfig } from './viewPreference'

const STORAGE_KEY = 'linear-lite-project-board-v2'

/** 处理人筛选项 */
export type AssigneeFilterItem = string

export interface TaskFilterSnapshot {
  searchQuery: string
  /** 按状态多选筛选（OR） */
  filterStatusList: Status[]
  /** 按优先级多选筛选（OR） */
  filterPriorityList: Priority[]
  /** 按负责人多选筛选（OR） */
  filterAssigneeList: AssigneeFilterItem[]
  /** 按标签 id 多选筛选（OR） */
  filterLabelIds: number[]
}

export interface ProjectBoardSnapshot {
  filters: TaskFilterSnapshot
  view: ViewConfig
}

interface RootV1 {
  v: 1
  byProject: Record<string, ProjectBoardSnapshot>
}

function isStatus(v: unknown): v is Status {
  return (
    v === 'backlog' ||
    v === 'todo' ||
    v === 'in_progress' ||
    v === 'in_review' ||
    v === 'done' ||
    v === 'canceled' ||
    v === 'duplicate'
  )
}

function isPriority(v: unknown): v is Priority {
  return v === 'low' || v === 'medium' || v === 'high' || v === 'urgent'
}

function parseStatusList(raw: unknown): Status[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(isStatus)
}

function parsePriorityList(raw: unknown): Priority[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(isPriority)
}

function parseAssigneeList(raw: unknown): AssigneeFilterItem[] {
  if (!Array.isArray(raw)) return []
  return raw.filter((item): item is string => typeof item === 'string' &&
    (item === 'unassigned' || /^(user|external):[1-9][0-9]*$/.test(item)))
}

function parseFilterLabelIds(raw: unknown): number[] {
  if (!Array.isArray(raw)) return []
  const out: number[] = []
  for (const x of raw) {
    if (typeof x === 'number' && Number.isFinite(x)) {
      out.push(x)
      continue
    }
    if (typeof x === 'string') {
      const n = Number(x)
      if (Number.isFinite(n)) out.push(n)
    }
  }
  return out
}

function parseFilters(raw: unknown): TaskFilterSnapshot {
  if (!raw || typeof raw !== 'object') {
    return {
      searchQuery: '',
      filterStatusList: [],
      filterPriorityList: [],
      filterAssigneeList: [],
      filterLabelIds: []
    }
  }
  const f = raw as Record<string, unknown>
  return {
    searchQuery: typeof f.searchQuery === 'string' ? f.searchQuery : '',
    filterStatusList: parseStatusList(f.filterStatusList),
    filterPriorityList: parsePriorityList(f.filterPriorityList),
    filterAssigneeList: parseAssigneeList(f.filterAssigneeList),
    filterLabelIds: parseFilterLabelIds(f.filterLabelIds)
  }
}

function readRoot(): RootV1 {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { v: 1, byProject: {} }
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object') return { v: 1, byProject: {} }
    const o = parsed as Partial<RootV1>
    if (o.v !== 1 || !o.byProject || typeof o.byProject !== 'object') return { v: 1, byProject: {} }
    return { v: 1, byProject: { ...o.byProject } }
  } catch {
    return { v: 1, byProject: {} }
  }
}

function writeRoot(root: RootV1) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(root))
  } catch {
    /* ignore quota */
  }
}

export function readProjectBoard(projectId: number): ProjectBoardSnapshot | null {
  const root = readRoot()
  const raw = root.byProject[String(projectId)]
  if (!raw || typeof raw !== 'object') return null
  const snap = raw as Partial<ProjectBoardSnapshot>
  return {
    filters: parseFilters(snap.filters),
    view: normalizeViewConfig(snap.view ?? DEFAULT_VIEW_CONFIG)
  }
}

export function writeProjectBoard(projectId: number, snapshot: ProjectBoardSnapshot) {
  const root = readRoot()
  root.byProject[String(projectId)] = {
    filters: { ...snapshot.filters },
    view: normalizeViewConfig(snapshot.view)
  }
  writeRoot(root)
}
