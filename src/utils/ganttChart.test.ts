import { describe, expect, it } from 'vitest'
import type { Task } from '../types/domain'
import { DEFAULT_VIEW_CONFIG, type ViewConfig } from './viewPreference'
import { parseDateInputValue } from './taskDate'
import { dateRangeToTaskPatch, getGanttRows } from './ganttChart'

function baseTask(overrides: Partial<Task> & Pick<Task, 'id'>): Task {
  return {
    status: 'todo',
    priority: 'medium',
    createdAt: 1,
    updatedAt: 1,
    ...overrides,
    id: overrides.id,
    title: overrides.title ?? overrides.id
  }
}

function cfg(overrides: Partial<ViewConfig>): ViewConfig {
  return { ...DEFAULT_VIEW_CONFIG, ...overrides }
}

describe('getGanttRows', () => {
  it('showSubIssues false: only roots; undated use createdAt', () => {
    const undatedCreated = parseDateInputValue('2026-06-01')!
    const rows = getGanttRows(
      [
        baseTask({
          id: 'ENG-1',
          title: 'Top level dated',
          plannedStartDate: parseDateInputValue('2026-04-01'),
          dueDate: parseDateInputValue('2026-04-03'),
          updatedAt: 3
        }),
        baseTask({
          id: 'ENG-2',
          title: 'Child dated',
          parentId: '101',
          numericId: 102,
          plannedStartDate: parseDateInputValue('2026-04-02'),
          dueDate: parseDateInputValue('2026-04-04')
        }),
        baseTask({
          id: 'ENG-3',
          title: 'Top level without dates',
          createdAt: undatedCreated,
          updatedAt: 2
        })
      ],
      cfg({ showSubIssues: false }),
      false
    )

    expect(rows).toEqual([
      {
        id: 'ENG-1',
        name: 'Top level dated',
        start: '2026-04-01',
        end: '2026-04-03',
        progress: 0
      },
      {
        id: 'ENG-3',
        name: 'Top level without dates',
        start: '2026-06-01',
        end: '2026-06-01',
        progress: 0
      }
    ])
  })

  it('showSubIssues true: children after parent with indent; match parentId to parent numericId', () => {
    const rows = getGanttRows(
      [
        baseTask({
          id: 'ENG-1',
          title: 'Parent',
          numericId: 101,
          plannedStartDate: parseDateInputValue('2026-04-01'),
          dueDate: parseDateInputValue('2026-04-03'),
          updatedAt: 5
        }),
        baseTask({
          id: 'ENG-2',
          title: 'Child',
          parentId: '101',
          numericId: 102,
          plannedStartDate: parseDateInputValue('2026-04-02'),
          dueDate: parseDateInputValue('2026-04-04'),
          updatedAt: 4
        })
      ],
      cfg({ showSubIssues: true, nestedSubIssues: true }),
      false
    )

    expect(rows).toEqual([
      {
        id: 'ENG-1',
        name: 'Parent',
        start: '2026-04-01',
        end: '2026-04-03',
        progress: 0
      },
      {
        id: 'ENG-2',
        name: '  Child',
        start: '2026-04-02',
        end: '2026-04-04',
        progress: 0,
        dependencies: 'ENG-1'
      }
    ])
  })

  it('flatRoots: all tasks sorted flat, no nesting', () => {
    const rows = getGanttRows(
      [
        baseTask({
          id: 'ENG-1',
          title: 'Root',
          numericId: 101,
          plannedStartDate: parseDateInputValue('2026-04-01'),
          dueDate: parseDateInputValue('2026-04-02'),
          updatedAt: 1
        }),
        baseTask({
          id: 'ENG-2',
          title: 'Child',
          parentId: '101',
          plannedStartDate: parseDateInputValue('2026-04-03'),
          dueDate: parseDateInputValue('2026-04-04'),
          updatedAt: 9
        })
      ],
      cfg({ showSubIssues: true }),
      true
    )

    expect(rows.map((r) => r.id)).toEqual(['ENG-2', 'ENG-1'])
    expect(rows.every((r) => !r.name.startsWith('  '))).toBe(true)
  })
})

describe('dateRangeToTaskPatch', () => {
  it('maps Frappe drag dates to inclusive local calendar days', () => {
    const patch = dateRangeToTaskPatch(
      new Date('2026-04-01T00:00:00'),
      new Date('2026-04-03T23:59:59')
    )

    expect(patch).toEqual({
      plannedStartDate: parseDateInputValue('2026-04-01'),
      dueDate: parseDateInputValue('2026-04-03')
    })
  })
})
