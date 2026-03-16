<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import type { Component } from 'vue'
import { Check } from 'lucide-vue-next'

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
  }>(),
  { placeholder: 'Select…', ariaLabel: 'Select option', triggerClass: '' }
)

const emit = defineEmits<{
  'update:modelValue': [value: string | number | null]
}>()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const highlightedIndex = ref(-1)

const selectedOption = computed(() =>
  props.options.find((o) => o.value === props.modelValue)
)
const displayLabel = computed(() => selectedOption.value?.label ?? props.placeholder)

function open() {
  isOpen.value = true
  highlightedIndex.value = props.options.findIndex((o) => o.value === props.modelValue)
  if (highlightedIndex.value < 0) highlightedIndex.value = 0
}
function close() {
  isOpen.value = false
  highlightedIndex.value = -1
}
function select(opt: CustomSelectOption) {
  emit('update:modelValue', opt.value)
  close()
}

function onTriggerKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    if (isOpen.value) {
      const opt = props.options[highlightedIndex.value]
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
    else highlightedIndex.value = Math.min(highlightedIndex.value + 1, props.options.length - 1)
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
    highlightedIndex.value = Math.min(highlightedIndex.value + 1, props.options.length - 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightedIndex.value = Math.max(highlightedIndex.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const opt = props.options[highlightedIndex.value]
    if (opt) select(opt)
    return
  }
  // Linear 风格：数字键直接选对应 shortcut 的选项
  if (e.key >= '1' && e.key <= '9') {
    const opt = props.options.find((o) => o.shortcut === e.key)
    if (opt) {
      e.preventDefault()
      select(opt)
    }
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

watch(isOpen, (open) => {
  if (open) {
    setTimeout(() => listRef.value?.focus(), 0)
  }
})

onMounted(() => document.addEventListener('click', handleClickOutside))
onUnmounted(() => document.removeEventListener('click', handleClickOutside))
</script>

<template>
  <div class="custom-select">
    <button
      ref="triggerRef"
      type="button"
      class="custom-select-trigger"
      :class="triggerClass"
      :aria-label="ariaLabel"
      :aria-expanded="isOpen"
      :aria-haspopup="'listbox'"
      aria-controls="custom-select-list"
      @click="isOpen ? close() : open()"
      @keydown="onTriggerKeydown"
    >
      <span v-if="selectedOption?.icon" class="trigger-icon">
        <component :is="selectedOption.icon" :size="16" />
      </span>
      <span class="trigger-label">{{ displayLabel }}</span>
      <span class="trigger-chevron" aria-hidden="true">▼</span>
    </button>
    <div
      v-show="isOpen"
      id="custom-select-list"
      ref="listRef"
      class="custom-select-list"
      role="listbox"
      tabindex="-1"
      :aria-activedescendant="
        options[highlightedIndex] != null ? `opt-${options[highlightedIndex]!.value}` : undefined
      "
      @keydown="onListKeydown"
    >
      <div v-if="searchPlaceholder" class="custom-select-search">
        <input
          type="text"
          class="custom-select-search-input"
          :placeholder="searchPlaceholder"
          readonly
          tabindex="-1"
          aria-label="Search"
        />
        <kbd v-if="searchShortcutBadge" class="custom-select-search-badge">{{ searchShortcutBadge }}</kbd>
      </div>
      <button
        v-for="(opt, i) in options"
        :id="`opt-${opt.value}`"
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
  position: absolute;
  z-index: 1000;
  top: calc(100% + 4px);
  left: 0;
  min-width: 100%;
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
