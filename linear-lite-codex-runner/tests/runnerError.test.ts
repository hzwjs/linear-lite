import { describe, expect, it } from 'vitest'
import { runnerErrorCode } from '../src/runnerError.js'

describe('runner error code', () => {
  it('keeps only a stable code from a long driver error', () => {
    expect(runnerErrorCode(new Error(`CODEX_DESKTOP_LAUNCH_FAILED: ${'x'.repeat(8_192)}`))).toBe('CODEX_DESKTOP_LAUNCH_FAILED')
  })

  it('uses the fixed generic code for transport failures', () => {
    expect(runnerErrorCode(new Error('fetch failed'))).toBe('RUNNER_EXECUTION_FAILED')
  })
})
