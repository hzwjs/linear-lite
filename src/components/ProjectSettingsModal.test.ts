import { describe, expect, it } from 'vitest'
import { shouldIgnoreProjectResponse } from '../utils/projectRequestGuard'

describe('shouldIgnoreProjectResponse', () => {
  it('returns false for the latest response of the active project', () => {
    expect(shouldIgnoreProjectResponse(2, 2, 10, 10)).toBe(false)
  })

  it('returns true when a newer request already exists', () => {
    expect(shouldIgnoreProjectResponse(1, 2, 10, 10)).toBe(true)
  })

  it('returns true when the active project has changed', () => {
    expect(shouldIgnoreProjectResponse(2, 2, 20, 10)).toBe(true)
  })
})
