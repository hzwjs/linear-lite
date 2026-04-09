import type { SuggestionKeyDownProps, SuggestionProps } from '@tiptap/suggestion'

export type MentionListItem = { id: string; label: string }

/**
 * 无 tippy：用 fixed 定位的 DOM 列表实现 @ 建议层。
 */
export function createMentionListRender() {
  let root: HTMLElement | null = null
  let latest: SuggestionProps<MentionListItem, MentionListItem> | null = null
  let selectedIndex = 0

  function position() {
    if (!root || !latest?.clientRect) return
    const rect = latest.clientRect()
    if (!rect) return
    const gap = 4
    root.style.position = 'fixed'
    root.style.left = `${Math.max(8, rect.left)}px`
    root.style.top = `${rect.bottom + gap}px`
    root.style.zIndex = '10050'
  }

  function renderButtons() {
    if (!root || !latest) return
    root.innerHTML = ''
    if (latest.items.length === 0) {
      const empty = document.createElement('div')
      empty.className = 'tiptap-mention-suggestion-empty'
      empty.textContent = '—'
      root.appendChild(empty)
      return
    }
    selectedIndex = Math.max(0, Math.min(selectedIndex, latest.items.length - 1))
    latest.items.forEach((item, index) => {
      const btn = document.createElement('button')
      btn.type = 'button'
      btn.className =
        'tiptap-mention-suggestion-item' + (index === selectedIndex ? ' tiptap-mention-suggestion-item--active' : '')
      btn.textContent = item.label
      btn.addEventListener('mousedown', (e) => {
        e.preventDefault()
        latest?.command(item)
      })
      root!.appendChild(btn)
    })
  }

  return {
    onStart: (props: SuggestionProps<MentionListItem, MentionListItem>) => {
      latest = props
      selectedIndex = 0
      root = document.createElement('div')
      root.className = 'tiptap-mention-suggestion'
      root.setAttribute('role', 'listbox')
      document.body.appendChild(root)
      renderButtons()
      position()
    },
    onUpdate: (props: SuggestionProps<MentionListItem, MentionListItem>) => {
      latest = props
      selectedIndex = 0
      renderButtons()
      position()
    },
    onExit: () => {
      root?.remove()
      root = null
      latest = null
      selectedIndex = 0
    },
    onKeyDown: (keyProps: SuggestionKeyDownProps): boolean => {
      if (!latest || latest.items.length === 0) return false
      if (keyProps.event.key === 'ArrowDown') {
        keyProps.event.preventDefault()
        selectedIndex = (selectedIndex + 1) % latest.items.length
        renderButtons()
        return true
      }
      if (keyProps.event.key === 'ArrowUp') {
        keyProps.event.preventDefault()
        selectedIndex = (selectedIndex + latest.items.length - 1) % latest.items.length
        renderButtons()
        return true
      }
      if (keyProps.event.key === 'Enter') {
        keyProps.event.preventDefault()
        const item = latest.items[selectedIndex]
        if (item) latest.command(item)
        return true
      }
      return false
    },
  }
}
