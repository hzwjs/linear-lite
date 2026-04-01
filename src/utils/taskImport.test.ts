import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import type { User } from '../types/domain'
import {
  autoMapTaskImportColumns,
  buildTaskImportPreview,
  getTaskImportTemplateCsv,
  getTaskImportFileKind,
  parseTaskImportFile,
  TASK_IMPORT_FIELD_ALIASES,
  type TaskImportColumnMapping
} from './taskImport'

const users: User[] = [
  { id: 1, username: 'alice' },
  { id: 2, username: 'bob' }
]

function fullMapping(): TaskImportColumnMapping {
  return {
    title: 'Task Name',
    description: 'Details',
    status: 'State',
    priority: 'Severity',
    assignee: 'Owner',
    dueDate: 'Due Date',
    plannedStartDate: 'Planned Start',
    progressPercent: 'Progress',
    importId: 'Row ID',
    parentImportId: 'Parent Row ID'
  }
}

describe('taskImport helpers', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('detects supported file types from file name', () => {
    expect(getTaskImportFileKind('tasks.csv')).toBe('csv')
    expect(getTaskImportFileKind('tasks.XLSX')).toBe('xlsx')
    expect(getTaskImportFileKind('tasks.txt')).toBe(null)
  })

  it('parses UTF-8 CSV text without mojibake', async () => {
    const csv = 'title,importId\n系统管理,T-1\n'
    const parsed = await parseTaskImportFile(new File([csv], 'tasks.csv', { type: 'text/csv' }))
    expect(parsed.rows[0]?.title).toBe('系统管理')
    expect(parsed.rows[0]?.importId).toBe('T-1')
  })

  it('strips UTF-8 BOM from CSV so headers map correctly', async () => {
    const bom = new Uint8Array([0xef, 0xbb, 0xbf])
    const rest = new TextEncoder().encode('title,importId\nx,T-1\n')
    const bytes = new Uint8Array(bom.length + rest.length)
    bytes.set(bom, 0)
    bytes.set(rest, bom.length)
    const parsed = await parseTaskImportFile(new File([bytes], 'b.csv', { type: 'text/csv' }))
    expect(parsed.headers[0]).toBe('title')
    expect(parsed.rows[0]?.title).toBe('x')
  })

  it('builds a downloadable csv template with sample parent-child rows', () => {
    expect(getTaskImportTemplateCsv()).toBe(
      [
        'title,importId,parentImportId,description,status,priority,assignee,plannedStartDate,dueDate,progressPercent',
        'Project kickoff,T-1,,Top-level issue,todo,high,alice,2026-03-18,2026-03-20,0',
        'Write checklist,T-2,T-1,Child issue,in_progress,medium,bob,,2026-03-21,40'
      ].join('\n')
    )
  })

  it('auto-maps known header aliases', () => {
    const mapping = autoMapTaskImportColumns([
      'Task Name',
      'Details',
      'State',
      'Severity',
      'Owner',
      'Due Date',
      'Planned Start',
      'Progress',
      'Row ID',
      'Parent Row ID'
    ])

    expect(mapping).toEqual(fullMapping())
    expect(TASK_IMPORT_FIELD_ALIASES.title).toContain('task name')
  })

  it('auto-maps official template camelCase headers', () => {
    const headerLine = getTaskImportTemplateCsv().split('\n')[0] ?? ''
    const headers = headerLine.split(',')
    const mapping = autoMapTaskImportColumns(headers)

    expect(mapping.title).toBe('title')
    expect(mapping.importId).toBe('importId')
    expect(mapping.parentImportId).toBe('parentImportId')
    expect(mapping.description).toBe('description')
    expect(mapping.status).toBe('status')
    expect(mapping.priority).toBe('priority')
    expect(mapping.assignee).toBe('assignee')
    expect(mapping.plannedStartDate).toBe('plannedStartDate')
    expect(mapping.dueDate).toBe('dueDate')
    expect(mapping.progressPercent).toBe('progressPercent')
  })

  it('rejects files with more than 800 rows', () => {
    const rows = Array.from({ length: 801 }, (_, index) => ({
      'Task Name': `Task ${index + 1}`,
      'Row ID': `ID-${index + 1}`
    }))

    const preview = buildTaskImportPreview(rows, {
      mapping: {
        title: 'Task Name',
        importId: 'Row ID'
      },
      users
    })

    expect(preview.fileErrors).toEqual(['Import supports up to 800 rows per file.'])
  })

  it('reports row validation errors for required fields and invalid values', () => {
    const preview = buildTaskImportPreview(
      [
        {
          'Task Name': '',
          Details: 'Missing title',
          State: 'todo',
          Severity: 'medium',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Row ID': 'A-1',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Bad status',
          Details: '',
          State: 'waiting',
          Severity: 'medium',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Row ID': 'A-2',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Bad priority',
          Details: '',
          State: 'todo',
          Severity: 'highest',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Row ID': 'A-3',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Bad assignee',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: 'carol',
          'Due Date': '2026-03-20',
          'Row ID': 'A-4',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Bad due date',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: 'alice',
          'Due Date': '03/20/2026',
          'Row ID': 'A-5',
          'Parent Row ID': ''
        }
      ],
      {
        mapping: fullMapping(),
        users
      }
    )

    expect(preview.rows).toHaveLength(0)
    expect(preview.rowErrors.map((error) => error.message)).toEqual([
      'Title is required.',
      'Status must be one of: backlog, todo, in_progress, in_review, done, canceled, duplicate.',
      'Priority must be one of: low, medium, high, urgent.'
    ])
  })

  it('rejects duplicate import ids and missing parent references', () => {
    const preview = buildTaskImportPreview(
      [
        {
          'Task Name': 'Parent',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Row ID': 'A-1',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Duplicate id',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Row ID': 'A-1',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Missing parent',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: 'bob',
          'Due Date': '2026-03-20',
          'Row ID': 'A-3',
          'Parent Row ID': 'A-9'
        }
      ],
      {
        mapping: fullMapping(),
        users
      }
    )

    expect(preview.rows).toHaveLength(0)
    expect(preview.rowErrors.map((error) => `${error.lineNumber}:${error.message}`)).toEqual([
      '3:Import ID must be unique within the file.',
      '4:Parent Import ID must reference another row in the same file.'
    ])
  })

  it('builds normalized rows and summary for a valid parent-child import', () => {
    const preview = buildTaskImportPreview(
      [
        {
          'Task Name': 'Parent',
          Details: 'Parent description',
          State: 'todo',
          Severity: 'high',
          Owner: 'alice',
          'Due Date': '2026-03-20',
          'Planned Start': '2026-03-18',
          Progress: '0',
          'Row ID': 'A-1',
          'Parent Row ID': ''
        },
        {
          'Task Name': 'Child',
          Details: '',
          State: 'in_progress',
          Severity: 'medium',
          Owner: 'bob',
          'Due Date': '',
          'Planned Start': '',
          Progress: '40',
          'Row ID': 'A-2',
          'Parent Row ID': 'A-1'
        }
      ],
      {
        mapping: fullMapping(),
        users
      }
    )

    expect(preview.fileErrors).toEqual([])
    expect(preview.rowErrors).toEqual([])
    expect(preview.summary).toEqual({
      totalCount: 2,
      parentCount: 1,
      subtaskCount: 1
    })
    expect(preview.rows).toEqual([
      {
        lineNumber: 2,
        importId: 'A-1',
        parentImportId: null,
        title: 'Parent',
        description: 'Parent description',
        status: 'todo',
        priority: 'high',
        assigneeId: 1,
        assigneeDisplayName: null,
        dueDate: '2026-03-20T00:00:00',
        plannedStartDate: '2026-03-18T00:00:00',
        progressPercent: 0
      },
      {
        lineNumber: 3,
        importId: 'A-2',
        parentImportId: 'A-1',
        title: 'Child',
        description: '',
        status: 'in_progress',
        priority: 'medium',
        assigneeId: 2,
        assigneeDisplayName: null,
        dueDate: null,
        plannedStartDate: null,
        progressPercent: 40
      }
    ])
  })

  it('sets assigneeDisplayName when owner is not a system user', () => {
    const preview = buildTaskImportPreview(
      [
        {
          'Task Name': 'Task ext',
          Details: '',
          State: 'todo',
          Severity: 'medium',
          Owner: '外部姓名',
          'Due Date': '2026-03-20',
          'Row ID': 'E-1',
          'Parent Row ID': ''
        }
      ],
      {
        mapping: fullMapping(),
        users
      }
    )

    expect(preview.rowErrors).toEqual([])
    expect(preview.rows).toHaveLength(1)
    expect(preview.rows[0]?.assigneeId).toBeNull()
    expect(preview.rows[0]?.assigneeDisplayName).toBe('外部姓名')
  })
})
