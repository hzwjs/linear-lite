import { describe, expect, it } from 'vitest'
import type { User } from '../types/domain'
import {
  autoMapTaskImportColumns,
  buildTaskImportPreview,
  getTaskImportTemplateCsv,
  getTaskImportFileKind,
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
    importId: 'Row ID',
    parentImportId: 'Parent Row ID'
  }
}

describe('taskImport helpers', () => {
  it('detects supported file types from file name', () => {
    expect(getTaskImportFileKind('tasks.csv')).toBe('csv')
    expect(getTaskImportFileKind('tasks.XLSX')).toBe('xlsx')
    expect(getTaskImportFileKind('tasks.txt')).toBe(null)
  })

  it('builds a downloadable csv template with sample parent-child rows', () => {
    expect(getTaskImportTemplateCsv()).toBe(
      [
        'title,importId,parentImportId,description,status,priority,assignee,dueDate',
        'Project kickoff,T-1,,Top-level issue,todo,high,alice,2026-03-20',
        'Write checklist,T-2,T-1,Child issue,in_progress,medium,bob,2026-03-21'
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
      'Row ID',
      'Parent Row ID'
    ])

    expect(mapping).toEqual(fullMapping())
    expect(TASK_IMPORT_FIELD_ALIASES.title).toContain('task name')
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
      'Priority must be one of: low, medium, high, urgent.',
      'Assignee must match an existing username in this workspace.',
      'Due date must use YYYY-MM-DD.'
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
        dueDate: '2026-03-20T00:00:00'
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
        dueDate: null
      }
    ])
  })
})
