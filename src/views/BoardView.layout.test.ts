import { describe, expect, it } from 'vitest'
import boardViewSource from './BoardView.vue?raw'

describe('BoardView toolbar layout', () => {
  it('only renders the toolbar outside task detail routes', () => {
    expect(boardViewSource).toMatch(
      /<div\s+v-if="!isEditorOpen"\s+class="board-toolbar board-toolbar--single-row"[\s\S]*?>/
    )
  })

  it('keeps every task view in one toolbar row', () => {
    expect(boardViewSource).toContain('class="board-toolbar board-toolbar--single-row"')
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

  it('reserves space for the active filter badge instead of letting it overflow into view tabs', () => {
    expect(boardViewSource).toMatch(
      /\.toolbar-options \.command-btn-filter\.has-active-filters\s*\{[\s\S]*?width:\s*auto;[\s\S]*?padding-inline:\s*8px;/
    )
  })
})
