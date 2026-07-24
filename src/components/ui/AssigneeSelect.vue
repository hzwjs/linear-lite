<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { Check, Search, UserRound } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import type { User } from '../../types/domain'
import { getAvatarColorByUsername } from '../../utils/avatar'

type AssigneeValue = string | number | null
type AssigneeOption = {
  value: '' | number
  label: string
  user?: User
}

const props = withDefaults(
  defineProps<{
    modelValue: AssigneeValue
    users: User[]
    placeholder?: string
    ariaLabel?: string
    triggerClass?: string
    externalLabel?: string
    disabled?: boolean
    variant?: 'default' | 'compact'
    triggerMode?: 'full' | 'avatar'
    tooltip?: string
  }>(),
  {
    placeholder: '',
    ariaLabel: '',
    triggerClass: '',
    externalLabel: '',
    disabled: false,
    variant: 'default',
    triggerMode: 'full',
    tooltip: ''
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: '' | number]
  'open-change': [open: boolean]
}>()

const { t } = useI18n()
const rootRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLButtonElement | null>(null)
const popoverRef = ref<HTMLElement | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const isOpen = ref(false)
const query = ref('')
const highlightedIndex = ref(0)
const popoverStyle = ref({ top: '0px', left: '0px' })
const listboxId = `${useId()}-assignee-listbox`

const normalizedUsers = computed(() =>
  props.users.filter(
    (user, index, users) =>
      Number.isFinite(user?.id) &&
      !!user?.username?.trim() &&
      users.findIndex((candidate) => candidate.id === user.id) === index
  )
)

const selectedId = computed<number | null>(() => {
  if (props.modelValue === '' || props.modelValue == null) return null
  const value = Number(props.modelValue)
  return Number.isFinite(value) ? value : null
})

const selectedUser = computed(() =>
  selectedId.value == null
    ? undefined
    : normalizedUsers.value.find((user) => user.id === selectedId.value)
)

const displayLabel = computed(() =>
  selectedUser.value?.username?.trim() ||
  props.externalLabel.trim() ||
  props.placeholder ||
  t('common.unassigned')
)

const allOptions = computed<AssigneeOption[]>(() => [
  { value: '', label: t('common.unassigned') },
  ...normalizedUsers.value.map((user) => ({
    value: user.id,
    label: user.username.trim(),
    user
  }))
])

const visibleOptions = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return allOptions.value
  return allOptions.value.filter((option) => option.label.toLocaleLowerCase().includes(keyword))
})

function optionIsSelected(option: AssigneeOption): boolean {
  if (option.value === '') return selectedId.value == null && !props.externalLabel.trim()
  return option.value === selectedId.value
}

function avatarInitial(label: string): string {
  return label.trim().slice(0, 1).toLocaleUpperCase()
}

function avatarStyle(user?: User): { background: string; color: string } | undefined {
  return user ? getAvatarColorByUsername(user.username) : undefined
}

function syncHighlight() {
  const selectedIndex = visibleOptions.value.findIndex(optionIsSelected)
  highlightedIndex.value = selectedIndex >= 0 ? selectedIndex : 0
}

function updatePopoverPosition() {
  if (!isOpen.value || !triggerRef.value || !popoverRef.value) return
  const triggerRect = triggerRef.value.getBoundingClientRect()
  const popoverRect = popoverRef.value.getBoundingClientRect()
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const inset = 8
  const gap = 4
  const width = popoverRect.width || 260
  const height = popoverRect.height || 280
  let left = triggerRect.left
  let top = triggerRect.bottom + gap

  if (left + width > viewportWidth - inset) left = triggerRect.right - width
  if (top + height > viewportHeight - inset && triggerRect.top > height + inset) {
    top = triggerRect.top - height - gap
  }

  popoverStyle.value = {
    left: `${Math.max(inset, Math.min(left, viewportWidth - width - inset))}px`,
    top: `${Math.max(inset, Math.min(top, viewportHeight - height - inset))}px`
  }
}

function open() {
  if (props.disabled || isOpen.value) return
  query.value = ''
  isOpen.value = true
  emit('open-change', true)
  nextTick(() => {
    syncHighlight()
    updatePopoverPosition()
    searchInputRef.value?.focus()
  })
}

function close(options?: { restoreFocus?: boolean }) {
  if (!isOpen.value) return
  isOpen.value = false
  query.value = ''
  emit('open-change', false)
  if (options?.restoreFocus) nextTick(() => triggerRef.value?.focus())
}

function selectOption(option: AssigneeOption) {
  if (!optionIsSelected(option)) emit('update:modelValue', option.value)
  close({ restoreFocus: true })
}

function moveHighlight(delta: number) {
  const lastIndex = visibleOptions.value.length - 1
  if (lastIndex < 0) return
  highlightedIndex.value = Math.min(Math.max(highlightedIndex.value + delta, 0), lastIndex)
}

function onTriggerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' || event.key === ' ' || event.key === 'ArrowDown') {
    event.preventDefault()
    open()
  }
}

function onSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close({ restoreFocus: true })
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveHighlight(1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveHighlight(-1)
    return
  }
  if (event.key === 'Enter') {
    const option = visibleOptions.value[highlightedIndex.value]
    if (!option) return
    event.preventDefault()
    selectOption(option)
  }
}

function handlePointerDown(event: MouseEvent) {
  const target = event.target as Node
  if (!isOpen.value || rootRef.value?.contains(target) || popoverRef.value?.contains(target)) return
  close()
}

function handleViewportChange() {
  if (isOpen.value) updatePopoverPosition()
}

watch(query, syncHighlight)
watch(() => props.modelValue, () => {
  if (isOpen.value) syncHighlight()
})

onMounted(() => {
  document.addEventListener('mousedown', handlePointerDown)
  window.addEventListener('resize', handleViewportChange)
  window.addEventListener('scroll', handleViewportChange, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handlePointerDown)
  window.removeEventListener('resize', handleViewportChange)
  window.removeEventListener('scroll', handleViewportChange, true)
})
</script>

<template>
  <div
    ref="rootRef"
    class="assignee-select"
    :class="[
      `assignee-select--${variant}`,
      `assignee-select--${triggerMode}`,
      { 'assignee-select--open': isOpen }
    ]"
  >
    <button
      ref="triggerRef"
      type="button"
      class="assignee-trigger"
      :class="triggerClass"
      :disabled="disabled"
      :aria-label="ariaLabel || t('common.assignee')"
      :aria-expanded="isOpen"
      aria-haspopup="listbox"
      :aria-controls="listboxId"
      :title="tooltip || undefined"
      @click="isOpen ? close() : open()"
      @keydown="onTriggerKeydown"
    >
      <span
        v-if="selectedUser || externalLabel.trim()"
        class="assignee-avatar"
        :style="avatarStyle(selectedUser)"
        aria-hidden="true"
      >
        <img
          v-if="selectedUser?.avatar_url"
          :src="selectedUser.avatar_url"
          alt=""
          class="assignee-avatar-image"
        />
        <span v-else>{{ avatarInitial(displayLabel) }}</span>
      </span>
      <UserRound v-else class="assignee-trigger-icon" aria-hidden="true" />
      <span v-if="triggerMode === 'full'" class="assignee-trigger-label">{{ displayLabel }}</span>
    </button>

    <Teleport to="body">
      <div
        v-show="isOpen"
        ref="popoverRef"
        class="assignee-popover"
        :style="popoverStyle"
        role="dialog"
        :aria-label="t('common.assignee')"
      >
      <label class="assignee-search">
        <Search class="assignee-search-icon" aria-hidden="true" />
        <input
          ref="searchInputRef"
          v-model="query"
          type="search"
          class="assignee-search-input"
          :placeholder="t('assigneeSelect.searchPlaceholder')"
          :aria-label="t('assigneeSelect.searchPlaceholder')"
          autocomplete="off"
          @keydown="onSearchKeydown"
        />
      </label>

      <div :id="listboxId" class="assignee-options" role="listbox" :aria-label="t('common.assignee')">
        <button
          v-for="(option, index) in visibleOptions"
          :key="String(option.value)"
          type="button"
          class="assignee-option"
          :class="{
            'assignee-option--highlighted': index === highlightedIndex,
            'assignee-option--selected': optionIsSelected(option)
          }"
          role="option"
          :aria-selected="optionIsSelected(option)"
          @mouseenter="highlightedIndex = index"
          @click="selectOption(option)"
        >
          <span
            v-if="option.user"
            class="assignee-avatar"
            :style="avatarStyle(option.user)"
            aria-hidden="true"
          >
            <img
              v-if="option.user.avatar_url"
              :src="option.user.avatar_url"
              alt=""
              class="assignee-avatar-image"
            />
            <span v-else>{{ avatarInitial(option.label) }}</span>
          </span>
          <span v-else class="assignee-avatar assignee-avatar--neutral" aria-hidden="true">
            <UserRound />
          </span>
          <span class="assignee-option-label">{{ option.label }}</span>
          <Check v-if="optionIsSelected(option)" class="assignee-option-check" aria-hidden="true" />
        </button>
        <p v-if="visibleOptions.length === 0" class="assignee-empty" role="status">
          {{ t('assigneeSelect.noResults') }}
        </p>
      </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.assignee-select {
  position: relative;
  display: inline-flex;
  min-width: 0;
}
.assignee-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--font-size-caption);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}
.assignee-trigger:hover,
.assignee-trigger:focus-visible,
.assignee-select--open .assignee-trigger {
  border-color: var(--color-border);
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.assignee-trigger:focus-visible {
  outline: 2px solid var(--color-accent-muted-border);
  outline-offset: 1px;
}
.assignee-trigger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.assignee-select--default,
.assignee-select--default .assignee-trigger {
  width: 100%;
}
.assignee-select--compact .assignee-trigger {
  min-height: 28px;
  padding: 2px 7px;
  border-color: var(--color-border-subtle);
  border-radius: var(--radius-full);
}
.assignee-select--avatar .assignee-trigger {
  width: 26px;
  min-width: 26px;
  min-height: 26px;
  height: 26px;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
}
.assignee-select--avatar .assignee-trigger:hover,
.assignee-select--avatar.assignee-select--open .assignee-trigger {
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-accent) 10%, transparent);
}
.assignee-trigger-icon {
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
}
.assignee-trigger-label,
.assignee-option-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assignee-trigger-label {
  flex: 1;
  text-align: left;
}
.assignee-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  overflow: hidden;
  border-radius: var(--radius-full);
  background: var(--color-accent-muted);
  color: var(--color-accent);
  font-size: 9px;
  font-weight: var(--font-weight-semibold);
  letter-spacing: 0;
}
.assignee-avatar > svg {
  width: 14px;
  height: 14px;
}
.assignee-avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.assignee-avatar--neutral { background: var(--color-bg-muted); color: var(--color-text-muted); }
.assignee-popover {
  position: fixed;
  z-index: 1200;
  width: 260px;
  max-height: min(320px, calc(100vh - 16px));
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-base);
  box-shadow: var(--shadow-popover);
}
.assignee-search {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 4px 9px;
  border-bottom: 1px solid var(--color-border-subtle);
}
.assignee-search-icon {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  color: var(--color-text-muted);
}
.assignee-search-input {
  width: 100%;
  min-width: 0;
  padding: 4px 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--color-text-primary);
  font: inherit;
  font-size: var(--font-size-caption);
}
.assignee-search-input::placeholder {
  color: var(--color-text-muted);
}
.assignee-search-input::-webkit-search-cancel-button {
  display: none;
}
.assignee-options {
  max-height: 276px;
  overflow-y: auto;
  padding: 4px;
}
.assignee-option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 34px;
  padding: 5px 7px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--font-size-caption);
  text-align: left;
  cursor: pointer;
}
.assignee-option--highlighted,
.assignee-option:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.assignee-option-label {
  flex: 1;
}
.assignee-option-check {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
  color: var(--color-text-primary);
}
.assignee-empty {
  margin: 0;
  padding: 18px 10px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  text-align: center;
}
</style>
