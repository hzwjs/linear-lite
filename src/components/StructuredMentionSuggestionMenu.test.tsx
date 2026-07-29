/** @jsxImportSource react */
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  buildProjectDocumentMentionHref,
  createMemberMentionInline,
  createProjectDocumentLinkInline
} from './BlockNoteEditorReact'
import {
  StructuredMentionSuggestionMenu,
  type StructuredSuggestionItem
} from './StructuredMentionSuggestionMenu'

describe('structured @ suggestions', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  })

  it('builds a stable document route from IDs only', () => {
    expect(buildProjectDocumentMentionHref(7, 42)).toBe('/projects/7/documents/42')
    expect(createProjectDocumentLinkInline(7, 42, 'Architecture')).toEqual({
      type: 'link',
      href: '/projects/7/documents/42',
      content: 'Architecture'
    })
    expect(createMemberMentionInline(3, 'Ada')).toEqual({
      type: 'mention',
      props: { userId: '3', label: 'Ada' }
    })
  })

  it('renders member and document groups and keeps their identities separate', async () => {
    const items: StructuredSuggestionItem[] = [
      {
        kind: 'member', memberId: 3, label: 'Ada', title: 'Ada', group: 'Members',
        aliases: ['ada'], onItemClick: () => {}
      },
      {
        kind: 'document', documentId: 9, projectId: 7, label: 'Architecture', title: 'Architecture', group: 'Documents',
        aliases: ['architecture'], onItemClick: () => {}
      }
    ]
    const selected = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const root = createRoot(host)

    await act(async () => {
      root.render(
        <StructuredMentionSuggestionMenu
          items={items}
          loadingState="loaded"
          selectedIndex={1}
          onItemClick={selected}
          loadingText="Loading"
          noMatchesText="No matches"
        />
      )
    })

    expect(host.textContent).toContain('Members')
    expect(host.textContent).toContain('Documents')
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(2)
    expect(host.querySelector('[aria-selected="true"]')?.textContent).toContain('Architecture')
    ;(host.querySelectorAll<HTMLButtonElement>('[role="option"]')[1]).click()
    expect(selected).toHaveBeenCalledWith(items[1])

    await act(async () => root.unmount())
  })
})
