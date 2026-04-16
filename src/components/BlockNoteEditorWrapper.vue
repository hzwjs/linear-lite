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
    /** 仅任务描述等场景：块侧栏 + `/` 命令菜单 */
    blockChrome?: boolean
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 120,
    blockChrome: false,
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
    :class="{ 'blocknote-editor-wrap--chrome': blockChrome }"
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
      :blockChrome="blockChrome"
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
  background: transparent;
}

.blocknote-editor-wrap :deep(.bn-block-outer:first-child > .bn-block > .bn-block-content) {
  margin-top: 0;
}
</style>

<!-- Global (non-scoped): Veaury-bridged React elements may not carry Vue's scoped
     data attribute, so padding/side-menu rules that need to cross the bridge boundary
     are placed here instead of in the scoped block. -->
<style>
/* ── Text alignment: only strip BlockNote's default 54px horizontal padding in the
      block-chrome (description) editor. Comment editors keep their own padding.
      The side menu renders via FloatingPortal (body-level) and positions itself
      using getBoundingClientRect — it does NOT depend on this padding. ── */
.blocknote-editor-wrap--chrome .bn-editor {
  padding-inline: 0 !important;
}

/* ── Typography: inherit project font; use 15px body (matches official example density,
      13px is too tight for a block editor content area) ── */
.bn-default-styles {
  font-family: inherit !important;
  font-size: 15px !important;
  line-height: 1.6 !important;
}

/* ── Block vertical spacing: give paragraphs visual breathing room like official demo ── */
.bn-block-outer {
  padding-block: 2px;
}

/* ── Placeholder: italic muted, matching official demo style ── */
.bn-editor .bn-block-content[data-is-empty-and-focused]::before,
.bn-editor .bn-block-content[data-is-placeholder-visible]::before {
  color: var(--color-text-muted, #aaa) !important;
  font-style: italic !important;
}

/* ── Side menu: FloatingPortal renders at body level — BlockNote manages show/hide
      internally; just style size, colour, and layout here ── */
.bn-side-menu {
  display: flex;
  align-items: center;
  gap: 2px;
}

/* ── Side menu button sizing and colour ──
      SideMenuButton renders as MantineActionIcon (mantine-ActionIcon-root) when it has
      an icon (drag handle), and MantineButton (mantine-Button-root) for the + button ── */
.bn-side-menu .mantine-ActionIcon-root,
.bn-side-menu .mantine-Button-root {
  width: 24px !important;
  height: 24px !important;
  min-width: 24px !important;
  min-height: 24px !important;
  padding: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  border-radius: 4px !important;
  color: var(--color-text-muted, #bbb) !important;
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  transition: color 0.1s, background 0.1s !important;
}

.bn-side-menu .mantine-ActionIcon-root:hover,
.bn-side-menu .mantine-Button-root:hover {
  color: var(--color-text-secondary, #555) !important;
  background: var(--color-bg-hover, rgba(0, 0, 0, 0.06)) !important;
}

.bn-side-menu .mantine-ActionIcon-root svg,
.bn-side-menu .mantine-Button-root svg {
  width: 16px;
  height: 16px;
  display: block;
}

/* ── Code block language selector (native <select> rendered by BlockNote) ── */
.bn-editor [data-content-type="codeBlock"] select {
  appearance: none;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 11px;
  font-family: inherit;
  padding: 2px 8px 2px 6px;
  margin: 8px 8px 4px;
  cursor: pointer;
  outline: none;
  transition: background 0.1s, border-color 0.1s;
}

.bn-editor [data-content-type="codeBlock"] select:hover {
  background: rgba(255, 255, 255, 0.18);
  border-color: rgba(255, 255, 255, 0.35);
  color: rgba(255, 255, 255, 0.95);
}

/* ── Slash menu: match project style ── */
.bn-suggestion-menu {
  border: 1px solid var(--color-border-subtle, #e8e8e8);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.10);
  background: var(--color-bg-base, #fff);
}

.bn-suggestion-menu-item:hover,
.bn-suggestion-menu-item[data-hovered="true"] {
  background: var(--color-bg-hover, rgba(0, 0, 0, 0.05));
}
</style>
