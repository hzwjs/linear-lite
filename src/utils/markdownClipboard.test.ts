import { describe, expect, it } from 'vitest'
import { shouldPasteClipboardAsMarkdown } from './markdownClipboard'

describe('shouldPasteClipboardAsMarkdown', () => {
  it('recognizes raw markdown copied as plain text', () => {
    // A single heading followed by a paragraph is valid Markdown but is not
    // recognized by BlockNote 0.48's built-in clipboard detector.
    expect(shouldPasteClipboardAsMarkdown(['text/plain'], '# Title\nbody')).toBe(true)
  })

  it('recognizes an explicit markdown clipboard type', () => {
    expect(shouldPasteClipboardAsMarkdown(['text/markdown'], 'plain paragraph')).toBe(true)
    expect(shouldPasteClipboardAsMarkdown(['text/markdown', 'text/html'], 'plain paragraph')).toBe(true)
  })

  it('keeps rich HTML clipboard content on BlockNote default handling', () => {
    expect(shouldPasteClipboardAsMarkdown(['text/plain', 'text/html'], 'Title')).toBe(false)
  })

  it('parses Markdown copied from a BlockNote paragraph with internal HTML', () => {
    expect(
      shouldPasteClipboardAsMarkdown(
        ['blocknote/html', 'text/html', 'text/plain'],
        '# Title\n\n## Section\n\n- item',
      ),
    ).toBe(true)
  })
})
