import { describe, expect, it } from 'vitest'
import source from './GlobalSearchModal.vue?raw'

describe('GlobalSearchModal integration', () => {
  it('keeps keyboard focus and active-option semantics inside the dialog', () => {
    expect(source).toContain("document.addEventListener('focusin', onFocusIn)")
    expect(source).toContain(':aria-activedescendant="activeOptionId"')
    expect(source).toContain('previousActiveElement?.focus()')
  })

  it('uses the shared language and design systems without display fallbacks', () => {
    expect(source).toContain("t('globalSearch.placeholder')")
    expect(source).toContain('var(--color-bg-main)')
    expect(source).toContain('var(--shadow-popover)')
    expect(source).not.toMatch(/var\(--(?:bg|text|border-color)-/)
    expect(source).not.toContain('result.title ||')
    expect(source).not.toContain('result.excerpt ||')
  })
})
