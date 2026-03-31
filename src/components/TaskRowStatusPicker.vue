<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import type { Component } from 'vue'
import {
  Circle,
  CircleDashed,
  CircleX,
  CheckCircle,
  Copy,
  Eye,
  Loader2,
  Check
} from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import type { Status } from '../types/domain'
import { getStatusLabel } from '../utils/enumLabels'

const props = defineProps<{
  taskId: string
  status: Status
}>()

const emit = defineEmits<{
  change: [status: Status]
}>()

const { t } = useI18n()

const statusIcons: Record<Status, typeof Circle> = {
  backlog: CircleDashed,
  todo: Circle,
  in_progress: Loader2,
  in_review: Eye,
  done: CheckCircle,
  canceled: CircleX,
  duplicate: Copy
}

type StatusOption = {
  value: Status
  label: string
  icon: Component
  shortcut: string
}

const statusOptions = computed<StatusOption[]>(() => [
  { value: 'backlog', label: getStatusLabel('backlog'), icon: CircleDashed, shortcut: '1' },
  { value: 'todo', label: getStatusLabel('todo'), icon: Circle, shortcut: '2' },
  { value: 'in_progress', label: getStatusLabel('in_progress'), icon: Loader2, shortcut: '3' },
  { value: 'in_review', label: getStatusLabel('in_review'), icon: Eye, shortcut: '4' },
  { value: 'done', label: getStatusLabel('done'), icon: CheckCircle, shortcut: '5' },
  { value: 'canceled', label: getStatusLabel('canceled'), icon: CircleX, shortcut: '6' },
  { value: 'duplicate', label: getStatusLabel('duplicate'), icon: Copy, shortcut: '7' }
])

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const panelStyle = ref<{ top: string; left: string }>({ top: '0', left: '0' })
const highlightedIndex = ref(-1)

const FALLBACK_PANEL_WIDTH = 268
const FALLBACK_PANEL_HEIGHT = 320

const domTaskKey = computed(() => props.taskId.replace(/[^a-zA-Z0-9_-]/g, '-'))
const listboxId = computed(() => `task-row-status-list-${domTaskKey.value}`)

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

function open() {
  isOpen.value = true
  highlightedIndex.value = statusOptions.value.findIndex((o) => o.value === props.status)
  if (highlightedIndex.value < 0) highlightedIndex.value = 0
  nextTick(() => {
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  })
}

function close() {
  isOpen.value = false
  highlightedIndex.value = -1
  triggerRef.value?.focus()
}

function toggle(e: MouseEvent) {
  e.stopPropagation()
  if (isOpen.value) close()
  else open()
}

function pick(opt: StatusOption) {
  if (opt.value === props.status) {
    close()
    return
  }
  emit('change', opt.value)
  close()
}

function onTriggerKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    if (isOpen.value) {
      const opt = statusOptions.value[highlightedIndex.value]
      if (opt) pick(opt)
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
    else highlightedIndex.value = Math.min(highlightedIndex.value + 1, statusOptions.value.length - 1)
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
    triggerRef.value?.focus()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightedIndex.value = Math.min(highlightedIndex.value + 1, statusOptions.value.length - 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightedIndex.value = Math.max(highlightedIndex.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const opt = statusOptions.value[highlightedIndex.value]
    if (opt) pick(opt)
    return
  }
  if (e.key >= '1' && e.key <= '9') {
    const opt = statusOptions.value.find((o) => o.shortcut === e.key)
    if (opt) {
      e.preventDefault()
      pick(opt)
    }
  }
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

onMounted(() => document.addEventListener('click', handleClickOutside))
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  if (removeListeners) removeListeners()
})
</script>

<template>
  <span class="task-row-status">
    <button
      ref="triggerRef"
      type="button"
      class="task-row-status-trigger"
      :aria-label="t('taskList.changeStatus')"
      :aria-expanded="isOpen"
      aria-haspopup="listbox"
      :aria-controls="isOpen ? listboxId : undefined"
      @click="toggle"
      @keydown="onTriggerKeydown"
    >
      <component :is="statusIcons[status]" class="icon icon-14 status-icon" aria-hidden="true" />
    </button>
    <Teleport to="body">
      <div
        v-show="isOpen"
        :id="listboxId"
        ref="panelRef"
        class="task-row-status-panel"
        role="listbox"
        tabindex="-1"
        :style="panelStyle"
        :aria-activedescendant="
          statusOptions[highlightedIndex] != null
            ? `opt-${domTaskKey}-${statusOptions[highlightedIndex]!.value}`
            : undefined
        "
        @keydown="onListKeydown"
      >
        <div class="custom-select-search">
          <input
            type="text"
            class="custom-select-search-input"
            readonly
            tabindex="-1"
            :placeholder="t('boardView.filterByStatus')"
            :aria-label="t('select.searchAria')"
          />
        </div>
        <button
          v-for="(opt, i) in statusOptions"
          :id="`opt-${domTaskKey}-${opt.value}`"
          :key="opt.value"
          type="button"
          class="custom-select-option"
          :class="{ highlighted: i === highlightedIndex, selected: opt.value === status }"
          role="option"
          :aria-selected="opt.value === status"
          @click.stop="pick(opt)"
        >
          <span v-if="opt.icon" class="option-icon">
            <component :is="opt.icon" :size="18" />
          </span>
          <span class="option-label">{{ opt.label }}</span>
          <span v-if="opt.value === status" class="option-check" aria-hidden="true">
            <Check :size="16" />
          </span>
          <span v-else-if="opt.shortcut" class="option-shortcut">
            <kbd>{{ opt.shortcut }}</kbd>
          </span>
        </button>
      </div>
    </Teleport>
  </span>
</template>

<style scoped>
.task-row-status {
  flex: 0 0 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
}

.task-row-status-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin: 0;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  border-radius: var(--border-radius-sm, 4px);
}
.task-row-status-trigger:hover .status-icon {
  color: var(--color-text-primary);
}
.task-row-status-trigger:focus-visible {
  outline: 2px solid var(--color-status-done);
  outline-offset: 2px;
}

.icon {
  flex-shrink: 0;
}
.icon-14 {
  width: 14px;
  height: 14px;
}
.status-icon {
  color: var(--color-text-secondary);
}

.task-row-status-panel {
  position: fixed;
  z-index: 1001;
  min-width: 260px;
  max-height: 320px;
  overflow-y: auto;
  padding: 4px 0;
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-md);
  box-shadow: var(--shadow-popover);
  outline: none;
}

.custom-select-search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px 8px;
  border-bottom: 1px solid var(--color-border-subtle, var(--color-border));
}
.custom-select-search-input {
  flex: 1;
  padding: 6px 8px;
  font-size: var(--font-size-caption, 13px);
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  outline: none;
}
.custom-select-search-input::placeholder {
  color: var(--color-text-muted, var(--color-text-secondary));
}

.custom-select-option {
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
.custom-select-option .option-label {
  flex: 1;
}
.custom-select-option:hover,
.custom-select-option.highlighted {
  background: var(--color-hover);
}
.custom-select-option.selected {
  font-weight: 500;
}
.option-icon {
  display: inline-flex;
  color: var(--color-text-primary);
}
.option-check {
  display: inline-flex;
  color: var(--color-text-primary);
  margin-left: auto;
}
.option-shortcut {
  margin-left: auto;
}
.option-shortcut kbd {
  font-size: var(--font-size-xs, 11px);
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--color-bg-muted, #eee);
  color: var(--color-text-secondary);
  font-weight: 500;
  font-family: inherit;
}
</style>
