import { describe, expect, it } from 'vitest'
import taskEditorSource from './TaskEditor.vue?raw'

describe('task editor description fullscreen mode', () => {
  it('wires the description section toggle to the description fullscreen class', () => {
    expect(taskEditorSource).toContain("const isDescriptionFullscreen = ref(false)")
    expect(taskEditorSource).toContain("const descriptionSectionRef = ref<HTMLElement | null>(null)")
    expect(taskEditorSource).toContain("t('taskEditor.exitFullscreen')")
    expect(taskEditorSource).toContain("t('taskEditor.enterFullscreen')")
    expect(taskEditorSource).toContain('document.addEventListener(\'fullscreenchange\', syncDescriptionFullscreenState)')
    expect(taskEditorSource).toContain('document.removeEventListener(\'fullscreenchange\', syncDescriptionFullscreenState)')
    expect(taskEditorSource).toContain('await target.requestFullscreen()')
    expect(taskEditorSource).toContain('await document.exitFullscreen()')
    expect(taskEditorSource).toContain('ref="descriptionSectionRef"')
    expect(taskEditorSource).toContain("@click=\"toggleDescriptionFullscreen\"")
    expect(taskEditorSource).toContain("'description-section--fullscreen': isDescriptionFullscreen")
    expect(taskEditorSource).toContain("<Minimize2 v-if=\"isDescriptionFullscreen\"")
    expect(taskEditorSource).toContain("<Maximize2 v-else")
    expect(taskEditorSource).toContain(".description-section--fullscreen")
    expect(taskEditorSource).toContain(".description-section:fullscreen")
    expect(taskEditorSource).toContain("position: fixed;")
    expect(taskEditorSource).toContain(":min-height=\"isDescriptionFullscreen ? 520 : 96\"")
    expect(taskEditorSource).not.toContain("'editor-panel--fullscreen'")
  })
})
