import { describe, expect, it } from 'vitest'
import blockNoteReactSource from './BlockNoteEditorReact.tsx?raw'
import blockNoteWrapperSource from './BlockNoteEditorWrapper.vue?raw'

describe('BlockNote image preview', () => {
  it('opens description images with PhotoSwipe zoom controls', () => {
    expect(blockNoteReactSource).toContain("import PhotoSwipe from 'photoswipe'")
    expect(blockNoteWrapperSource).toContain("import 'photoswipe/style.css'")
    expect(blockNoteReactSource).toContain('collectImagePreviewItems')
    expect(blockNoteReactSource).toContain('openImagePreview')
    expect(blockNoteReactSource).toContain('wheelToZoom: true')
    expect(blockNoteReactSource).toContain('maxZoomLevel: 8')
    expect(blockNoteReactSource).toContain('bn-image-preview-button')
    expect(blockNoteReactSource).toContain('data-preview-index')
    expect(blockNoteReactSource).toContain('bn-image-preview-target')
    expect(blockNoteWrapperSource).toContain('opacity: 0.88;')
    expect(blockNoteWrapperSource).not.toContain('opacity: 0;')
  })

  it('adds PhotoSwipe preview for rendered Mermaid SVG diagrams', () => {
    expect(blockNoteReactSource).toContain('bn-mermaid-preview-zoom')
    expect(blockNoteReactSource).toContain('openMermaidPreview')
    expect(blockNoteReactSource).toContain('XMLSerializer')
    expect(blockNoteReactSource).toContain('image/svg+xml;charset=utf-8')
    expect(blockNoteReactSource).toContain('bn-mermaid-preview-background')
    expect(blockNoteReactSource).toContain("background.setAttribute('fill', '#fff')")
    expect(blockNoteWrapperSource).toContain('.bn-mermaid-preview-zoom')
  })
})
