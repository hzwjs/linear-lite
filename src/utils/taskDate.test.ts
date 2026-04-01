import { describe, expect, it } from 'vitest'
import {
  formatDateInputValue,
  parseDateInputValue,
  todayDateInputValue,
  toApiDateTime
} from './taskDate'

describe('taskDate helpers', () => {
  it('formats stored local-midnight timestamp without shifting calendar day', () => {
    const ms = new Date('2026-03-10T00:00:00+08:00').getTime()

    expect(formatDateInputValue(ms)).toBe('2026-03-10')
  })

  it('parses date input as local midnight', () => {
    const ms = parseDateInputValue('2026-03-10')

    expect(formatDateInputValue(ms)).toBe('2026-03-10')
  })

  it('serializes local-midnight timestamp without converting to UTC date', () => {
    const ms = parseDateInputValue('2026-03-10')

    expect(toApiDateTime(ms)).toBe('2026-03-10T00:00:00')
  })

  it('todayDateInputValue is local calendar today in YYYY-MM-DD', () => {
    expect(todayDateInputValue()).toBe(formatDateInputValue(Date.now()))
    expect(todayDateInputValue()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})
