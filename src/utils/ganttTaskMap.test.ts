import { describe, expect, it } from 'vitest'
import type { Task } from '../types/domain'
import { parseDateInputValue } from './taskDate'
import { ganttDatesToTaskPatch, taskToGanttRow } from './ganttTaskMap'

function baseTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'ENG-1',
    title: 'Example',
    status: 'todo',
    priority: 'medium',
    createdAt: 0,
    updatedAt: 0,
    ...overrides
  }
}

describe('taskToGanttRow', () => {
  it('maps both planned start and due to Frappe day strings (inclusive end)', () => {
    const planned = parseDateInputValue('2026-03-01')!
    const due = parseDateInputValue('2026-03-05')!
    const row = taskToGanttRow(
      baseTask({ plannedStartDate: planned, dueDate: due, progressPercent: 40 })
    )

    expect(row).toEqual({
      id: 'ENG-1',
      name: 'Example',
      start: '2026-03-01',
      end: '2026-03-05',
      progress: 40
    })
  })

  it('returns null when neither planned nor due is set', () => {
    expect(taskToGanttRow(baseTask())).toBeNull()
    expect(
      taskToGanttRow(
        baseTask({ plannedStartDate: null, dueDate: null })
      )
    ).toBeNull()
  })

  it('only due: single-day bar (start === end)', () => {
    const due = parseDateInputValue('2026-06-15')!
    const row = taskToGanttRow(baseTask({ dueDate: due }))

    expect(row).not.toBeNull()
    expect(row!.start).toBe('2026-06-15')
    expect(row!.end).toBe('2026-06-15')
  })

  it('only planned start: single-day bar (start === end)', () => {
    const planned = parseDateInputValue('2026-07-20')!
    const row = taskToGanttRow(baseTask({ plannedStartDate: planned }))

    expect(row).not.toBeNull()
    expect(row!.start).toBe('2026-07-20')
    expect(row!.end).toBe('2026-07-20')
  })

  it('returns null when calendar start is after calendar due', () => {
    const row = taskToGanttRow(
      baseTask({
        plannedStartDate: parseDateInputValue('2026-04-10')!,
        dueDate: parseDateInputValue('2026-04-09')!
      })
    )
    expect(row).toBeNull()
  })

  it('clamps progress to 0–100', () => {
    expect(
      taskToGanttRow(
        baseTask({
          plannedStartDate: parseDateInputValue('2026-01-01')!,
          dueDate: parseDateInputValue('2026-01-02')!,
          progressPercent: -5
        })
      )!.progress
    ).toBe(0)
    expect(
      taskToGanttRow(
        baseTask({
          plannedStartDate: parseDateInputValue('2026-01-01')!,
          dueDate: parseDateInputValue('2026-01-02')!,
          progressPercent: 150
        })
      )!.progress
    ).toBe(100)
  })
})

describe('ganttDatesToTaskPatch', () => {
  it('maps inclusive Gantt end day to dueDate local midnight (Frappe semantics)', () => {
    const patch = ganttDatesToTaskPatch('2026-03-01', '2026-03-05')
    expect(patch.plannedStartDate).toBe(parseDateInputValue('2026-03-01'))
    expect(patch.dueDate).toBe(parseDateInputValue('2026-03-05'))
  })

  it('round-trips with taskToGanttRow for two-date tasks', () => {
    const task = baseTask({
      plannedStartDate: parseDateInputValue('2026-02-10')!,
      dueDate: parseDateInputValue('2026-02-14')!,
      progressPercent: 25
    })
    const row = taskToGanttRow(task)!
    const patch = ganttDatesToTaskPatch(row.start, row.end)
    expect(patch.plannedStartDate).toBe(task.plannedStartDate)
    expect(patch.dueDate).toBe(task.dueDate)
  })

  it('round-trips for single-day row', () => {
    const row = taskToGanttRow(
      baseTask({ dueDate: parseDateInputValue('2026-08-01')! })
    )!
    const patch = ganttDatesToTaskPatch(row.start, row.end)
    expect(patch.plannedStartDate).toBe(patch.dueDate)
    expect(patch.dueDate).toBe(parseDateInputValue('2026-08-01'))
  })
})
