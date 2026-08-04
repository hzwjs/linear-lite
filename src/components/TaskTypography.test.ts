import { describe, expect, it } from 'vitest'
import taskEditorSource from './TaskEditor.vue?raw'
import blockNoteEditorSource from './BlockNoteEditorWrapper.vue?raw'
import issueComposerSource from './IssueComposer.vue?raw'

describe('task typography hierarchy', () => {
  it('keeps the task title visually above editor h1 headings', () => {
    expect(taskEditorSource).toContain('.content-section--title .title-input')
    expect(taskEditorSource).toContain('--task-editor-content-inset: 12px;')
    expect(taskEditorSource).toContain('padding-inline: var(--task-editor-content-inset);')
    expect(taskEditorSource).toContain('padding: 10px var(--task-editor-content-inset);')
    expect(taskEditorSource).not.toContain('padding-inline-start: 36px;')
    expect(taskEditorSource).toContain('font-size: 2rem;')
    expect(taskEditorSource).toContain('font-weight: 700;')
    expect(taskEditorSource).toContain('line-height: 1.18;')
    expect(taskEditorSource).toContain('.editor-panel--create .content-section--title')
    expect(taskEditorSource).toContain('margin-bottom: 16px;')

    expect(issueComposerSource).toContain('.content-section--title .composer-title-input')
    expect(issueComposerSource).toContain('font-size: 24px;')
    expect(issueComposerSource).toContain('font-weight: 600;')
    expect(issueComposerSource).toContain('margin-top: 6px;')

    expect(blockNoteEditorSource).toContain('.bn-default-styles')
    expect(blockNoteEditorSource).toContain('font-size: 15px !important;')
    expect(blockNoteEditorSource).toContain('--bn-desc-h1')
    expect(blockNoteEditorSource).toContain('line-height: 1.28 !important;')
  })

  it('uses BlockNote styling rather than the removed Tiptap editor', () => {
    expect(issueComposerSource).toContain('BlockNoteEditorWrapper')
    expect(issueComposerSource).not.toContain('TiptapEditor')
    expect(blockNoteEditorSource).toContain('.bn-block-outer')
    expect(blockNoteEditorSource).toContain('padding-block: 2px;')
  })
})
