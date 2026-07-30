import { describe, expect, it } from 'vitest'
import source from './MobileDocumentReadView.vue?raw'

describe('MobileDocumentReadView', () => {
  it('loads one fixed document and rejects a mismatched project context', () => {
    expect(source).toContain('documentApi.get(expectedDocumentId)')
    expect(source).toContain('loaded.projectId !== expectedProjectId')
    expect(source).toContain('requestId !== loadSequence')
    expect(source).not.toMatch(/documentApi\.(update|move|archive|restore)/)
    expect(source).toContain('documentApi.addFavorite(current.id)')
    expect(source).toContain('documentApi.removeFavorite(current.id)')
  })

  it('renders content with the shared editor in read-only mode', () => {
    expect(source).toMatch(/<StructuredDocumentEditor[^>]*:model-value="document\.content"[^>]*readonly/s)
    expect(source).toContain(`:aria-label="t('documents.mobile.back')"`)
    expect(source).toContain('role="status"')
    expect(source).toContain('role="alert"')
  })
})
