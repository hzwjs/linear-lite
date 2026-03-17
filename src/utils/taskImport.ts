import type { Priority, Status, User } from '../types/domain'
import * as XLSX from 'xlsx'
import { translate } from './i18n'

export type TaskImportField =
  | 'title'
  | 'description'
  | 'status'
  | 'priority'
  | 'assignee'
  | 'dueDate'
  | 'importId'
  | 'parentImportId'

export type TaskImportColumnMapping = Partial<Record<TaskImportField, string>>

export interface TaskImportPreviewRow {
  lineNumber: number
  importId: string
  parentImportId: string | null
  title: string
  description: string
  status: Status
  priority: Priority
  assigneeId: number | null
  dueDate: string | null
}

export interface TaskImportPreviewError {
  lineNumber: number
  field: TaskImportField | 'row'
  message: string
}

export interface TaskImportPreviewSummary {
  totalCount: number
  parentCount: number
  subtaskCount: number
}

export interface TaskImportPreview {
  rows: TaskImportPreviewRow[]
  fileErrors: string[]
  rowErrors: TaskImportPreviewError[]
  summary: TaskImportPreviewSummary
}

export interface ParsedTaskImportFile {
  headers: string[]
  rows: Array<Record<string, unknown>>
}

export const TASK_IMPORT_MAX_ROWS = 800
export const TASK_IMPORT_REQUIRED_FIELDS: TaskImportField[] = ['title', 'importId']
export const TASK_IMPORT_OPTIONAL_FIELDS: TaskImportField[] = [
  'description',
  'status',
  'priority',
  'assignee',
  'dueDate',
  'parentImportId'
]
export const TASK_IMPORT_FIELD_LABELS: Record<TaskImportField, string> = {
  title: 'Title',
  description: 'Description',
  status: 'Status',
  priority: 'Priority',
  assignee: 'Assignee',
  dueDate: 'Due date',
  importId: 'Import ID',
  parentImportId: 'Parent Import ID'
}

export const TASK_IMPORT_FIELD_ALIASES: Record<TaskImportField, string[]> = {
  title: ['title', 'task title', 'task name', 'name', 'issue title'],
  description: ['description', 'details', 'body'],
  status: ['status', 'state'],
  priority: ['priority', 'severity'],
  assignee: ['assignee', 'owner', 'assigned to'],
  dueDate: ['due date', 'duedate', 'due'],
  importId: ['import id', 'row id', 'id'],
  parentImportId: ['parent import id', 'parent row id', 'parent id']
}

const STATUS_VALUES: Status[] = [
  'backlog',
  'todo',
  'in_progress',
  'in_review',
  'done',
  'canceled',
  'duplicate'
]

const PRIORITY_VALUES: Priority[] = ['low', 'medium', 'high', 'urgent']

export function getTaskImportFileKind(name: string): 'csv' | 'xlsx' | null {
  const normalized = name.trim().toLowerCase()
  if (normalized.endsWith('.csv')) return 'csv'
  if (normalized.endsWith('.xlsx')) return 'xlsx'
  return null
}

export function autoMapTaskImportColumns(headers: string[]): TaskImportColumnMapping {
  const mapping: TaskImportColumnMapping = {}
  const normalizedHeaders = headers.map((header) => ({
    original: header,
    normalized: normalizeHeader(header)
  }))

  for (const field of Object.keys(TASK_IMPORT_FIELD_ALIASES) as TaskImportField[]) {
    const match = normalizedHeaders.find((header) =>
      TASK_IMPORT_FIELD_ALIASES[field].includes(header.normalized)
    )
    if (match) mapping[field] = match.original
  }

  return mapping
}

export function getTaskImportTemplateCsv(): string {
  return [
    'title,importId,parentImportId,description,status,priority,assignee,dueDate',
    'Project kickoff,T-1,,Top-level issue,todo,high,alice,2026-03-20',
    'Write checklist,T-2,T-1,Child issue,in_progress,medium,bob,2026-03-21'
  ].join('\n')
}

export function buildTaskImportPreview(
  rawRows: Array<Record<string, unknown>>,
  options: {
    mapping: TaskImportColumnMapping
    users: User[]
  }
): TaskImportPreview {
  const fileErrors: string[] = []
  const rowErrors: TaskImportPreviewError[] = []
  const rows: TaskImportPreviewRow[] = []

  if (rawRows.length > TASK_IMPORT_MAX_ROWS) {
    fileErrors.push(
      translate(
        'taskImport.errors.maxRows',
        { count: TASK_IMPORT_MAX_ROWS },
        `Import supports up to ${TASK_IMPORT_MAX_ROWS} rows per file.`
      )
    )
  }

  if (!options.mapping.title) {
    fileErrors.push(
      translate('taskImport.errors.titleColumnRequired', undefined, 'Title column is required.')
    )
  }
  if (!options.mapping.importId) {
    fileErrors.push(
      translate('taskImport.errors.importIdColumnRequired', undefined, 'Import ID column is required.')
    )
  }

  if (fileErrors.length > 0) {
    return {
      rows: [],
      fileErrors,
      rowErrors,
      summary: emptySummary()
    }
  }

  const userIdByUsername = new Map(options.users.map((user) => [user.username.toLowerCase(), user.id]))
  const importIds = new Map<string, number>()

  for (const [index, rawRow] of rawRows.entries()) {
    const lineNumber = index + 2
    const title = getMappedValue(rawRow, options.mapping.title).trim()
    const description = getMappedValue(rawRow, options.mapping.description).trim()
    const statusInput = getMappedValue(rawRow, options.mapping.status).trim().toLowerCase()
    const priorityInput = getMappedValue(rawRow, options.mapping.priority).trim().toLowerCase()
    const assigneeInput = getMappedValue(rawRow, options.mapping.assignee).trim()
    const dueDateInput = getMappedValue(rawRow, options.mapping.dueDate).trim()
    const importId = getMappedValue(rawRow, options.mapping.importId).trim()
    const parentImportIdValue = getMappedValue(rawRow, options.mapping.parentImportId).trim()
    const parentImportId = parentImportIdValue || null

    if (!title) {
      rowErrors.push({
        lineNumber,
        field: 'title',
        message: translate('taskImport.errors.titleRequired', undefined, 'Title is required.')
      })
    }
    if (!importId) {
      rowErrors.push({
        lineNumber,
        field: 'importId',
        message: translate('taskImport.errors.importIdRequired', undefined, 'Import ID is required.')
      })
    } else if (importIds.has(importId)) {
      rowErrors.push({
        lineNumber,
        field: 'importId',
        message: translate(
          'taskImport.errors.importIdUnique',
          undefined,
          'Import ID must be unique within the file.'
        )
      })
    } else {
      importIds.set(importId, lineNumber)
    }

    const status = normalizeStatus(statusInput)
    if (statusInput && !status) {
      rowErrors.push({
        lineNumber,
        field: 'status',
        message: translate(
          'taskImport.errors.invalidStatus',
          { values: STATUS_VALUES.join(', ') },
          `Status must be one of: ${STATUS_VALUES.join(', ')}.`
        )
      })
    }

    const priority = normalizePriority(priorityInput)
    if (priorityInput && !priority) {
      rowErrors.push({
        lineNumber,
        field: 'priority',
        message: translate(
          'taskImport.errors.invalidPriority',
          { values: PRIORITY_VALUES.join(', ') },
          `Priority must be one of: ${PRIORITY_VALUES.join(', ')}.`
        )
      })
    }

    let assigneeId: number | null = null
    if (assigneeInput) {
      assigneeId = userIdByUsername.get(assigneeInput.toLowerCase()) ?? null
      // 未匹配到时不再报错，仅不分配负责人
    }

    let dueDate: string | null = null
    if (dueDateInput) {
      dueDate = normalizeDueDate(dueDateInput)
      if (!dueDate) {
        rowErrors.push({
          lineNumber,
          field: 'dueDate',
          message: translate('taskImport.errors.invalidDueDate', undefined, 'Due date must use YYYY-MM-DD.')
        })
      }
    }

    rows.push({
      lineNumber,
      importId,
      parentImportId,
      title,
      description,
      status: status ?? 'backlog',
      priority: priority ?? 'medium',
      assigneeId,
      dueDate
    })
  }

  for (const row of rows) {
    if (!row.parentImportId) continue
    if (row.parentImportId === row.importId) {
      rowErrors.push({
        lineNumber: row.lineNumber,
        field: 'parentImportId',
        message: translate(
          'taskImport.errors.parentSelfReference',
          undefined,
          'Parent Import ID cannot reference the same row.'
        )
      })
      continue
    }
    if (!importIds.has(row.parentImportId)) {
      rowErrors.push({
        lineNumber: row.lineNumber,
        field: 'parentImportId',
        message: translate(
          'taskImport.errors.parentMissing',
          undefined,
          'Parent Import ID must reference another row in the same file.'
        )
      })
    }
  }

  if (rowErrors.length > 0) {
    return {
      rows: [],
      fileErrors,
      rowErrors,
      summary: emptySummary()
    }
  }

  const summary = {
    totalCount: rows.length,
    parentCount: rows.filter((row) => row.parentImportId == null).length,
    subtaskCount: rows.filter((row) => row.parentImportId != null).length
  }

  return {
    rows,
    fileErrors,
    rowErrors,
    summary
  }
}

export async function parseTaskImportFile(file: File): Promise<ParsedTaskImportFile> {
  const kind = getTaskImportFileKind(file.name)
  if (!kind) {
    throw new Error(
      translate(
        'taskImport.errors.unsupportedFileType',
        undefined,
        'Only .csv and .xlsx files are supported.'
      )
    )
  }

  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
  const firstSheetName = workbook.SheetNames[0]
  if (!firstSheetName) {
    throw new Error(
      translate('taskImport.errors.noSheets', undefined, 'The file does not contain any sheets.')
    )
  }
  const sheet = workbook.Sheets[firstSheetName]
  if (!sheet) {
    throw new Error(
      translate('taskImport.errors.firstSheetUnreadable', undefined, 'The first sheet could not be read.')
    )
  }
  const matrix = XLSX.utils.sheet_to_json<(string | number | boolean | null)[]>(sheet, {
    header: 1,
    raw: false,
    defval: ''
  })
  const [headerRow, ...bodyRows] = matrix
  const headers = (headerRow ?? []).map((cell) => String(cell ?? '').trim())
  if (headers.filter(Boolean).length === 0) {
    throw new Error(
      translate('taskImport.errors.missingHeader', undefined, 'The file must include a header row.')
    )
  }
  const rows = bodyRows
    .filter((row) => row.some((cell) => String(cell ?? '').trim() !== ''))
    .map((row) => {
      const record: Record<string, unknown> = {}
      headers.forEach((header, index) => {
        if (!header) return
        record[header] = row[index] ?? ''
      })
      return record
    })

  return {
    headers,
    rows
  }
}

function normalizeHeader(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, ' ')
}

function getMappedValue(row: Record<string, unknown>, header?: string): string {
  if (!header) return ''
  const value = row[header]
  if (value == null) return ''
  return String(value)
}

function normalizeStatus(value: string): Status | null {
  if (!value) return null
  return STATUS_VALUES.find((status) => status === value) ?? null
}

function normalizePriority(value: string): Priority | null {
  if (!value) return null
  return PRIORITY_VALUES.find((priority) => priority === value) ?? null
}

function normalizeDueDate(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) return `${trimmed}T00:00:00`
  const isoMatch = trimmed.match(/^(\d{4}-\d{2}-\d{2})[T\s]/)
  if (isoMatch) return `${isoMatch[1]}T00:00:00`
  const excelSerial = /^\d+$/.test(trimmed) ? Number(trimmed) : NaN
  if (!Number.isNaN(excelSerial) && excelSerial > 0) {
    const date = new Date((excelSerial - 25569) * 86400 * 1000)
    if (!Number.isNaN(date.getTime())) {
      const y = date.getFullYear()
      const m = String(date.getMonth() + 1).padStart(2, '0')
      const d = String(date.getDate()).padStart(2, '0')
      return `${y}-${m}-${d}T00:00:00`
    }
  }
  const parsed = new Date(trimmed)
  if (!Number.isNaN(parsed.getTime())) {
    const y = parsed.getFullYear()
    const m = String(parsed.getMonth() + 1).padStart(2, '0')
    const d = String(parsed.getDate()).padStart(2, '0')
    return `${y}-${m}-${d}T00:00:00`
  }
  return null
}

function emptySummary(): TaskImportPreviewSummary {
  return {
    totalCount: 0,
    parentCount: 0,
    subtaskCount: 0
  }
}
