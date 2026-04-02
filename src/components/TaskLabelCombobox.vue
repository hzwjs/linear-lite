<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Tag } from 'lucide-vue-next'
import { projectApi } from '../services/api/project'

const props = defineProps<{
  modelValue: string
  labels: { id?: number; name: string }[]
  projectId: number | null
  disabled: boolean
  sidebarRoot: HTMLElement | null
  taskId: string | null
  placeholder: string
  ariaLabel: string
  /** 输入区内已选胶囊：仅从当前任务移除 */
  removeLabelAriaLabel: string
  /** 联想列表内：永久删除标签定义 */
  deleteDefinitionAriaLabel: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  pick: [label: { id: number; name: string }]
  create: [name: string]
  remove: [index: number]
  deleteLabelDefinition: [labelId: number]
}>()

const open = ref(false)
const suggestions = ref<{ id: number; name: string }[]>([])
const inputRef = ref<HTMLInputElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)
let suggestTimer: ReturnType<typeof setTimeout> | null = null
let typingEpoch = 0

function close() {
  if (!open.value) return
  open.value = false
}

function setInput(value: string) {
  emit('update:modelValue', value)
}

function focusInput() {
  nextTick(() => inputRef.value?.focus())
}

function clearSuggestTimer() {
  if (!suggestTimer) return
  clearTimeout(suggestTimer)
  suggestTimer = null
}

function labelNameTaken(name: string): boolean {
  const normalized = name.trim().toLowerCase()
  if (!normalized) return true
  return props.labels.some((chip) => chip.name.trim().toLowerCase() === normalized)
}

async function fetchSuggestions(fromTypingDebounce = false) {
  const pid = props.projectId
  if (pid == null) {
    if (!open.value) suggestions.value = []
    return
  }
  const typingGen = fromTypingDebounce ? ++typingEpoch : -1
  try {
    const q = props.modelValue.trim()
    const data = await projectApi.listLabels(pid, q || undefined)
    if (fromTypingDebounce && typingGen !== typingEpoch) return
    suggestions.value = data
  } catch {
    if (fromTypingDebounce && typingGen !== typingEpoch) return
    if (!open.value) suggestions.value = []
  }
}

function openSuggestions() {
  if (props.disabled) return
  open.value = true
  void fetchSuggestions(false)
}

function openSuggestionsFromContainer() {
  if (props.disabled) return
  open.value = true
  focusInput()
  void fetchSuggestions(false)
}

function scheduleSuggestions() {
  clearSuggestTimer()
  suggestTimer = setTimeout(() => {
    suggestTimer = null
    void fetchSuggestions(true)
  }, 200)
}

function onInput() {
  if (props.disabled) return
  open.value = true
  scheduleSuggestions()
}

function onBlur() {
  clearSuggestTimer()
  typingEpoch++
}

function pickSuggestion(suggestion: { id: number; name: string }) {
  if (!labelNameTaken(suggestion.name)) emit('pick', suggestion)
  setInput('')
  close()
}

function commitCurrentInput() {
  const raw = props.modelValue.trim()
  if (raw) {
    if (!labelNameTaken(raw)) emit('create', raw)
    setInput('')
    close()
    return
  }
  const first = suggestions.value[0]
  if (first) pickSuggestion(first)
}

function isWithinCombobox(target: EventTarget | null): boolean {
  const node = target instanceof Node ? target : null
  if (!node) return false
  return !!rootRef.value?.contains(node)
}

function handleDocumentFocusIn(event: FocusEvent) {
  if (isWithinCombobox(event.target)) return
  close()
}

function handleDocumentMouseDown(event: MouseEvent) {
  if (isWithinCombobox(event.target)) return
  close()
}

watch(
  () => props.disabled,
  (disabled) => {
    if (!disabled) return
    clearSuggestTimer()
    typingEpoch++
    suggestions.value = []
    close()
  }
)

watch(
  () => props.projectId,
  (projectId) => {
    if (projectId == null) {
      suggestions.value = []
      close()
      return
    }
    if (open.value) void fetchSuggestions(false)
  }
)

watch(
  () => props.labels,
  () => {
    if (open.value) void fetchSuggestions(false)
  },
  { deep: true }
)

watch(
  () => props.taskId,
  () => {
    clearSuggestTimer()
    typingEpoch++
    suggestions.value = []
    setInput('')
    close()
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

/** 删除定义成功后由父组件调用，从当前打开的联想列表中去掉该项 */
function removeFromSuggestions(labelId: number) {
  suggestions.value = suggestions.value.filter((l) => l.id !== labelId)
  if (suggestions.value.length === 0) close()
}

defineExpose({
  removeFromSuggestions
})
</script>

<template>
  <div ref="rootRef" class="task-label-combobox">
    <div class="prop-label-editor" @click="openSuggestionsFromContainer">
      <Tag class="icon-14 prop-label-icon" aria-hidden="true" />
      <div class="prop-label-chips-wrap">
        <span
          v-for="(chip, idx) in labels"
          :key="chip.id ?? `tmp-${idx}-${chip.name}`"
          class="label-pill"
        >
          <span class="label-pill-text">{{ chip.name }}</span>
          <span class="label-pill-sep" aria-hidden="true" />
          <button
            v-if="!disabled"
            type="button"
            class="label-pill-delete"
            :aria-label="removeLabelAriaLabel"
            @click.stop="emit('remove', idx)"
          >
            ×
          </button>
        </span>
        <input
          ref="inputRef"
          :value="modelValue"
          type="text"
          class="prop-label-input"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          :placeholder="placeholder"
          :disabled="disabled"
          :aria-label="ariaLabel"
          :aria-expanded="open && suggestions.length > 0"
          aria-haspopup="listbox"
          @focus="openSuggestions"
          @blur="onBlur"
          @input="setInput(($event.target as HTMLInputElement).value); onInput()"
          @keydown.enter.prevent="commitCurrentInput"
          @keydown.escape="close"
          @click.stop
        />
      </div>
    </div>
    <ul
      v-if="open && suggestions.length > 0"
      class="prop-label-suggestions"
      role="listbox"
      @mousedown.prevent
    >
      <li
        v-for="suggestion in suggestions"
        :key="suggestion.id"
        role="option"
        class="prop-label-suggestion-item"
      >
        <span class="label-pill">
          <button
            type="button"
            class="label-pill-main"
            @mousedown.prevent="pickSuggestion(suggestion)"
          >
            {{ suggestion.name }}
          </button>
          <span class="label-pill-sep" aria-hidden="true" />
          <button
            type="button"
            class="label-pill-delete"
            :aria-label="`${deleteDefinitionAriaLabel}: ${suggestion.name}`"
            @mousedown.stop.prevent="emit('deleteLabelDefinition', suggestion.id)"
          >
            ×
          </button>
        </span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.task-label-combobox {
  width: 100%;
}
.prop-label-editor {
  position: relative;
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 3px 7px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  background: var(--color-bg-muted);
  min-height: 30px;
  box-sizing: border-box;
}
.prop-label-icon {
  flex-shrink: 0;
  color: var(--color-text-muted);
}
.prop-label-chips-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 5px;
  row-gap: 4px;
}
.prop-label-chips-wrap .label-pill {
  background: var(--color-bg-base);
}
/* 输入区与联想项共用胶囊结构；下拉内与面板对比一致用 muted */
.label-pill {
  display: inline-flex;
  align-items: stretch;
  max-width: 100%;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-subtle);
  background: var(--color-bg-muted);
  overflow: hidden;
}
.label-pill-text {
  display: inline-flex;
  align-items: center;
  padding: 3px 2px 3px 8px;
  font-size: 11px;
  font-weight: var(--font-weight-medium, 500);
  line-height: 1.25;
  color: var(--color-text-primary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label-pill-main {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 11rem;
  margin: 0;
  padding: 3px 2px 3px 8px;
  border: none;
  background: transparent;
  font-size: 11px;
  font-weight: var(--font-weight-medium, 500);
  line-height: 1.25;
  color: var(--color-text-primary);
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label-pill-main:hover {
  background: var(--color-bg-hover);
}
.label-pill-sep {
  width: 1px;
  flex-shrink: 0;
  align-self: stretch;
  margin: 3px 0;
  background: var(--color-border-subtle);
}
.label-pill-delete {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  flex-shrink: 0;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1;
  transition:
    background var(--transition-fast),
    color var(--transition-fast);
}
.label-pill-delete:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.prop-label-input {
  flex: 1;
  min-width: 3.5rem;
  min-height: 20px;
  border: none;
  background: transparent;
  font-size: 11px;
  line-height: 1.35;
  color: var(--color-text-primary);
  padding: 1px 0;
  outline: none;
}
.prop-label-input::placeholder {
  color: var(--color-text-muted);
}
.prop-label-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.prop-label-suggestions {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 6px;
  list-style: none;
  margin: 4px 0 0;
  padding: 8px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-popover);
  max-height: 160px;
  overflow-y: auto;
  width: 100%;
  box-sizing: border-box;
}
.prop-label-suggestion-item {
  margin: 0;
  padding: 0;
  max-width: 100%;
}
</style>
