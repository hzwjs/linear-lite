import { describe, expect, it } from 'vitest'
import componentSource from './TaskImageNodeView.vue?raw'

describe('TaskImageNodeView styles', () => {
  it('renders the upload status as a compact floating pill instead of a full-width strip', () => {
    expect(componentSource).toContain(":class=\"{ 'has-status': isUploading || isFailed, failed: isFailed }\"")
    expect(componentSource).toContain('overflow: hidden;')
    expect(componentSource).toContain('inset: 0;')
    expect(componentSource).toContain('linear-gradient(')
    expect(componentSource).toContain('width: fit-content;')
    expect(componentSource).toContain('max-width: calc(100% - 12px);')
    expect(componentSource).toContain('left: 6px;')
    expect(componentSource).toContain('bottom: 10px;')
    expect(componentSource).toContain('justify-content: flex-start;')
    expect(componentSource).toContain('border-radius: 999px;')
    expect(componentSource).toContain('box-shadow: 0 6px 18px rgba(15, 23, 42, 0.18);')
    expect(componentSource).not.toContain('right: 8px;')
  })
})
