import { describe, expect, it } from 'vitest'
import { asciiTableToMarkdown, shouldPasteClipboardAsMarkdown } from './markdownClipboard'

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

  it('recognizes a GFM table when rich HTML is also on the clipboard', () => {
    expect(
      shouldPasteClipboardAsMarkdown(
        ['text/html', 'text/plain'],
        '| 风险 | 影响等级 |\n| --- | --- |\n| 数据库绑定 | 高 |',
      ),
    ).toBe(true)
  })

  it('does not classify arbitrary pipe-delimited text as a table', () => {
    expect(
      shouldPasteClipboardAsMarkdown(
        ['text/html', 'text/plain'],
        '问题 A | 问题 B\n普通文本 | 普通文本',
      ),
    ).toBe(false)
  })

  it('converts a Unicode box-drawing table with wrapped cells to GFM', () => {
    expect(
      asciiTableToMarkdown(
        '┌───┬────────┬────┐\n' +
        '│ # │ 风险   │ 等级 │\n' +
        '├───┼────────┼────┤\n' +
        '│ 1 │ 数据库 │ 高  │\n' +
        '│   │ 绑定   │    │\n' +
        '└───┴────────┴────┘',
      ),
    ).toBe('| # | 风险 | 等级 |\n| --- | --- | --- |\n| 1 | 数据库 绑定 | 高 |')
  })
})
