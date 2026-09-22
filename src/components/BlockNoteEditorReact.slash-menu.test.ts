import { describe, expect, it } from 'vitest'
import blockNoteReactSource from './BlockNoteEditorReact.tsx?raw'

describe('BlockNote task description slash menu', () => {
  it('uses an explicit elevated controller instead of BlockNote default layering', () => {
    expect(blockNoteReactSource).toContain('const taskDescriptionSuggestionMenuLayer = 1000')
    expect(blockNoteReactSource).toContain('slashMenu={false}')
    expect(blockNoteReactSource).toMatch(
      /documentIdResolved == null && blockChromeOn[\s\S]*?SuggestionMenuController[\s\S]*?triggerCharacter="\/"[\s\S]*?floatingUIOptions=\{taskDescriptionSuggestionMenuOptions\}/,
    )
  })
})
