import { describe, expect, it } from 'vitest'
import { resolveWorkspacePresentation } from './workspacePresentation'

describe('resolveWorkspacePresentation', () => {
  it('returns inline workspace mode when an issue is open', () => {
    expect(resolveWorkspacePresentation('LLT-101')).toEqual({
      preservePrimaryCanvas: true,
      workspaceMode: 'inline'
    })
  })

  it('returns no workspace mode when no issue is open', () => {
    expect(resolveWorkspacePresentation(null)).toEqual({
      preservePrimaryCanvas: true,
      workspaceMode: 'none'
    })
  })
})
