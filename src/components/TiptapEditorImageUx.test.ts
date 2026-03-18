import { describe, expect, it } from 'vitest'
import tiptapEditorSource from './TiptapEditor.vue?raw'

describe('TiptapEditor image UX', () => {
  it('uses a skeleton component for remote image loading overlays', () => {
    expect(tiptapEditorSource).toContain("import { Skeleton } from '@brayamvalero/vue3-skeleton'")
    expect(tiptapEditorSource).toContain('<Skeleton')
    expect(tiptapEditorSource).toContain('loadingImageOverlays')
    expect(tiptapEditorSource).toContain('editor-image-skeleton-layer')
  })

  it('opens saved description images in a PhotoSwipe gallery', () => {
    expect(tiptapEditorSource).toContain("import PhotoSwipe from 'photoswipe'")
    expect(tiptapEditorSource).toContain('openImageGallery(')
    expect(tiptapEditorSource).toContain("showHideAnimationType: 'zoom'")
    expect(tiptapEditorSource).toContain("img:not(.ProseMirror-separator)")
  })
})
