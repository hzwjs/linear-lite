<script setup lang="ts">
import { ref, watch, shallowRef } from 'vue'
import { applyReactInVue, setVeauryOptions } from 'veaury'
import { createRoot } from 'react-dom/client'
import 'photoswipe/style.css'
import BlockNoteEditorReact from './BlockNoteEditorReact'
import type { EditorApi } from './BlockNoteEditorReact'
import { uploadApi } from '../services/api/upload'

// 编辑器只在任务/文档编辑器真正打开时加载；将 veaury 初始化放到这里，避免首屏入口提前拉取 React 编辑器依赖。
setVeauryOptions({ react: { createRoot } })
const BlockNoteVue = applyReactInVue(BlockNoteEditorReact)

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    minHeight?: number
    mentionMembers?: Array<{ id: number; label: string }>
    mentionDocuments?: Array<{ id: number; title: string; projectId: number }>
    readonly?: boolean
    /** `@` 成员菜单：与 TaskRowAssigneePicker 一致的搜索框占位与空态文案（仅传 mentionMembers 时生效） */
    mentionMenuSearchPlaceholder?: string
    mentionMenuNoMatchesText?: string
    mentionMenuLoadingText?: string
    mentionMembersGroupText?: string
    mentionDocumentsGroupText?: string
    /** 仅任务描述等场景：块侧栏 + `/` 命令菜单 */
    blockChrome?: boolean
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 120,
    blockChrome: false,
    readonly: false,
    mentionMenuSearchPlaceholder: '',
    mentionMenuNoMatchesText: '',
    mentionMenuLoadingText: '',
    mentionMembersGroupText: '',
    mentionDocumentsGroupText: '',
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'upload-state-change': [state: { hasPending: boolean; hasFailed: boolean }]
  blur: []
  focus: []
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

function handleFocus() {
  emit('focus')
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

function focusAppend() {
  editorApi.value?.focusAppend()
}

function getMentionedUserIdsFromDoc(): number[] {
  return editorApi.value?.getMentionedUserIds() ?? []
}

function insertMention(userId: string, label: string) {
  editorApi.value?.insertMention(userId, label)
}

function handleSurfaceClick() {
  // Suppress if user just finished a drag-select
  if (window.getSelection()?.toString()) return
  focusAppend()
}

defineExpose({ focus, getMentionedUserIdsFromDoc, insertMention })
</script>

<template>
  <div
    class="blocknote-editor-wrap"
    :class="{ 'blocknote-editor-wrap--chrome': blockChrome }"
    :style="{ minHeight: `${minHeight}px` }"
    @click.self="!readonly && handleSurfaceClick()"
  >
    <BlockNoteVue
      :key="editorKey"
      :initialContent="internalValue"
      :placeholder="placeholder"
      :mentionMembers="mentionMembers"
      :mentionDocuments="mentionDocuments"
      :mentionMenuSearchPlaceholder="mentionMenuSearchPlaceholder"
      :mentionMenuNoMatchesText="mentionMenuNoMatchesText"
      :mentionMenuLoadingText="mentionMenuLoadingText"
      :mentionMembersGroupText="mentionMembersGroupText"
      :mentionDocumentsGroupText="mentionDocumentsGroupText"
      :editable="!readonly"
      :uploadFile="handleUploadFile"
      :onChange="handleChange"
      :onBlur="handleBlur"
      :onFocus="handleFocus"
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

/* 任务描述编辑器与外层 surface 融合，不使用独立的灰色编辑区背景。 */
.blocknote-editor-wrap--chrome {
  background: transparent;
}

/* Mention chip styling to match the project design */
.blocknote-editor-wrap :deep(.bn-mention) {
  border-radius: 4px;
  padding: 0 3px;
  background: color-mix(in srgb, var(--color-accent, #5e6ad2) 18%, transparent);
  color: var(--color-accent, #5e6ad2);
  font-weight: 500;
}

/* `@` 联合建议保持成员/文档两种语义可辨，且沿用现有浅色 Operate 表面。 */
.blocknote-editor-wrap :deep(.bn-structured-suggestion-group) {
  padding: 7px 8px 4px;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  text-transform: uppercase;
}

.blocknote-editor-wrap :deep(.bn-structured-suggestion-item) {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  text-align: left;
}

.blocknote-editor-wrap :deep(.bn-structured-suggestion-icon) {
  display: inline-flex;
  width: 24px;
  height: 24px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-semibold);
}

.blocknote-editor-wrap :deep(.bn-structured-suggestion-title) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.blocknote-editor-wrap :deep(.bn-structured-suggestion-empty) {
  padding: 12px;
  color: var(--color-text-muted);
  text-align: center;
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
     data attribute, so BlockNote rules that need to cross the bridge boundary
     are placed here instead of in the scoped block. -->
<style>
/* ── Text alignment: only strip BlockNote's default 54px horizontal padding in the
      block-chrome (description) editor. Comment editors keep their own padding.
      The description editor no longer renders BlockNote's side menu. ── */
/*
 * 任务描述 chrome：去默认水平 padding + 标题比例变量（相对 TaskEditor `--task-editor-issue-title-size`）
 * 标题覆写见下块（官方 Overriding CSS 思路）
 * https://www.blocknotejs.org/docs/react/styling-theming/overriding-css
 */
.blocknote-editor-wrap--chrome .bn-editor {
  padding-inline: 0 !important;
  --bn-desc-h1: calc(var(--task-editor-issue-title-size, 2rem) * 0.85);
  --bn-desc-h2: calc(var(--task-editor-issue-title-size, 2rem) * 0.72);
  --bn-desc-h3: calc(var(--task-editor-issue-title-size, 2rem) * 0.63);
  --bn-desc-h4: calc(var(--task-editor-issue-title-size, 2rem) * 0.55);
  --bn-desc-h5: calc(var(--task-editor-issue-title-size, 2rem) * 0.5);
  --bn-desc-h6: calc(var(--task-editor-issue-title-size, 2rem) * 0.46);
}

/* 覆写 BlockNote 的 --level 变量（Block.css 通过 font-size: var(--level) 作用于 bn-block-content 容器），
   使侧栏定位、光标、占位符三者共享同一字号基准，消除错位 */
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"]:not([data-level]),
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="1"] { --level: var(--bn-desc-h1); }
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="2"] { --level: var(--bn-desc-h2); }
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="3"] { --level: var(--bn-desc-h3); }
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="4"] { --level: var(--bn-desc-h4); }
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="5"] { --level: var(--bn-desc-h5); }
.blocknote-editor-wrap--chrome .bn-editor [data-content-type="heading"][data-level="6"] { --level: var(--bn-desc-h6); }

/* font-weight / line-height / letter-spacing 统一设于容器，内层 h* 继承即可 */
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"]:not([data-level]),
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="1"] {
  font-weight: 600 !important; line-height: 1.28 !important; letter-spacing: -0.03em !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="2"] {
  font-weight: 600 !important; line-height: 1.3 !important; letter-spacing: -0.025em !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="3"] {
  font-weight: 600 !important; line-height: 1.32 !important; letter-spacing: -0.02em !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="4"] {
  font-weight: 600 !important; line-height: 1.35 !important; letter-spacing: -0.015em !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="5"] {
  font-weight: 600 !important; line-height: 1.38 !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"][data-level="6"] {
  font-weight: 600 !important; line-height: 1.4 !important;
}

/* 重置浏览器 UA 默认的 h* 字号/间距，继承自容器 */
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"] :where(h1, h2, h3, h4, h5, h6),
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-content-type="heading"] .bn-inline-content {
  font-size: 1em !important;
  font-weight: inherit !important;
  line-height: inherit !important;
  letter-spacing: inherit !important;
  margin: 0 !important;
  padding: 0 !important;
}

.blocknote-editor-wrap--chrome .bn-image-preview-host {
  position: relative;
}

.blocknote-editor-wrap--chrome .bn-image-preview-target {
  cursor: zoom-in;
}

.blocknote-editor-wrap--chrome .bn-image-preview-button {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 6;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgba(15, 23, 42, 0.18);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.92);
  color: rgba(15, 23, 42, 0.78);
  font-size: 12px;
  font-weight: 500;
  line-height: 28px;
  cursor: zoom-in;
  opacity: 0.88;
  transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.16);
}

.blocknote-editor-wrap--chrome .bn-image-preview-host:hover > .bn-image-preview-button,
.blocknote-editor-wrap--chrome .bn-image-preview-button:focus-visible {
  opacity: 1;
}

.blocknote-editor-wrap--chrome .bn-image-preview-button:hover {
  background: #fff;
  color: rgba(15, 23, 42, 0.95);
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

/* ── Placeholder: 评论等保持弱提示；任务描述（block chrome）用次级字色提高可读性 ── */
.bn-editor .bn-block-content[data-is-empty-and-focused]::before,
.bn-editor .bn-block-content[data-is-placeholder-visible]::before {
  color: var(--color-text-muted, #aaa) !important;
  font-style: italic !important;
}
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-is-empty-and-focused]::before,
.blocknote-editor-wrap--chrome .bn-editor .bn-block-content[data-is-placeholder-visible]::before {
  color: var(--color-text-secondary, #6b6b6b) !important;
}

/* ── Table controls: task descriptions enable BlockNote table handles. Make the
      right/bottom extend buttons read as explicit add controls instead of thin
      resize strips, while keeping comment editors untouched. ── */
.bn-extend-button.bn-extend-button-add-remove-columns,
.bn-extend-button.bn-extend-button-add-remove-rows {
  z-index: 80 !important;
  color: var(--color-text-muted, #888) !important;
  background: var(--color-bg-base, #fff) !important;
  border: 1px solid var(--color-border, #ddd) !important;
  border-radius: 6px !important;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.12) !important;
}

.bn-extend-button.bn-extend-button-add-remove-columns {
  width: 24px !important;
  min-width: 24px !important;
  margin-left: 6px !important;
}

.bn-extend-button.bn-extend-button-add-remove-rows {
  height: 24px !important;
  min-height: 24px !important;
  margin-top: 6px !important;
}

.bn-extend-button.bn-extend-button-add-remove-columns:hover,
.bn-extend-button.bn-extend-button-add-remove-rows:hover,
.bn-extend-button.bn-extend-button-editing {
  color: var(--color-text-primary, #222) !important;
  background: var(--color-bg-hover, #f4f4f5) !important;
  border-color: var(--color-accent, #5e6ad2) !important;
}

.bn-table-handle-menu {
  z-index: 90 !important;
}

/* ── Mermaid code blocks: render diagrams in-place by default, and reveal the
      editable source only when the block is activated. ── */
.blocknote-editor-wrap--chrome
  .bn-block-outer:has(.bn-block-content[data-content-type="codeBlock"][data-language="mermaid"]) {
  margin: 4px 0;
  /* 图表高度异步落定时，让浏览器选取正文作为滚动锚点，避免视口跟随图表回跳。 */
  overflow-anchor: none;
}

.blocknote-editor-wrap--chrome
  .bn-block-content[data-content-type="codeBlock"][data-language="mermaid"] {
  height: var(--bn-mermaid-preview-height, clamp(180px, 34vw, 520px));
  overflow: hidden;
  visibility: hidden;
}

.blocknote-editor-wrap--chrome .bn-mermaid-editor-root {
  position: relative;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 3;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview {
  position: absolute;
  display: block;
  min-height: 96px;
  overflow-x: auto;
  padding: 12px;
  border: 1px solid var(--color-border-subtle, #e4e4e7);
  border-radius: 8px;
  background: var(--color-bg-base, #fff);
  color: inherit;
  cursor: pointer;
  pointer-events: auto;
  text-align: left;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview-zoom {
  position: absolute;
  z-index: 4;
  min-width: 44px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgba(15, 23, 42, 0.18);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.92);
  color: rgba(15, 23, 42, 0.78);
  font-size: 12px;
  font-weight: 500;
  line-height: 28px;
  cursor: zoom-in;
  opacity: 0.88;
  pointer-events: auto;
  transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.16);
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview-zoom:hover,
.blocknote-editor-wrap--chrome .bn-mermaid-preview-zoom:focus-visible {
  background: #fff;
  color: rgba(15, 23, 42, 0.95);
  opacity: 1;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview--source {
  display: none;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview svg {
  display: block;
  max-width: 100%;
  margin: 0 auto;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview--loading,
.blocknote-editor-wrap--chrome .bn-mermaid-preview--error {
  display: flex;
  align-items: center;
  color: var(--color-text-muted, #777);
  font-size: var(--font-size-sm, 13px);
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview--error {
  color: var(--color-danger, #b42318);
  background: color-mix(in srgb, var(--color-danger, #b42318) 7%, var(--color-bg-base, #fff));
  border-color: color-mix(in srgb, var(--color-danger, #b42318) 35%, var(--color-border-subtle, #e4e4e7));
  white-space: pre-wrap;
}

.blocknote-editor-wrap--chrome .bn-mermaid-preview--source {
  display: none;
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

/* ── Slash `/` 建议菜单（`@` 成员共用 MemberListDropdownPanel 带 .task-row-assignee-panel，不套此规则）
      BlockNote 0.48 item 结构：MantineGroup[role="option"].bn-suggestion-menu-item
        ├── .bn-mt-suggestion-menu-item-section[data-position="left"]  (icon + background)
        ├── .bn-mt-suggestion-menu-item-body > .bn-mt-suggestion-menu-item-title
        │                                    > .bn-mt-suggestion-menu-item-subtitle
        └── .bn-mt-suggestion-menu-item-section[data-position="right"] (badge)
      紧凑单行策略：隐藏 subtitle，item 高度固定 40px，直接对 MantineGroup 容器设样式 ── */
.bn-suggestion-menu:not(.task-row-assignee-panel) {
  width: min(260px, calc(100vw - 32px)) !important;
  min-width: 200px !important;
  max-width: min(260px, calc(100vw - 32px)) !important;
  max-height: min(320px, 42vh) !important;
  overflow: hidden auto !important;
  padding: 4px !important;
  border: 1px solid color-mix(in srgb, var(--color-border, #e8e8e8) 90%, #d6d9de 10%) !important;
  border-radius: 8px !important;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.09), 0 1px 3px rgba(15, 23, 42, 0.08) !important;
  background: var(--color-bg-base, #fff) !important;
}

.bn-suggestion-menu:not(.task-row-assignee-panel)::-webkit-scrollbar {
  width: 8px;
}

.bn-suggestion-menu:not(.task-row-assignee-panel)::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--color-text-muted, #999) 42%, transparent);
  border-radius: 999px;
}

/* item 容器：MantineGroup[role="option"]，上游默认 52px — 覆写为紧凑 40px 单行 */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-suggestion-menu-item {
  height: 40px !important;
  min-height: 40px !important;
  padding: 0 6px !important;
  margin: 0 !important;
  border-radius: 6px !important;
  border: 1px solid transparent !important;
  background: transparent !important;
  cursor: pointer !important;
  transition:
    background var(--transition-fast, 120ms ease),
    border-color var(--transition-fast, 120ms ease) !important;
}

.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-suggestion-menu-item + .bn-suggestion-menu-item {
  margin-top: 1px !important;
}

/* 悬停 / 选中 */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-suggestion-menu-item[aria-selected="true"],
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-suggestion-menu-item:hover {
  background: color-mix(in srgb, var(--color-accent, #475569) 9%, var(--color-bg-base, #fff)) !important;
  border-color: color-mix(in srgb, var(--color-accent, #475569) 16%, transparent) !important;
}

/* 图标区：缩小内边距以适应 40px 行高 */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-mt-suggestion-menu-item-section[data-position="left"] {
  padding: 5px !important;
  border-radius: 4px !important;
  flex-shrink: 0 !important;
}

/* 文本区：垂直居中（单行无 subtitle） */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-mt-suggestion-menu-item-body {
  justify-content: center !important;
  padding-right: 8px !important;
}

/* 标题 */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-mt-suggestion-menu-item-title {
  font-size: 12px !important;
  line-height: 1.3 !important;
  font-weight: 500 !important;
  color: var(--color-text-primary, #1f2328) !important;
}

/* 副标题：隐藏（紧凑单行） */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-mt-suggestion-menu-item-subtitle {
  display: none !important;
}

/* 徽章（快捷键） */
.bn-suggestion-menu:not(.task-row-assignee-panel) .bn-mt-suggestion-menu-item-section[data-position="right"] {
  flex-shrink: 0 !important;
}

.bn-suggestion-menu:not(.task-row-assignee-panel) .mantine-Badge-root {
  font-size: 10px !important;
  padding: 0 5px !important;
  height: 18px !important;
  line-height: 18px !important;
}
</style>
