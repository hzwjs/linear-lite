<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick, useId } from 'vue'
import type { Component } from 'vue'
import { Check } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

export interface CustomSelectOption {
  value: string | number | null
  label: string
  icon?: Component
  /** Linear 风格：选项右侧数字快捷键，如 "1" "2" "3" */
  shortcut?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string | number | null
    options: CustomSelectOption[]
    placeholder?: string
    ariaLabel?: string
    triggerClass?: string
    /** Linear 风格：下拉顶部占位文案，如 "Change status..." */
    searchPlaceholder?: string
    /** 占位输入框右侧快捷键角标，如 "S" */
    searchShortcutBadge?: string
    /** 为 true 时顶部为可输入过滤，选项按标签子串匹配（不区分大小写） */
    filterable?: boolean
    filterInputPlaceholder?: string
  }>(),
  { placeholder: '', ariaLabel: '', triggerClass: '', filterable: false, filterInputPlaceholder: '' }
)

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
}>()
const { t } = useI18n()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const filterInputRef = ref<HTMLInputElement | null>(null)
const filterQuery = ref('')
const highlightedIndex = ref(-1)
const popoverStyle = ref({ top: '0px', left: '0px', minWidth: '0px' })
const listboxId = `${useId()}-custom-select-listbox`
const resolvedPlaceholder = computed(() => props.placeholder || t('select.placeholder'))
const resolvedAriaLabel = computed(() => props.ariaLabel || t('select.ariaLabel'))
const resolvedFilterPlaceholder = computed(
  () => props.filterInputPlaceholder || t('select.filterPlaceholder')
)

const selectedOption = computed(() =>
  props.options.find((o) => o.value === props.modelValue)
)
const displayLabel = computed(() => selectedOption.value?.label ?? resolvedPlaceholder.value)

const displayedOptions = computed(() => {
  if (!props.filterable || !filterQuery.value.trim()) return props.options
  const q = filterQuery.value.trim().toLowerCase()
  return props.options.filter((o) => o.label.toLowerCase().includes(q))
})

function syncHighlightToDisplayed() {
  const opts = displayedOptions.value
  let i = opts.findIndex((o) => o.value === props.modelValue)
  if (i < 0) i = 0
  highlightedIndex.value = opts.length > 0 ? Math.min(i, opts.length - 1) : -1
}

function open() {
  if (props.filterable) filterQuery.value = ''
  isOpen.value = true
  nextTick(() => {
    syncHighlightToDisplayed()
    updatePopoverPosition()
    if (props.filterable) filterInputRef.value?.focus()
    else listRef.value?.focus()
  })
}

function updatePopoverPosition() {
  if (!isOpen.value || !triggerRef.value || !listRef.value) return
  const triggerRect = triggerRef.value.getBoundingClientRect()
  const listRect = listRef.value.getBoundingClientRect()
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const inset = 8
  const gap = 4
  const width = listRect.width || Math.max(triggerRect.width, 180)
  const height = listRect.height || 280
  let left = triggerRect.left
  let top = triggerRect.bottom + gap

  if (left + width > viewportWidth - inset) left = triggerRect.right - width
  if (top + height > viewportHeight - inset && triggerRect.top > height + inset) {
    top = triggerRect.top - height - gap
  }

  popoverStyle.value = {
    left: `${Math.max(inset, Math.min(left, viewportWidth - width - inset))}px`,
    top: `${Math.max(inset, Math.min(top, viewportHeight - height - inset))}px`,
    minWidth: `${Math.max(triggerRect.width, 180)}px`
  }
}
function close() {
  isOpen.value = false
  highlightedIndex.value = -1
  filterQuery.value = ''
}
function select(opt: CustomSelectOption) {
  emit('update:modelValue', opt.value)
  close()
}

function onTriggerKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    if (isOpen.value) {
      const opts = displayedOptions.value
      const opt = opts[highlightedIndex.value]
      if (opt) select(opt)
    } else open()
    return
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    triggerRef.value?.focus()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!isOpen.value) open()
    else {
      const opts = displayedOptions.value
      highlightedIndex.value = Math.min(highlightedIndex.value + 1, Math.max(0, opts.length - 1))
    }
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (!isOpen.value) open()
    else highlightedIndex.value = Math.max(highlightedIndex.value - 1, 0)
    return
  }
}

function moveHighlight(delta: number) {
  const opts = displayedOptions.value
  if (opts.length === 0) return
  const next = Math.min(Math.max(highlightedIndex.value + delta, 0), opts.length - 1)
  highlightedIndex.value = next
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
    moveHighlight(1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    moveHighlight(-1)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const opt = displayedOptions.value[highlightedIndex.value]
    if (opt) select(opt)
    return
  }
  if (e.key >= '1' && e.key <= '9') {
    const opt = displayedOptions.value.find((o) => o.shortcut === e.key)
    if (opt) {
      e.preventDefault()
      select(opt)
    }
  }
}

function onFilterInputKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    triggerRef.value?.focus()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    moveHighlight(1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    moveHighlight(-1)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const opt = displayedOptions.value[highlightedIndex.value]
    if (opt) select(opt)
    return
  }
}

function handleClickOutside(e: MouseEvent) {
  const el = e.target as Node
  if (
    isOpen.value &&
    triggerRef.value &&
    !triggerRef.value.contains(el) &&
    listRef.value &&
    !listRef.value.contains(el)
  ) {
    close()
  }
}

watch(filterQuery, () => {
  if (!isOpen.value || !props.filterable) return
  syncHighlightToDisplayed()
})

watch(isOpen, (open) => {
  if (open && !props.filterable) {
    setTimeout(() => listRef.value?.focus(), 0)
  }
})

function handleViewportChange() {
  if (isOpen.value) updatePopoverPosition()
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  window.addEventListener('resize', handleViewportChange)
  window.addEventListener('scroll', handleViewportChange, true)
})
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  window.removeEventListener('resize', handleViewportChange)
  window.removeEventListener('scroll', handleViewportChange, true)
})
</script>

<template>
  <div class="custom-select">
    <button
      ref="triggerRef"
      type="button"
      class="custom-select-trigger"
      :class="triggerClass"
      :aria-label="resolvedAriaLabel"
      :aria-expanded="isOpen"
      :aria-haspopup="'listbox'"
      :aria-controls="listboxId"
      @click="isOpen ? close() : open()"
      @keydown="onTriggerKeydown"
    >
      <span v-if="selectedOption?.icon" class="trigger-icon">
        <component :is="selectedOption.icon" :size="16" />
      </span>
      <span class="trigger-label">{{ displayLabel }}</span>
      <span class="trigger-chevron" aria-hidden="true">▼</span>
    </button>
    <Teleport to="body">
      <div
        v-show="isOpen"
        :id="listboxId"
        ref="listRef"
        class="custom-select-list"
        :style="popoverStyle"
        role="listbox"
        tabindex="-1"
        :aria-activedescendant="
          displayedOptions[highlightedIndex] != null
            ? `${listboxId}-opt-${String(displayedOptions[highlightedIndex]!.value)}`
            : undefined
        "
        @keydown="onListKeydown"
      >
      <div v-if="filterable" class="custom-select-search">
        <input
          ref="filterInputRef"
          v-model="filterQuery"
          type="text"
          class="custom-select-search-input"
          :placeholder="resolvedFilterPlaceholder"
          :aria-label="resolvedFilterPlaceholder"
          autocomplete="off"
          @click.stop
          @keydown="onFilterInputKeydown"
        />
      </div>
      <div v-else-if="searchPlaceholder" class="custom-select-search">
        <input
          type="text"
          class="custom-select-search-input"
          :placeholder="searchPlaceholder"
          readonly
          tabindex="-1"
          :aria-label="t('select.searchAria')"
        />
        <kbd v-if="searchShortcutBadge" class="custom-select-search-badge">{{ searchShortcutBadge }}</kbd>
      </div>
      <button
        v-for="(opt, i) in displayedOptions"
        :id="`${listboxId}-opt-${String(opt.value)}`"
        :key="String(opt.value)"
        type="button"
        class="custom-select-option"
        :class="{ highlighted: i === highlightedIndex, selected: opt.value === modelValue }"
        role="option"
        :aria-selected="opt.value === modelValue"
        @click="select(opt)"
      >
        <span v-if="opt.icon" class="option-icon">
          <component :is="opt.icon" :size="18" />
        </span>
        <span class="option-label">{{ opt.label }}</span>
        <span v-if="opt.value === modelValue" class="option-check" aria-hidden="true">
          <Check :size="16" />
        </span>
        <span v-else-if="opt.shortcut" class="option-shortcut">
          <kbd>{{ opt.shortcut }}</kbd>
        </span>
      </button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.custom-select {
  position: relative;
  display: inline-block;
}
.custom-select-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 120px;
  padding: 6px 10px;
  font-size: var(--font-size-body, 14px);
  color: var(--color-text-primary);
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  box-shadow: none;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}
.custom-select-trigger:hover {
  background: var(--color-hover);
}
.custom-select-trigger:focus {
  outline: none;
  border-color: var(--color-status-done);
}
.trigger-icon {
  display: inline-flex;
  color: var(--color-text-primary);
}
.trigger-label {
  flex: 1;
  text-align: left;
}
.trigger-chevron {
  font-size: 10px;
  color: var(--color-text-secondary);
}
.custom-select-list {
  position: fixed;
  z-index: 1200;
  min-width: 180px;
  width: max-content;
  max-width: min(320px, calc(100vw - 16px));
  max-height: 280px;
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
.custom-select-search-badge {
  font-size: 10px;
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--color-bg-muted, #eee);
  color: var(--color-text-secondary);
  font-weight: 500;
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
