import { describe, expect, it } from 'vitest'
import boardViewSource from './BoardView.vue?raw'

describe('BoardView toolbar layout', () => {
  it('only renders the toolbar outside task detail routes', () => {
    expect(boardViewSource).toMatch(
      /<div\s+v-if="!isEditorOpen"\s+class="board-toolbar"[\s\S]*?>/
    )
  })

  it('merges list controls into one toolbar row', () => {
    expect(boardViewSource).toContain("'board-toolbar--list': viewType === 'list'")
    expect(boardViewSource).toContain('grid-template-areas: "scope actions spacer search options view"')
    expect(boardViewSource).toContain('class="toolbar-actions"')
    expect(boardViewSource).toContain('class="toolbar-scope"')
    expect(boardViewSource).toContain('class="toolbar-search"')
    expect(boardViewSource).toContain('class="toolbar-options"')
  })

  it('uses the approved list-first layout without duplicate create/import controls', () => {
    expect(boardViewSource.indexOf('class="toolbar-scope"')).toBeLessThan(
      boardViewSource.indexOf('class="toolbar-actions"')
    )
    expect(boardViewSource).not.toContain('class="command-bar-add"')
    expect(boardViewSource).not.toContain('class="btn-import"')
    expect(boardViewSource).not.toContain('openImportModal')
  })
})
