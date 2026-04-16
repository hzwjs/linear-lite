/** @jsxImportSource react */
import { SuggestionMenu as SuggestionMenuExtension } from '@blocknote/core/extensions'
import type { BlockNoteEditor } from '@blocknote/core'
import type { DefaultReactSuggestionItem, SuggestionMenuProps } from '@blocknote/react'
import { useBlockNoteEditor, useExtension, useExtensionState } from '@blocknote/react'
import { applyVueInReact } from 'veaury'
import type { ComponentType } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import MemberListDropdownPanel from './MemberListDropdownPanel.vue'
import { i18n } from '../i18n'
import type { User } from '../types/domain'

const MemberListDropdownPanelReact = applyVueInReact(MemberListDropdownPanel, {
  beforeVueAppMount(app) {
    ;(app as { use: (plugin: unknown) => void }).use(i18n)
  },
}) as ComponentType<Record<string, unknown>>

type BnBlock = { id: string; content?: unknown[]; children?: BnBlock[] }

function stripFirstMentionForUser(content: unknown[], userId: number): unknown[] | null {
  const uid = String(userId)
  const next = [...content]
  for (let i = 0; i < next.length; i++) {
    const n = next[i] as { type?: string; props?: { userId?: string } }
    if (n?.type === 'mention' && String(n.props?.userId) === uid) {
      next.splice(i, 1)
      if (i < next.length && next[i] === ' ') {
        next.splice(i, 1)
      } else if (typeof next[i] === 'string') {
        const s = next[i] as string
        if (s.startsWith(' ')) {
          const rest = s.slice(1)
          if (rest === '') next.splice(i, 1)
          else next[i] = rest
        }
      }
      return next
    }
  }
  return null
}

function removeMentionForUserFromDocument(editor: BlockNoteEditor<any, any, any>, userId: number): boolean {
  const walk = (blocks: BnBlock[]): boolean => {
    for (const block of blocks) {
      if (Array.isArray(block.content)) {
        const stripped = stripFirstMentionForUser(block.content, userId)
        if (stripped) {
          editor.updateBlock(block.id, { content: stripped as never })
          return true
        }
      }
      if (block.children?.length) {
        if (walk(block.children)) return true
      }
    }
    return false
  }
  return walk(editor.document as BnBlock[])
}

function insertMentionAtCursor(editor: BlockNoteEditor<any, any, any>, u: User) {
  editor.insertInlineContent(
    [
      { type: 'mention', props: { userId: String(u.id), label: u.username } },
      ' ',
    ] as Parameters<typeof editor.insertInlineContent>[0],
  )
}

export type MentionMemberSuggestionMenuProps = SuggestionMenuProps<DefaultReactSuggestionItem> & {
  searchPlaceholder: string
  noMatchesText: string
  loadingText: string
  resolveMember: (label: string) => { id: number; label: string } | undefined
}

/**
 * 与 `MemberListDropdownPanel` 共用 UI：勾选成员立即插入 mention，取消勾选同步从正文删除。
 */
export function MentionMemberSuggestionMenu({
  items,
  loadingState,
  selectedIndex,
  searchPlaceholder,
  noMatchesText,
  loadingText,
  resolveMember,
}: MentionMemberSuggestionMenuProps) {
  const editor = useBlockNoteEditor()
  const suggestionMenu = useExtension(SuggestionMenuExtension, { editor })

  const [stagingUsers, setStagingUsers] = useState<User[]>([])
  const stagingUsersRef = useRef<User[]>([])
  stagingUsersRef.current = stagingUsers

  const showMenu = useExtensionState(SuggestionMenuExtension, {
    selector: (s) => s?.show ?? false,
  })
  const prevShowRef = useRef(false)
  useEffect(() => {
    if (showMenu && !prevShowRef.current) {
      setStagingUsers([])
    }
    if (!showMenu && prevShowRef.current) {
      setStagingUsers([])
    }
    prevShowRef.current = showMenu
  }, [showMenu])

  const query = useExtensionState(SuggestionMenuExtension, {
    selector: (s) => s?.query ?? '',
  })

  const showFullBleedLoader =
    (loadingState === 'loading-initial' || loadingState === 'loading') && items.length === 0

  const loading = showFullBleedLoader
  const emptyText = loading ? loadingText : items.length === 0 ? noMatchesText : ''

  const rows = useMemo(() => {
    return items.map((item) => {
      const mem = resolveMember(item.title)
      return {
        type: 'user' as const,
        user: {
          id: mem?.id ?? 0,
          username: item.title,
        } as User,
      }
    })
  }, [items, resolveMember])

  const stagingUserIds = useMemo(() => stagingUsers.map((u) => u.id), [stagingUsers])

  useEffect(() => {
    const el = document.getElementById(`bn-suggestion-menu-item-${selectedIndex}`)
    el?.scrollIntoView({ block: 'nearest' })
  }, [selectedIndex])

  function handleTogglePickUser(u: User) {
    setStagingUsers((prev) => {
      const exists = prev.some((x) => x.id === u.id)
      if (exists) {
        removeMentionForUserFromDocument(editor, u.id)
        return prev.filter((x) => x.id !== u.id)
      }
      if (prev.length === 0) {
        suggestionMenu.clearQuery()
      }
      insertMentionAtCursor(editor, u)
      return [...prev, u]
    })
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Escape') return
    e.preventDefault()
    e.stopPropagation()
    for (const u of stagingUsersRef.current) {
      removeMentionForUserFromDocument(editor, u.id)
    }
    setStagingUsers([])
    suggestionMenu.closeMenu()
    suggestionMenu.clearQuery()
  }

  return (
    <div onKeyDownCapture={handleKeyDown}>
      <MemberListDropdownPanelReact
        panelRootId="bn-suggestion-menu"
        includeBnSuggestionClass
        pickBehavior="mention-staging"
        optionIdMode="suggestion"
        idPrefix="mention"
        rows={rows}
        stagingUserIds={stagingUserIds}
        searchQuery={query}
        searchPlaceholder={searchPlaceholder}
        searchAriaLabel={searchPlaceholder}
        highlightedIndex={selectedIndex ?? 0}
        positionFixed={false}
        floatingStyle={{}}
        searchReadonly
        showAssigneeChrome={false}
        loading={loading}
        emptyText={emptyText}
        onTogglePickUser={handleTogglePickUser}
      />
    </div>
  )
}
