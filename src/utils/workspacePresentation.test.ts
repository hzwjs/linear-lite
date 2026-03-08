import { describe, expect, it } from 'vitest'
import { resolveWorkspacePresentation } from './workspacePresentation'

describe('resolveWorkspacePresentation', () => {
  it('keeps the primary canvas full width and opens issues as an overlay', () => {
    expect(resolveWorkspacePresentation('LLT-101')).toEqual({
      preservePrimaryCanvas: true,
      workspaceMode: 'overlay'
    })
  })

  it('returns no workspace mode when no issue is open', () => {
    expect(resolveWorkspacePresentation(null)).toEqual({
      preservePrimaryCanvas: true,
      workspaceMode: 'none'
    })
  })
})
