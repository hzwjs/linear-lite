<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { User as UserIcon, Check } from 'lucide-vue-next'
import type { Task, User } from '../types/domain'
import { getInitials, getAvatarColor } from '../utils/avatar'

const props = defineProps<{
  taskId: string
  task: Task
  users?: User[]
  /** 悬停提示，与清单列 data-tooltip 一致 */
  tooltip?: string
}>()

const emit = defineEmits<{
  pick: [userId: number | null, options: { clearAssignee: boolean }]
}>()

const { t } = useI18n()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const panelStyle = ref<{ top: string; left: string }>({ top: '0', left: '0' })
const searchQuery = ref('')
const highlightedIndex = ref(0)

const FALLBACK_PANEL_WIDTH = 280
const FALLBACK_PANEL_HEIGHT = 320

const domTaskKey = computed(() => props.taskId.replace(/[^a-zA-Z0-9_-]/g, '-'))
const listboxId = computed(() => `task-row-assignee-list-${domTaskKey.value}`)

const sortedUsers = computed(() => {
  const list = [...(props.users ?? [])]
  list.sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: 'base' }))
  return list
})

const filteredUsers = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return sortedUsers.value
  return sortedUsers.value.filter((u) => u.username.toLowerCase().includes(q))
})

type RowOpt = { type: 'unassigned' } | { type: 'user'; user: User }

const optionRows = computed<RowOpt[]>(() => {
  const rows: RowOpt[] = [{ type: 'unassigned' }]
  for (const u of filteredUsers.value) rows.push({ type: 'user', user: u })
  return rows
})

const currentAssigneeId = computed(() => props.task.assigneeId ?? null)
const hasImportedNameOnly = computed(
  () => currentAssigneeId.value == null && !!(props.task.assigneeDisplayName?.trim())
)
const hasAnyAssigneeDisplay = computed(
  () => currentAssigneeId.value != null || hasImportedNameOnly.value
)

function updatePanelPosition() {
  if (!triggerRef.value || !isOpen.value) return
  const rect = triggerRef.value.getBoundingClientRect()
  const panelRect = panelRef.value?.getBoundingClientRect()
  const panelWidth = panelRect && panelRect.width > 0 ? panelRect.width : FALLBACK_PANEL_WIDTH
  const panelHeight = panelRect && panelRect.height > 0 ? panelRect.height : FALLBACK_PANEL_HEIGHT
  const viewportW = window.innerWidth
  const viewportH = window.innerHeight
  let left = rect.left
  let top = rect.bottom + 4
  const preferOpenLeft = rect.right > viewportW / 2
  if (preferOpenLeft || rect.left + panelWidth > viewportW) {
    left = rect.right - panelWidth
  }
  if (rect.bottom + panelHeight > viewportH && rect.top >= panelHeight) {
    top = rect.top - panelHeight - 4
  }
  left = Math.max(0, Math.min(left, viewportW - panelWidth))
  top = Math.max(0, Math.min(top, viewportH - panelHeight))
  panelStyle.value = { top: `${top}px`, left: `${left}px` }
}

function syncHighlightToSelection() {
  const rows = optionRows.value
  let idx = rows.findIndex((r) => {
    if (r.type === 'unassigned') return !hasAnyAssigneeDisplay.value
    return r.user.id === currentAssigneeId.value
  })
  if (idx < 0) idx = 0
  highlightedIndex.value = idx
}

function open() {
  isOpen.value = true
  searchQuery.value = ''
  syncHighlightToSelection()
  nextTick(() => {
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  })
}

function close() {
  isOpen.value = false
  highlightedIndex.value = 0
  searchQuery.value = ''
  triggerRef.value?.focus()
}

function toggle(e: MouseEvent) {
  e.stopPropagation()
  if (isOpen.value) close()
  else open()
}

function pickUnassigned() {
  if (!hasAnyAssigneeDisplay.value) {
    close()
    return
  }
  const clearAssignee = true
  emit('pick', null, { clearAssignee })
  close()
}

function pickUser(user: User) {
  if (user.id === currentAssigneeId.value) {
    close()
    return
  }
  emit('pick', user.id, { clearAssignee: false })
  close()
}

function pickRow(row: RowOpt) {
  if (row.type === 'unassigned') pickUnassigned()
  else pickUser(row.user)
}

function onTriggerKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    if (isOpen.value) {
      const row = optionRows.value[highlightedIndex.value]
      if (row) pickRow(row)
    } else {
      open()
    }
    return
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!isOpen.value) open()
    else
      highlightedIndex.value = Math.min(highlightedIndex.value + 1, optionRows.value.length - 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (!isOpen.value) open()
    else highlightedIndex.value = Math.max(highlightedIndex.value - 1, 0)
    return
  }
}

function onListKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightedIndex.value = Math.min(highlightedIndex.value + 1, optionRows.value.length - 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightedIndex.value = Math.max(highlightedIndex.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const row = optionRows.value[highlightedIndex.value]
    if (row) pickRow(row)
  }
}

function onSearchInput() {
  highlightedIndex.value = 0
}

function handleClickOutside(e: MouseEvent) {
  const el = e.target as Node
  if (
    isOpen.value &&
    triggerRef.value &&
    !triggerRef.value.contains(el) &&
    panelRef.value &&
    !panelRef.value.contains(el)
  ) {
    close()
  }
}

let removeListeners: (() => void) | null = null

watch(isOpen, (open) => {
  if (removeListeners) {
    removeListeners()
    removeListeners = null
  }
  if (!open) return
  const onUpdate = () => updatePanelPosition()
  window.addEventListener('resize', onUpdate)
  window.addEventListener('scroll', onUpdate, true)
  removeListeners = () => {
    window.removeEventListener('resize', onUpdate)
    window.removeEventListener('scroll', onUpdate, true)
  }
  setTimeout(() => {
    panelRef.value?.focus()
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  }, 0)
})

watch(filteredUsers, () => {
  if (highlightedIndex.value >= optionRows.value.length) {
    highlightedIndex.value = Math.max(0, optionRows.value.length - 1)
  }
})

onMounted(() => document.addEventListener('click', handleClickOutside))
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  if (removeListeners) removeListeners()
})
</script>

<template>
  <span class="task-row-assignee-picker">
    <button
      ref="triggerRef"
      type="button"
      class="task-row-assignee-picker-trigger task-assignee-trigger"
      :class="{ assigned: hasAnyAssigneeDisplay, unassigned: !hasAnyAssigneeDisplay }"
      :data-tooltip="tooltip ?? ''"
      :aria-label="t('taskList.changeAssignee')"
      :aria-expanded="isOpen"
      aria-haspopup="listbox"
      :aria-controls="isOpen ? listboxId : undefined"
      @click="toggle"
      @keydown="onTriggerKeydown"
    >
      <slot />
    </button>
    <Teleport to="body">
      <div
        v-show="isOpen"
        :id="listboxId"
        ref="panelRef"
        class="task-row-assignee-panel"
        role="listbox"
        tabindex="-1"
        :style="panelStyle"
        :aria-activedescendant="
          optionRows[highlightedIndex] != null
            ? `assignee-opt-${domTaskKey}-${highlightedIndex}`
            : undefined
        "
        @keydown="onListKeydown"
      >
        <div class="assignee-search">
          <input
            v-model="searchQuery"
            type="search"
            class="assignee-search-input"
            :placeholder="t('taskList.assigneeSearchPlaceholder')"
            :aria-label="t('taskList.assigneeSearchPlaceholder')"
            @input="onSearchInput"
            @click.stop
          />
        </div>
        <button
          v-for="(row, i) in optionRows"
          :id="`assignee-opt-${domTaskKey}-${i}`"
          :key="row.type === 'unassigned' ? 'unassigned' : row.user.id"
          type="button"
          class="assignee-option"
          :class="{
            highlighted: i === highlightedIndex,
            selected:
              row.type === 'unassigned'
                ? !hasAnyAssigneeDisplay
                : row.user.id === currentAssigneeId
          }"
          role="option"
          :aria-selected="
            row.type === 'unassigned' ? !hasAnyAssigneeDisplay : row.user.id === currentAssigneeId
          "
          @click.stop="pickRow(row)"
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
              :style="getAvatarColor(row.user.id)"
              >{{ getInitials(row.user.username) }}</span
            >
            <span class="assignee-option-label">{{ row.user.username }}</span>
          </span>
          <span
            v-if="
              row.type === 'unassigned'
                ? !hasAnyAssigneeDisplay
                : row.user.id === currentAssigneeId
            "
            class="option-check"
            aria-hidden="true"
          >
            <Check :size="16" />
          </span>
        </button>
      </div>
    </Teleport>
  </span>
</template>

<style scoped>
.task-row-assignee-picker {
  display: inline-flex;
  align-items: center;
}

.task-row-assignee-picker-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin: 0;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  position: relative;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  outline: none;
}
.task-row-assignee-picker-trigger:focus-visible {
  outline: 2px solid var(--color-status-done);
  outline-offset: 2px;
}
.task-assignee-trigger:focus-visible::before {
  opacity: 1;
  transform: translate(-50%, -2px);
}
.task-assignee-trigger:focus-visible::after {
  opacity: 1;
  transform: translateX(-50%);
}
.task-assignee-trigger::before {
  content: attr(data-tooltip);
  position: absolute;
  left: 50%;
  bottom: calc(100% + 8px);
  transform: translate(-50%, 2px);
  padding: 5px 8px;
  border-radius: 6px;
  background: #111827;
  color: #fff;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  box-shadow: var(--shadow-popover);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
  z-index: 4;
}
.task-assignee-trigger::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: calc(100% + 4px);
  width: 8px;
  height: 8px;
  background: #111827;
  transform: translateX(-50%) rotate(45deg);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
  z-index: 3;
}
.task-assignee-trigger.unassigned {
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
}
.task-assignee-trigger.assigned {
  background: transparent;
}

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
</style>
