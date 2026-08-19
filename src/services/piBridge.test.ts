import { describe, expect, it } from 'vitest'
import { resolvePiBridgeApiBaseUrl } from './piBridge'

describe('resolvePiBridgeApiBaseUrl', () => {
  it('uses the current page origin for a relative API base', () => {
    expect(resolvePiBridgeApiBaseUrl('/api', 'http://localhost:5173/projects/8/tasks/1'))
      .toBe('http://localhost:5173')
  })

  it('uses the configured remote origin and removes the API suffix', () => {
    expect(resolvePiBridgeApiBaseUrl('https://linear.example.com/api', 'http://localhost:5173/'))
      .toBe('https://linear.example.com')
  })

  it('preserves a configured deployment path before the API suffix', () => {
    expect(resolvePiBridgeApiBaseUrl('https://linear.example.com/linear-lite/api', 'http://localhost:5173/'))
      .toBe('https://linear.example.com/linear-lite')
  })
})
