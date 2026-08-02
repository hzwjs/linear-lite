import { describe, expect, it, vi, afterEach } from 'vitest'
import { copyTextToClipboard } from './clipboard'

function stubExecCommand(ok: boolean) {
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value: vi.fn(() => ok)
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  // @ts-expect-error 清理 execCommand stub，恢复 jsdom 默认（jsdom 未实现时为 undefined）
  delete document.execCommand
})

describe('copyTextToClipboard', () => {
  it('优先使用 Clipboard API 并返回 true', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    const ok = await copyTextToClipboard('HZW-1')
    expect(writeText).toHaveBeenCalledWith('HZW-1')
    expect(ok).toBe(true)
  })

  it('Clipboard API 不可用（非安全上下文）时降级为 textarea + execCommand', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined
    })
    const execCommand = vi.fn(() => true)
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand
    })
    const ok = await copyTextToClipboard('HZW-1')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(ok).toBe(true)
  })

  it('Clipboard API 拒绝权限时降级为 textarea + execCommand', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('NotAllowedError'))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    stubExecCommand(true)
    const ok = await copyTextToClipboard('HZW-1')
    expect(ok).toBe(true)
  })

  it('两条路径都失败时返回 false', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined
    })
    stubExecCommand(false)
    const ok = await copyTextToClipboard('HZW-1')
    expect(ok).toBe(false)
  })
})
