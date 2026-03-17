import { describe, expect, it } from 'vitest'
import tiptapEditorSource from './TiptapEditor.vue?raw'

describe('TiptapEditor checklist paste behavior', () => {
  it('parses pasted markdown checklist text into task list content', () => {
    expect(tiptapEditorSource).toContain("event.clipboardData?.getData('text/plain')")
    expect(tiptapEditorSource).toContain('looksLikeMarkdownTaskList(text)')
    expect(tiptapEditorSource).toContain('insertContent(mdToHtml(text))')
  })
})
