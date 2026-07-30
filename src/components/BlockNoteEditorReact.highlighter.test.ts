import { describe, expect, it } from 'vitest'
import { createCodeBlockHighlighter } from './BlockNoteEditorReact'

describe('BlockNote code block highlighter', () => {
  it('loads the Mermaid grammar before BlockNote retries highlighting', async () => {
    const highlighter = await createCodeBlockHighlighter()

    await highlighter.loadLanguage('mermaid')

    // BlockNote retries while the requested language is absent, so this is the termination contract.
    expect(highlighter.getLoadedLanguages()).toContain('mermaid')
  })
})
