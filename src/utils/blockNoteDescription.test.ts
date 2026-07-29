import { describe, expect, it } from 'vitest'
import { blockNoteDocHasPersistableContent, parseBlockNoteStoredBlocks } from './blockNoteDescription'

describe('blockNoteDocHasPersistableContent', () => {
  it('lets BlockNote create its default paragraph for an empty stored document', () => {
    expect(parseBlockNoteStoredBlocks('[]')).toEqual([])
  })

  it('treats image-only BlockNote JSON as non-empty', () => {
    const raw = JSON.stringify([
      {
        id: 'b1',
        type: 'image',
        props: { url: 'https://example.com/a.png', caption: '', previewWidth: 320 },
      },
    ])
    const doc = parseBlockNoteStoredBlocks(raw)
    expect(doc).toBeDefined()
    expect(blockNoteDocHasPersistableContent(doc!)).toBe(true)
  })

  it('treats empty paragraph-only doc as empty', () => {
    const raw = JSON.stringify([{ id: 'p1', type: 'paragraph', content: [] }])
    const doc = parseBlockNoteStoredBlocks(raw)
    expect(doc).toBeDefined()
    expect(blockNoteDocHasPersistableContent(doc!)).toBe(false)
  })

  it('parseBlockNoteStoredBlocks keeps image before trailing empty paragraph', () => {
    const raw = JSON.stringify([
      {
        id: 'p1',
        type: 'paragraph',
        props: { backgroundColor: 'default', textColor: 'default', textAlignment: 'left' },
        content: [{ type: 'text', text: 'hi', styles: {} }],
        children: [],
      },
      {
        id: 'im',
        type: 'image',
        props: {
          url: 'https://example.com/a.png',
          caption: '',
          showPreview: true,
          textAlignment: 'left',
          backgroundColor: 'default',
          name: '',
        },
        children: [],
      },
      {
        id: 'p2',
        type: 'paragraph',
        props: { backgroundColor: 'default', textColor: 'default', textAlignment: 'left' },
        content: [],
        children: [],
      },
    ])
    const doc = parseBlockNoteStoredBlocks(raw)
    expect(doc).toBeDefined()
    expect((doc as { type: string }[]).map((b) => b.type)).toEqual(['paragraph', 'image'])
  })

  it('detects nested image in column/table children', () => {
    const raw = JSON.stringify([
      {
        id: 'outer',
        type: 'paragraph',
        content: [],
        children: [
          { id: 'img1', type: 'image', props: { url: 'https://x/blob', caption: '' } },
        ],
      },
    ])
    const doc = parseBlockNoteStoredBlocks(raw)
    expect(doc).toBeDefined()
    expect(blockNoteDocHasPersistableContent(doc!)).toBe(true)
  })

  it('preserves table content objects and treats table structure as persistable', () => {
    const raw = JSON.stringify([
      {
        id: 'tbl',
        type: 'table',
        props: { backgroundColor: 'default', textColor: 'default' },
        content: {
          type: 'tableContent',
          rows: [
            { cells: ['A1', 'B1'] },
            { cells: ['A2', 'B2'] },
          ],
        },
        children: [],
      },
    ])
    const doc = parseBlockNoteStoredBlocks(raw) as Array<{
      type: string
      content: { rows: Array<{ cells: string[] }> }
    }>

    expect(doc).toBeDefined()
    expect(doc[0]?.type).toBe('table')
    expect(doc[0]?.content.rows[0]?.cells).toEqual(['A1', 'B1'])
    expect(blockNoteDocHasPersistableContent(doc)).toBe(true)
  })
})
