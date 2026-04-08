import { describe, expect, it } from 'vitest'
import type { Task } from '../types/domain'
import { parseDateInputValue } from './taskDate'
import { dateRangeToTaskPatch, getTopLevelGanttRows } from './ganttChart'

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

describe('getTopLevelGanttRows', () => {
  it('maps top-level tasks; drops subtasks; undated use createdAt', () => {
    const undatedCreated = parseDateInputValue('2026-06-01')!
    const rows = getTopLevelGanttRows([
      baseTask({
        id: 'ENG-1',
        title: 'Top level dated',
        plannedStartDate: parseDateInputValue('2026-04-01'),
        dueDate: parseDateInputValue('2026-04-03')
      }),
      baseTask({
        id: 'ENG-2',
        title: 'Child dated',
        parentId: '101',
        plannedStartDate: parseDateInputValue('2026-04-02'),
        dueDate: parseDateInputValue('2026-04-04')
      }),
      baseTask({
        id: 'ENG-3',
        title: 'Top level without dates',
        createdAt: undatedCreated
      })
    ])

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
