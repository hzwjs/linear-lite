<script setup lang="ts">
import { ref, watch, shallowRef } from 'vue'
import { applyReactInVue } from 'veaury'
import BlockNoteEditorReact from './BlockNoteEditorReact'
import type { EditorApi } from './BlockNoteEditorReact'
import { uploadApi } from '../services/api/upload'

const BlockNoteVue = applyReactInVue(BlockNoteEditorReact)

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    minHeight?: number
    mentionMembers?: Array<{ id: number; label: string }>
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 120,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'upload-state-change': [state: { hasPending: boolean; hasFailed: boolean }]
  blur: []
  ready: []
}>()

const editorApi = shallowRef<EditorApi | null>(null)
// Track the current JSON value to avoid echo-loops in the watch
const internalValue = ref(props.modelValue)

async function handleUploadFile(file: File): Promise<string> {
  const res = await uploadApi.uploadImage(file)
  return res.url
}

function handleChange(jsonString: string) {
  internalValue.value = jsonString
  emit('update:modelValue', jsonString)
  // BlockNote handles uploads internally; no separate pending/failed tracking needed.
  emit('upload-state-change', { hasPending: false, hasFailed: false })
}

function handleBlur() {
  emit('blur')
}

function handleInit(api: EditorApi) {
  editorApi.value = api
  emit('ready')
}

// Keep editor in sync when modelValue changes externally (e.g. draft restore).
// Since BlockNote is uncontrolled, we can only detect content-reset scenarios.
// A full reset is signalled when modelValue becomes empty or is completely different
// from what the editor last emitted. BlockNote does not have a simple "setContent"
// API for React hooks, so we key the component to force remount on value reset.
const editorKey = ref(0)

watch(
  () => props.modelValue,
  (newVal) => {
    if (newVal === internalValue.value) return
    // External value differs from what we last emitted – remount the editor
    internalValue.value = newVal
    editorKey.value++
    editorApi.value = null
  }
)

// Public API (matches TiptapEditor.vue interface)
function focus() {
  editorApi.value?.focus()
}

function getMentionedUserIdsFromDoc(): number[] {
  return editorApi.value?.getMentionedUserIds() ?? []
}

defineExpose({ focus, getMentionedUserIdsFromDoc })
</script>

<template>
  <div
    class="blocknote-editor-wrap"
    :style="{ minHeight: `${minHeight}px` }"
  >
    <BlockNoteVue
      :key="editorKey"
      :initialContent="internalValue"
      :placeholder="placeholder"
      :mentionMembers="mentionMembers"
      :uploadFile="handleUploadFile"
      :onChange="handleChange"
      :onBlur="handleBlur"
      :onInit="handleInit"
    />
  </div>
</template>

<style scoped>
.blocknote-editor-wrap {
  background: var(--color-bg-subtle);
  cursor: text;
  position: relative;
}

/* Mention chip styling to match the project design */
.blocknote-editor-wrap :deep(.bn-mention) {
  border-radius: 4px;
  padding: 0 3px;
  background: color-mix(in srgb, var(--color-accent, #5e6ad2) 18%, transparent);
  color: var(--color-accent, #5e6ad2);
  font-weight: 500;
}

/* Integrate BlockNote editor into the page's font/color scheme */
.blocknote-editor-wrap :deep(.bn-editor) {
  color: var(--color-text-primary);
  font-size: var(--font-size-body);
  padding: 0;
  background: transparent;
}

.blocknote-editor-wrap :deep(.bn-block-outer:first-child > .bn-block > .bn-block-content) {
  margin-top: 0;
}
</style>
