import { describe, expect, it } from 'vitest'
import taskEditorSource from './TaskEditor.vue?raw'
import tiptapEditorSource from './TiptapEditor.vue?raw'
import issueComposerSource from './IssueComposer.vue?raw'

describe('task typography hierarchy', () => {
  it('keeps the task title visually above editor h1 headings', () => {
    expect(taskEditorSource).toContain('.content-section--title .title-textarea')
    expect(taskEditorSource).toContain('font-size: 2rem;')
    expect(taskEditorSource).toContain('font-weight: 700;')
    expect(taskEditorSource).toContain('line-height: 1.18;')
    expect(taskEditorSource).toContain('margin-top: 6px;')

    expect(issueComposerSource).toContain('.content-section--title .composer-title-input')
    expect(issueComposerSource).toContain('font-size: 2rem;')
    expect(issueComposerSource).toContain('font-weight: 700;')
    expect(issueComposerSource).toContain('margin-top: 6px;')

    expect(tiptapEditorSource).toContain(':deep(.tiptap h1)')
    expect(tiptapEditorSource).toContain('font-size: 1.5rem;')
    expect(tiptapEditorSource).toContain('line-height: 1.24;')
    expect(tiptapEditorSource).toContain('margin: 1.35em 0 0.5em;')
    expect(tiptapEditorSource).toContain(':deep(.tiptap > *:first-child)')
    expect(tiptapEditorSource).toContain('margin-top: 0;')
    expect(tiptapEditorSource).toContain(':deep(.tiptap h1:first-child)')
    expect(tiptapEditorSource).toContain('margin-top: 0.1em;')
  })

  it('renders checklist items without bullets and keeps checkbox/content inline', () => {
    expect(tiptapEditorSource).toContain('TaskItem.configure({ nested: true })')
    expect(tiptapEditorSource).toContain(':deep(.tiptap ul[data-type="taskList"])')
    expect(tiptapEditorSource).toContain('list-style: none;')
    expect(tiptapEditorSource).toContain('padding-left: 1.5em;')
    expect(tiptapEditorSource).toContain(':deep(.tiptap ul[data-type="taskList"] li)')
    expect(tiptapEditorSource).toContain('display: flex;')
    expect(tiptapEditorSource).toContain('align-items: center;')
    expect(tiptapEditorSource).toContain(':deep(.tiptap ul[data-type="taskList"] li > div)')
    expect(tiptapEditorSource).toContain('flex: 1;')
    expect(tiptapEditorSource).toContain('min-width: 0;')
    expect(tiptapEditorSource).not.toContain('padding-top: 0.1em;')
  })
})
