import { describe, expect, it } from 'vitest'
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { BlockNoteEditor } from '@blocknote/core'
import BlockNoteEditorReact, { initializeNumberedListIndices } from './BlockNoteEditorReact'

function numberedBlocks(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: `numbered-${index + 1}`,
    type: 'numberedListItem' as const,
    content: `item ${index + 1}`,
    children: [],
  }))
}

describe('BlockNote numbered list rendering', () => {
  it('keeps numbered list block types and indices after initial mount', () => {
    const editor = BlockNoteEditor.create({ initialContent: numberedBlocks(8) })
    const host = document.createElement('div')
    editor.mount(host)

    initializeNumberedListIndices(editor)

    const items = Array.from(host.querySelectorAll<HTMLElement>('[data-content-type]'))
      .filter((element) => element.dataset.contentType === 'numberedListItem')

    expect(items).toHaveLength(8)
    expect(items.map((item) => item.dataset.index)).toEqual(
      ['1', '2', '3', '4', '5', '6', '7', '8'],
    )
  })

  it('does not emit the index initialization as a document edit', async () => {
    window.matchMedia = (() => ({
      matches: false,
      media: '',
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    })) as typeof window.matchMedia
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    const host = document.createElement('div')
    document.body.appendChild(host)
    const root = createRoot(host)
    const onChange = vi.fn()

    await act(async () => {
      root.render(createElement(BlockNoteEditorReact, {
        initialContent: JSON.stringify(numberedBlocks(8)),
        onChange,
      }))
    })

    expect(onChange).not.toHaveBeenCalled()

    await act(async () => {
      root.unmount()
    })
    host.remove()
  })
})
