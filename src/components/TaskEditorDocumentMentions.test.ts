import { describe, expect, it } from 'vitest'
import source from './TaskEditor.vue?raw'

describe('TaskEditor document mentions', () => {
  it('loads the active project document tree and passes it to the description editor', () => {
    expect(source).toContain('documentApi.listTree(projectId)')
    expect(source).toContain(':mention-documents="mentionDocumentsForDescription"')
    expect(source).toContain(':mention-members="mentionMembersForDescription"')
  })
})
