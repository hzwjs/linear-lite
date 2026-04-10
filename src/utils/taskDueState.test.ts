import { describe, expect, it } from 'vitest'
import { getTaskDueState } from './taskDueState'

function localDate(year: number, month: number, day: number, hour = 12, minute = 0): Date {
  return new Date(year, month - 1, day, hour, minute, 0, 0)
}

describe('getTaskDueState', () => {
  it('returns no_due_date when due date is missing', () => {
    expect(getTaskDueState(undefined, localDate(2026, 4, 10))).toEqual({
      kind: 'no_due_date',
      dayCount: 0,
      isOverdue: false,
      isToday: false,
      isUpcoming: false,
      hasDueDate: false
    })
  })

  it('returns overdue when the due date is before today in local calendar days', () => {
    expect(getTaskDueState(localDate(2026, 4, 9).getTime(), localDate(2026, 4, 10, 23, 45))).toEqual({
      kind: 'overdue',
      dayCount: 1,
      isOverdue: true,
      isToday: false,
      isUpcoming: false,
      hasDueDate: true
    })
  })

  it('returns today even when the due date time differs from now', () => {
    expect(getTaskDueState(localDate(2026, 4, 10, 0, 1).getTime(), localDate(2026, 4, 10, 23, 59))).toEqual({
      kind: 'today',
      dayCount: 0,
      isOverdue: false,
      isToday: true,
      isUpcoming: false,
      hasDueDate: true
    })
  })

  it('returns upcoming when the due date is after today in local calendar days', () => {
    expect(getTaskDueState(localDate(2026, 4, 12).getTime(), localDate(2026, 4, 10, 1, 15))).toEqual({
      kind: 'upcoming',
      dayCount: 2,
      isOverdue: false,
      isToday: false,
      isUpcoming: true,
      hasDueDate: true
    })
  })

  it('treats out-of-range timestamps as no due date instead of producing NaN state', () => {
    expect(getTaskDueState(1e20, localDate(2026, 4, 10))).toEqual({
      kind: 'no_due_date',
      dayCount: 0,
      isOverdue: false,
      isToday: false,
      isUpcoming: false,
      hasDueDate: false
    })
  })
})
