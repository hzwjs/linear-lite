import { describe, expect, it } from 'vitest'
import { blockNoteDocHasPersistableContent, parseBlockNoteStoredBlocks } from './blockNoteDescription'

describe('blockNoteDocHasPersistableContent', () => {
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
})
