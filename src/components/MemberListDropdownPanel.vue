<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { User as UserIcon, Check } from 'lucide-vue-next'
import type { User } from '../types/domain'
import { getInitials, getAvatarColorByUsername } from '../utils/avatar'

export type MemberListRow = { type: 'unassigned' } | { type: 'user'; user: User }

const props = withDefaults(
  defineProps<{
    /** 浮层根节点 id（清单 aria-controls / BlockNote `bn-suggestion-menu`） */
    panelRootId: string
    /** 是否附加 `bn-suggestion-menu`（仅 BlockNote `@` 浮层需要） */
    includeBnSuggestionClass?: boolean
    /**
     * 行元素 id：`assignee` → `assignee-opt-{idPrefix}-{i}`（清单）；
     * `suggestion` → `bn-suggestion-menu-item-{i}`（与 BlockNote suggestion aria 一致）
     */
    optionIdMode?: 'assignee' | 'suggestion'
    /** optionIdMode 为 assignee 时的中间段；suggestion 模式忽略 */
    idPrefix: string
    rows: MemberListRow[]
    searchQuery: string
    searchPlaceholder: string
    searchAriaLabel: string
    highlightedIndex: number
    /** 浮层为 `position: fixed` + 外层 `style`（负责人 Teleport）；在 BlockNote 内嵌时为 false */
    positionFixed?: boolean
    floatingStyle?: Record<string, string>
    /** 搜索框是否只读（提及菜单镜像 @ 后输入） */
    searchReadonly?: boolean
    /** 是否显示负责人对勾与「已选」态 */
    showAssigneeChrome?: boolean
    currentAssigneeId?: number | null
    hasAnyAssigneeDisplay?: boolean
    /** 首屏加载中（BlockNote suggestion） */
    loading?: boolean
    /** 无行且已加载时的提示 */
    emptyText?: string
    /**
     * `mention-staging`：`@` 提及多选暂存，点击行只切换选中，不立即 `pickUser`。
     * 默认 `assignee`：点击行即确认（负责人）。
     */
    pickBehavior?: 'assignee' | 'mention-staging'
    /** `mention-staging` 时已勾选成员 id（用于对勾） */
    stagingUserIds?: number[]
  }>(),
  {
    includeBnSuggestionClass: false,
    pickBehavior: 'assignee',
    stagingUserIds: () => [],
    optionIdMode: 'assignee',
    positionFixed: true,
    floatingStyle: () => ({}),
    searchReadonly: false,
    showAssigneeChrome: false,
    currentAssigneeId: null,
    hasAnyAssigneeDisplay: false,
    loading: false,
    emptyText: '',
  }
)

const emit = defineEmits<{
  'update:searchQuery': [value: string]
  pickUser: [user: User]
  pickUnassigned: []
  togglePickUser: [user: User]
  keydown: [e: KeyboardEvent]
}>()

const { t } = useI18n()

const rootRef = ref<HTMLElement | null>(null)

const safeHighlight = computed(() => {
  if (props.rows.length === 0) return -1
  return Math.max(0, Math.min(props.highlightedIndex, props.rows.length - 1))
})

function optionDomId(i: number): string {
  if (props.optionIdMode === 'suggestion') return `bn-suggestion-menu-item-${i}`
  return `assignee-opt-${props.idPrefix}-${i}`
}

function onSearchInput(e: Event) {
  if (props.searchReadonly) return
  emit('update:searchQuery', (e.target as HTMLInputElement).value)
}

function onRootKeydown(e: KeyboardEvent) {
  emit('keydown', e)
}

function pickRow(row: MemberListRow) {
  if (row.type === 'unassigned') emit('pickUnassigned')
  else emit('pickUser', row.user)
}

function onOptionClick(row: MemberListRow) {
  if (row.type === 'unassigned') {
    pickRow(row)
    return
  }
  if (props.pickBehavior === 'mention-staging') emit('togglePickUser', row.user)
  else pickRow(row)
}

defineExpose({
  focus: () => rootRef.value?.focus(),
  getRoot: () => rootRef.value,
})
</script>

<template>
  <div
    ref="rootRef"
    :id="panelRootId"
    class="task-row-assignee-panel"
    :class="{
      'bn-suggestion-menu': includeBnSuggestionClass,
      'task-row-assignee-panel--anchored': !positionFixed,
    }"
    role="listbox"
    tabindex="-1"
    :style="positionFixed ? floatingStyle : undefined"
    :aria-activedescendant="safeHighlight >= 0 ? optionDomId(safeHighlight) : undefined"
    @keydown="onRootKeydown"
  >
    <div class="assignee-search">
      <input
        :value="searchQuery"
        type="search"
        class="assignee-search-input"
        :readonly="searchReadonly"
        :placeholder="searchPlaceholder"
        :aria-label="searchAriaLabel"
        @input="onSearchInput"
        @click.stop
      />
    </div>
    <template v-if="loading && rows.length === 0">
      <div class="member-list-dropdown-loading">{{ emptyText }}</div>
    </template>
    <template v-else>
      <button
        v-for="(row, i) in rows"
        :id="optionDomId(i)"
        :key="row.type === 'unassigned' ? 'unassigned' : row.user.id"
        type="button"
        class="assignee-option"
        :class="{
          highlighted: i === safeHighlight,
          selected:
            showAssigneeChrome &&
            (row.type === 'unassigned'
              ? !hasAnyAssigneeDisplay
              : row.user.id === currentAssigneeId),
          'mention-staged':
            pickBehavior === 'mention-staging' &&
            row.type === 'user' &&
            stagingUserIds.includes(row.user.id),
        }"
        role="option"
        :aria-selected="
          showAssigneeChrome
            ? row.type === 'unassigned'
              ? !hasAnyAssigneeDisplay
              : row.user.id === currentAssigneeId
            : undefined
        "
        @mousedown.prevent
        @click.stop="onOptionClick(row)"
      >
        <span v-if="row.type === 'unassigned'" class="assignee-option-inner">
          <UserIcon class="assignee-option-icon" :size="18" aria-hidden="true" />
          <span class="assignee-option-label">{{ t('common.unassigned') }}</span>
        </span>
        <span v-else class="assignee-option-inner">
          <img
            v-if="row.user.avatar_url"
            :src="row.user.avatar_url"
            :alt="row.user.username"
            class="assignee-option-avatar"
          />
          <span
            v-else
            class="assignee-option-avatar fallback"
            :style="getAvatarColorByUsername(row.user.username)"
            >{{ getInitials(row.user.username) }}</span
          >
          <span class="assignee-option-label">{{ row.user.username }}</span>
        </span>
        <span
          v-if="
            showAssigneeChrome &&
            (row.type === 'unassigned'
              ? !hasAnyAssigneeDisplay
              : row.user.id === currentAssigneeId)
          "
          class="option-check"
          aria-hidden="true"
        >
          <Check :size="16" />
        </span>
        <span
          v-else-if="
            pickBehavior === 'mention-staging' &&
            row.type === 'user' &&
            stagingUserIds.includes(row.user.id)
          "
          class="option-check mention-stage-check"
          aria-hidden="true"
        >
          <Check :size="16" />
        </span>
      </button>
      <div v-if="!loading && rows.length === 0 && emptyText" class="member-list-dropdown-empty">
        {{ emptyText }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.task-row-assignee-panel {
  position: fixed;
  z-index: 1001;
  min-width: 260px;
  max-width: min(320px, 100vw - 16px);
  max-height: 320px;
  overflow-y: auto;
  padding: 4px 0;
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-md);
  box-shadow: var(--shadow-popover);
  outline: none;
}

.task-row-assignee-panel--anchored {
  position: relative;
  z-index: auto;
  width: 100%;
  min-width: 260px;
  max-width: min(320px, 100vw - 16px);
}

.assignee-search {
  padding: 6px 10px 8px;
  border-bottom: 1px solid var(--color-border-subtle, var(--color-border));
  position: sticky;
  top: 0;
  background: var(--color-bg-main);
  z-index: 1;
}
.assignee-search-input {
  width: 100%;
  box-sizing: border-box;
  padding: 6px 8px;
  font-size: var(--font-size-caption, 13px);
  color: var(--color-text-primary);
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  outline: none;
}
.assignee-search-input:focus {
  border-color: var(--color-status-done);
}

.assignee-option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  text-align: left;
  font-size: var(--font-size-body, 14px);
  color: var(--color-text-primary);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background var(--transition-fast);
  min-height: 38px;
  box-sizing: border-box;
}
.assignee-option-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.assignee-option-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assignee-option-icon {
  flex-shrink: 0;
  color: var(--color-text-secondary);
}
.assignee-option-avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}
.assignee-option-avatar.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  color: #fff;
}
.assignee-option:hover,
.assignee-option.highlighted {
  background: var(--color-hover);
}
.assignee-option.selected {
  font-weight: 500;
}
.option-check {
  display: inline-flex;
  color: var(--color-text-primary);
  flex-shrink: 0;
}

.member-list-dropdown-loading,
.member-list-dropdown-empty {
  padding: 10px 12px;
  font-size: var(--font-size-caption, 12px);
  color: var(--color-text-muted);
}
</style>
