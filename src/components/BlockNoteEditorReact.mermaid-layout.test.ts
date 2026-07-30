import { describe, expect, it } from 'vitest'
import { syncMermaidPreviewHeight } from './BlockNoteEditorReact'
import blockNoteReactSource from './BlockNoteEditorReact.tsx?raw'
import blockNoteWrapperSource from './BlockNoteEditorWrapper.vue?raw'

function createMermaidLayoutFixture() {
  const root = document.createElement('div')
  root.innerHTML = `
    <div class="bn-block-outer" data-id="first">
      <div class="bn-block-content" data-content-type="codeBlock" data-language="mermaid"></div>
    </div>
    <div class="bn-block-outer" data-id="second">
      <div class="bn-block-content" data-content-type="codeBlock" data-language="mermaid"></div>
    </div>
  `
  const preview = document.createElement('button')
  preview.dataset.blockId = 'first'
  preview.getBoundingClientRect = () => ({ height: 248 } as DOMRect)
  const layer = document.createElement('div')
  layer.appendChild(preview)
  root.appendChild(layer)
  return { root, preview, layer }
}

describe('BlockNote Mermaid layout stability', () => {
  it('keeps the placeholder height stable while Mermaid is rendering', () => {
    const { root, preview, layer } = createMermaidLayoutFixture()
    preview.dataset.renderState = 'rendering'

    syncMermaidPreviewHeight(root, preview)

    expect(layer.querySelector('.bn-mermaid-layout-styles')).toBeNull()
  })

  it('commits the resolved height to only the matching Mermaid block', () => {
    const { root, preview, layer } = createMermaidLayoutFixture()
    preview.dataset.renderState = 'resolved'

    syncMermaidPreviewHeight(root, preview)

    const layoutCss = layer.querySelector('.bn-mermaid-layout-styles')?.textContent ?? ''
    expect(layoutCss).toContain('[data-id="first"]')
    expect(layoutCss).toContain('--bn-mermaid-preview-height: 248px')
    expect(layoutCss).not.toContain('[data-id="second"]')
    expect(root.style.getPropertyValue('--bn-mermaid-preview-height')).toBe('')
  })

  it('excludes Mermaid blocks from browser scroll anchoring', () => {
    expect(blockNoteWrapperSource).toMatch(/\.bn-block-outer:has\([^}]+overflow-anchor:\s*none;/s)
  })

  it('rehydrates when BlockNote exposes Mermaid through data attributes', () => {
    expect(blockNoteReactSource).toContain("attributeFilter: ['data-id', 'data-content-type', 'data-language']")
    expect(blockNoteReactSource).not.toContain('window.setInterval')
    expect(blockNoteReactSource).not.toContain("classList.add('bn-mermaid-block')")
    expect(blockNoteReactSource).not.toContain('[DEBUG-scroll-anchor]')
  })
})
