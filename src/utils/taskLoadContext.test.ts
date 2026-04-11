import { describe, expect, it } from 'vitest'
import { captureTaskLoadContext, isTaskLoadStale } from './taskLoadContext'

describe('taskLoadContext', () => {
  it('detects stale when task id changed', () => {
    const snap = captureTaskLoadContext({ id: 'A', numericId: 1 })
    expect(isTaskLoadStale(snap, { id: 'B', numericId: 2 })).toBe(true)
  })

  it('not stale when same task', () => {
    const snap = captureTaskLoadContext({ id: 'A', numericId: 1 })
    expect(isTaskLoadStale(snap, { id: 'A', numericId: 1 })).toBe(false)
  })

  it('null snapshot is stale', () => {
    expect(isTaskLoadStale(null, { id: 'A', numericId: 1 })).toBe(true)
  })
})
