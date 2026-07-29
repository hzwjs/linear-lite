/** @jsxImportSource react */
import type { DefaultReactSuggestionItem, SuggestionMenuProps } from '@blocknote/react'

export type StructuredSuggestionItem = DefaultReactSuggestionItem & (
  | { kind: 'member'; memberId: number; label: string }
  | { kind: 'document'; documentId: number; projectId: number; label: string }
)

type Props = SuggestionMenuProps<StructuredSuggestionItem> & {
  loadingText: string
  noMatchesText: string
}
/**
 * 统一展示 `@` 的成员与文档数据源；这里只负责分组和选择，写入语义由编辑器按 kind 执行。
 */
export function StructuredMentionSuggestionMenu({
  items,
  loadingState,
  selectedIndex,
  onItemClick,
  loadingText,
  noMatchesText,
}: Props) {
  let previousGroup: string | undefined
  const loading = loadingState === 'loading' || loadingState === 'loading-initial'
  return (
    <div id="bn-suggestion-menu" className="bn-suggestion-menu bn-structured-suggestion-menu" role="listbox">
      {items.map((item, index) => {
        const showGroup = item.group !== previousGroup
        previousGroup = item.group
        return (
          <div key={`${item.kind}-${item.kind === 'member' ? item.memberId : item.documentId}`}>
            {showGroup && item.group ? <div className="bn-structured-suggestion-group">{item.group}</div> : null}
            <button
              id={`bn-suggestion-menu-item-${index}`}
              type="button"
              role="option"
              aria-selected={index === selectedIndex}
              className="bn-suggestion-menu-item bn-structured-suggestion-item"
              onClick={() => onItemClick?.(item)}
            >
              <span className={`bn-structured-suggestion-icon bn-structured-suggestion-icon--${item.kind}`} aria-hidden="true">
                {item.kind === 'member' ? '@' : '↗'}
              </span>
              <span className="bn-structured-suggestion-title">{item.label}</span>
            </button>
          </div>
        )
      })}
      {items.length === 0 ? <div className="bn-structured-suggestion-empty">{loading ? loadingText : noMatchesText}</div> : null}
    </div>
  )
}
