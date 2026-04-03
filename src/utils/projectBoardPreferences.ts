import type { Status, Priority } from '../types/domain'
import type { ViewConfig } from './viewPreference'
import { DEFAULT_VIEW_CONFIG, normalizeViewConfig } from './viewPreference'

const STORAGE_KEY = 'linear-lite-project-board-v1'

export interface TaskFilterSnapshot {
  searchQuery: string
  filterStatus: Status | null
  filterPriority: Priority | null
  filterAssignee: null | 'unassigned' | number
  filterAssigneeUsernameNorm: string | null
  /** 按标签 id 多选筛选（OR），持久化 */
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

function parseAssigneeFilter(v: unknown): TaskFilterSnapshot['filterAssignee'] {
  if (v === 'unassigned') return 'unassigned'
  if (v === null || v === undefined) return null
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string') {
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }
  return null
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
      filterStatus: null,
      filterPriority: null,
      filterAssignee: null,
      filterAssigneeUsernameNorm: null,
      filterLabelIds: []
    }
  }
  const f = raw as Partial<TaskFilterSnapshot>
  return {
    searchQuery: typeof f.searchQuery === 'string' ? f.searchQuery : '',
    filterStatus: isStatus(f.filterStatus) ? f.filterStatus : null,
    filterPriority: isPriority(f.filterPriority) ? f.filterPriority : null,
    filterAssignee: parseAssigneeFilter(f.filterAssignee),
    filterAssigneeUsernameNorm:
      typeof f.filterAssigneeUsernameNorm === 'string' ? f.filterAssigneeUsernameNorm : null,
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
