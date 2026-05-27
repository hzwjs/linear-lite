import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  normalizeMermaidRenderError,
  renderMermaidSvg,
  resetMermaidRendererForTests,
} from './mermaidRenderer'

describe('renderMermaidSvg', () => {
  beforeEach(() => {
    resetMermaidRendererForTests()
  })

  it('initializes Mermaid once and renders normalized source', async () => {
    const initialize = vi.fn()
    const render = vi.fn().mockResolvedValue({ svg: '<svg />' })
    const loadMermaid = vi.fn().mockResolvedValue({ initialize, render })

    const first = await renderMermaidSvg('```mermaid\ngraph TD\n  A-->B\n```', {
      id: 'm1',
      loadMermaid,
    })
    const second = await renderMermaidSvg('sequenceDiagram\nA->>B: hi', {
      id: 'm2',
      loadMermaid,
    })

    expect(first.svg).toBe('<svg />')
    expect(second.svg).toBe('<svg />')
    expect(initialize).toHaveBeenCalledTimes(1)
    expect(render).toHaveBeenNthCalledWith(1, 'm1', 'graph TD\n  A-->B')
    expect(render).toHaveBeenNthCalledWith(2, 'm2', 'sequenceDiagram\nA->>B: hi')
  })

  it('normalizes unknown render errors into user-facing messages', () => {
    expect(normalizeMermaidRenderError(new Error('Parse error on line 2'))).toBe(
      'Parse error on line 2'
    )
    expect(normalizeMermaidRenderError('broken')).toBe('broken')
    expect(normalizeMermaidRenderError({})).toBe('Mermaid diagram failed to render.')
  })
})
