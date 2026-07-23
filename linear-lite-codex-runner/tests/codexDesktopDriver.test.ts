import { describe, expect, it } from 'vitest'
import { commandFailureDetails } from '../src/codexDesktopDriver.js'

describe('Codex 桌面 Driver 错误展示', () => {
  it('优先返回 Driver 的稳定错误码', () => {
    const error = Object.assign(new Error('Command failed: driver launch <large-payload>'), { stderr: 'CODEX_ACCESSIBILITY_PERMISSION_REQUIRED\n' })

    expect(commandFailureDetails(error)).toBe('CODEX_ACCESSIBILITY_PERMISSION_REQUIRED')
  })

  it('从没有 stderr 的命令错误中提取稳定错误码', () => {
    expect(commandFailureDetails(new Error('Command failed: driver launch payload CODEX_DESKTOP_COMPOSER_NOT_FOUND'))).toBe('CODEX_DESKTOP_COMPOSER_NOT_FOUND')
  })
})
