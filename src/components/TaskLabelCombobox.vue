<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Check, Plus, Tag, X } from 'lucide-vue-next'
import { projectApi } from '../services/api/project'

type LabelOption = { id: number; name: string }
type SelectedLabel = { id?: number; name: string }

const props = defineProps<{
  modelValue: string
  labels: SelectedLabel[]
  projectId: number | null
  disabled: boolean
  taskId: string | null
  placeholder: string
  ariaLabel: string
  removeLabelAriaLabel: string
  deleteDefinitionAriaLabel: string
  noMatchesText: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  openChange: [open: boolean]
  pick: [label: LabelOption]
  create: [name: string]
  remove: [index: number]
  deleteLabelDefinition: [labelId: number]
}>()

const open = ref(false)
const loading = ref(false)
const suggestions = ref<LabelOption[]>([])
const activeIndex = ref(-1)
const inputRef = ref<HTMLInputElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)
let suggestTimer: ReturnType<typeof setTimeout> | null = null
let typingEpoch = 0

const normalizedQuery = computed(() => props.modelValue.trim().toLowerCase())

const visibleSuggestions = computed(() => {
  const query = normalizedQuery.value
  const byKey = new Map<string, SelectedLabel>()
  for (const label of suggestions.value) byKey.set(`id:${label.id}`, label)
  for (const label of props.labels) {
    const key = label.id != null ? `id:${label.id}` : `name:${label.name.trim().toLowerCase()}`
    if (!byKey.has(key)) byKey.set(key, label)
  }

  return [...byKey.values()]
    .filter((label) => !query || label.name.toLowerCase().includes(query))
    .sort((a, b) => {
      return Number(isSelected(b)) - Number(isSelected(a))
    })
})

const canCreate = computed(() => {
  const query = props.modelValue.trim()
  if (!query) return false
  const normalized = query.toLowerCase()
  return ![...suggestions.value, ...props.labels].some(
    (label) => label.name.trim().toLowerCase() === normalized
  )
})

function selectedIndex(label: { id?: number; name: string }): number {
  const normalized = label.name.trim().toLowerCase()
  return props.labels.findIndex(
    (selected) =>
      (label.id != null && selected.id === label.id) ||
      selected.name.trim().toLowerCase() === normalized
  )
}

function isSelected(label: { id?: number; name: string }): boolean {
  return selectedIndex(label) >= 0
}

function labelTone(label: { id?: number; name: string }): number {
  const source = `${label.id ?? ''}:${label.name}`
  let hash = 0
  for (let i = 0; i < source.length; i += 1) hash = (hash * 31 + source.charCodeAt(i)) | 0
  return Math.abs(hash) % 6
}

function setInput(value: string) {
  emit('update:modelValue', value)
}

function clearSuggestTimer() {
  if (!suggestTimer) return
  clearTimeout(suggestTimer)
  suggestTimer = null
}

async function fetchSuggestions() {
  const pid = props.projectId
  if (pid == null) {
    suggestions.value = []
    loading.value = false
    return
  }

  const requestEpoch = ++typingEpoch
  loading.value = true
  try {
    const query = props.modelValue.trim()
    const data = await projectApi.listLabels(pid, query || undefined)
    if (requestEpoch !== typingEpoch) return
    suggestions.value = data
  } catch {
    if (requestEpoch !== typingEpoch) return
    suggestions.value = []
  } finally {
    if (requestEpoch === typingEpoch) loading.value = false
  }
}

function focusInput() {
  nextTick(() => inputRef.value?.focus())
}

function openPicker() {
  if (props.disabled) return
  open.value = true
  emit('openChange', true)
  activeIndex.value = -1
  void fetchSuggestions()
  focusInput()
}

function closePicker() {
  if (!open.value) return
  open.value = false
  emit('openChange', false)
  activeIndex.value = -1
  clearSuggestTimer()
  typingEpoch += 1
  setInput('')
}

function togglePicker() {
  if (open.value) closePicker()
  else openPicker()
}

function scheduleSuggestions() {
  clearSuggestTimer()
  suggestTimer = setTimeout(() => {
    suggestTimer = null
    void fetchSuggestions()
  }, 160)
}

function onSearchInput(value: string) {
  setInput(value)
  activeIndex.value = -1
  scheduleSuggestions()
}

async function toggleSuggestion(suggestion: SelectedLabel) {
  const index = selectedIndex(suggestion)
  if (index >= 0) emit('remove', index)
  else if (suggestion.id != null) emit('pick', { id: suggestion.id, name: suggestion.name })
  setInput('')
  activeIndex.value = -1
  await nextTick()
  inputRef.value?.focus()
}

async function createCurrentLabel() {
  const name = props.modelValue.trim()
  if (!name || !canCreate.value) return
  emit('create', name)
  setInput('')
  activeIndex.value = -1
  await nextTick()
  inputRef.value?.focus()
}

function moveActive(delta: number) {
  const itemCount = visibleSuggestions.value.length + Number(canCreate.value)
  if (!itemCount) return
  activeIndex.value = (activeIndex.value + delta + itemCount) % itemCount
}

function commitActive() {
  const suggestion = visibleSuggestions.value[activeIndex.value]
  if (suggestion) {
    void toggleSuggestion(suggestion)
    return
  }
  if (canCreate.value) void createCurrentLabel()
}

function isWithinCombobox(target: EventTarget | null): boolean {
  return target instanceof Node && !!rootRef.value?.contains(target)
}

function handleDocumentFocusIn(event: FocusEvent) {
  if (!isWithinCombobox(event.target)) closePicker()
}

function handleDocumentMouseDown(event: MouseEvent) {
  if (!isWithinCombobox(event.target)) closePicker()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) closePicker()
  }
)

watch(
  () => props.projectId,
  (projectId) => {
    if (projectId == null) {
      suggestions.value = []
      closePicker()
    } else if (open.value) {
      void fetchSuggestions()
    }
  }
)

watch(
  () => props.taskId,
  () => {
    suggestions.value = []
    closePicker()
  }
)

onMounted(() => {
  document.addEventListener('focusin', handleDocumentFocusIn)
  document.addEventListener('mousedown', handleDocumentMouseDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('focusin', handleDocumentFocusIn)
  document.removeEventListener('mousedown', handleDocumentMouseDown)
  clearSuggestTimer()
})

function removeFromSuggestions(labelId: number) {
  suggestions.value = suggestions.value.filter((label) => label.id !== labelId)
}

function requestDeleteLabelDefinition(labelId: number | undefined) {
  if (labelId != null) emit('deleteLabelDefinition', labelId)
}

defineExpose({ removeFromSuggestions })
</script>

<template>
  <div ref="rootRef" class="task-label-combobox">
    <button
      type="button"
      class="label-trigger"
      :class="{ 'label-trigger--open': open }"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      aria-haspopup="listbox"
      @click="togglePicker"
    >
      <Tag
        v-if="labels.length === 0"
        class="label-trigger-icon"
        :stroke-width="1.75"
        aria-hidden="true"
      />
      <span v-if="labels.length === 0" class="label-trigger-placeholder">{{ placeholder }}</span>
      <span v-else class="label-trigger-values">
        <span
          v-for="(label, index) in labels"
          :key="label.id ?? `temporary-${index}-${label.name}`"
          class="label-chip"
          :class="`label-tone-${labelTone(label)}`"
        >
          <span class="label-dot" aria-hidden="true" />
          <span class="label-chip-name">{{ label.name }}</span>
        </span>
      </span>
    </button>

    <div v-if="open" class="label-popover">
      <div class="label-search-row">
        <input
          ref="inputRef"
          :value="modelValue"
          type="search"
          class="label-search-input"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          :placeholder="placeholder"
          :aria-label="ariaLabel"
          @input="onSearchInput(($event.target as HTMLInputElement).value)"
          @keydown.down.prevent="moveActive(1)"
          @keydown.up.prevent="moveActive(-1)"
          @keydown.enter.prevent="commitActive"
          @keydown.escape.prevent.stop="closePicker"
        />
        <kbd class="label-shortcut" aria-hidden="true">L</kbd>
      </div>

      <div v-if="loading && visibleSuggestions.length === 0" class="label-empty" role="status">…</div>
      <ul v-else class="label-option-list" role="listbox" :aria-label="ariaLabel">
        <li
          v-for="(suggestion, index) in visibleSuggestions"
          :key="suggestion.id ?? `temporary-${suggestion.name}`"
          class="label-option"
          :class="{ 'label-option--active': activeIndex === index }"
        >
          <button
            type="button"
            class="label-option-main"
            role="option"
            :aria-selected="isSelected(suggestion)"
            @click="toggleSuggestion(suggestion)"
          >
            <span class="label-option-check" :class="{ 'label-option-check--selected': isSelected(suggestion) }">
              <Check v-if="isSelected(suggestion)" class="icon-12" aria-hidden="true" />
            </span>
            <span class="label-dot" :class="`label-tone-${labelTone(suggestion)}`" aria-hidden="true" />
            <span class="label-option-name">{{ suggestion.name }}</span>
          </button>
          <button
            v-if="suggestion.id != null"
            type="button"
            class="label-definition-delete"
            :aria-label="`${deleteDefinitionAriaLabel}: ${suggestion.name}`"
            @click.stop="requestDeleteLabelDefinition(suggestion.id)"
          >
            <X class="icon-12" aria-hidden="true" />
          </button>
        </li>

        <li v-if="canCreate" class="label-option label-option--create" :class="{ 'label-option--active': activeIndex === visibleSuggestions.length }">
          <button type="button" class="label-option-main" @click="createCurrentLabel">
            <Plus class="icon-14" aria-hidden="true" />
            <span class="label-option-name">{{ placeholder }} “{{ modelValue.trim() }}”</span>
          </button>
        </li>

        <li v-if="visibleSuggestions.length === 0 && !canCreate" class="label-empty">
          {{ noMatchesText }}
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.task-label-combobox {
  position: relative;
  width: 100%;
}

.label-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 32px;
  padding: 3px 6px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  text-align: left;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.label-trigger:hover,
.label-trigger:focus-visible {
  border-color: var(--color-border);
  background: var(--color-bg-hover);
}

.label-trigger--open {
  border-color: transparent;
  background: transparent;
}

.label-trigger:focus-visible {
  outline: 2px solid var(--color-border-strong);
  outline-offset: 1px;
}

.label-trigger:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.label-trigger-icon {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  width: 14px;
  height: 14px;
}

.icon-12 {
  width: 12px;
  height: 12px;
  flex: 0 0 12px;
}

.icon-14 {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
}

.label-trigger-placeholder {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.label-trigger-values {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.label-chip {
  --label-tone: var(--label-color-blue);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  min-height: 22px;
  padding: 2px 7px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  line-height: 1.2;
}

.label-chip-name,
.label-option-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.label-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: var(--radius-full);
  background: var(--label-tone, var(--label-color-blue));
}

.label-popover {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
  width: min(284px, 100%);
  min-width: 240px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-base);
  box-shadow: var(--shadow-popover);
}

.label-search-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.label-search-input {
  flex: 1;
  min-width: 0;
  min-height: 38px;
  padding: 7px 2px;
  border: none;
  background: transparent;
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  outline: none;
}

.label-search-input::placeholder {
  color: var(--color-text-muted);
}

.label-shortcut {
  min-width: 20px;
  padding: 2px 5px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  color: var(--color-text-muted);
  font: inherit;
  font-size: var(--font-size-xs);
  line-height: 1.2;
  text-align: center;
}

.label-option-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 240px;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  list-style: none;
}

.label-option {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  border-radius: var(--radius-sm);
}

.label-option:hover,
.label-option--active {
  background: var(--color-bg-hover);
}

.label-option-main {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 32px;
  padding: 5px 32px 5px 6px;
  border: none;
  background: transparent;
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  text-align: left;
}

.label-option-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-on-accent);
}

.label-option-check--selected {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.label-definition-delete {
  position: absolute;
  right: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  min-height: 0;
  padding: 0;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  opacity: 0;
}

.label-option:hover .label-definition-delete,
.label-definition-delete:focus-visible {
  opacity: 1;
}

.label-definition-delete:hover {
  background: var(--color-bg-active);
  color: var(--color-danger);
}

.label-option--create .label-option-main {
  padding-right: 6px;
  color: var(--color-text-secondary);
}

.label-empty {
  padding: 16px 10px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  text-align: center;
}

.label-tone-0 { --label-tone: var(--label-color-red); }
.label-tone-1 { --label-tone: var(--label-color-violet); }
.label-tone-2 { --label-tone: var(--label-color-blue); }
.label-tone-3 { --label-tone: var(--label-color-cyan); }
.label-tone-4 { --label-tone: var(--label-color-green); }
.label-tone-5 { --label-tone: var(--label-color-amber); }
</style>
