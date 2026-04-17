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
    /** `@` 成员菜单：与 TaskRowAssigneePicker 一致的搜索框占位与空态文案（仅传 mentionMembers 时生效） */
    mentionMenuSearchPlaceholder?: string
    mentionMenuNoMatchesText?: string
    mentionMenuLoadingText?: string
    /** 仅任务描述等场景：块侧栏 + `/` 命令菜单 */
    blockChrome?: boolean
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 120,
    blockChrome: false,
    mentionMenuSearchPlaceholder: '',
    mentionMenuNoMatchesText: '',
    mentionMenuLoadingText: '',
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
    @click.self="handleSurfaceClick()"
  >
    <BlockNoteVue
      :key="editorKey"
      :initialContent="internalValue"
      :placeholder="placeholder"
      :mentionMembers="mentionMembers"
      :mentionMenuSearchPlaceholder="mentionMenuSearchPlaceholder"
      :mentionMenuNoMatchesText="mentionMenuNoMatchesText"
      :mentionMenuLoadingText="mentionMenuLoadingText"
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

/* ── 标题比例变量同步到 :root，使 FloatingPortal (body 层) 的 .bn-side-menu 也能读取 ── */
:root {
  --bn-desc-h1: calc(2rem * 0.85);
  --bn-desc-h2: calc(2rem * 0.72);
  --bn-desc-h3: calc(2rem * 0.63);
}

/* ── Side menu 高度对齐：上游 styles.css 把 h1/h2/h3 的 .bn-side-menu 按默认大尺寸硬编码
      (78px/54px/37px)，本地把标题缩小后两套基准脱节，按钮在过高的盒子里 align-items:center
      后视觉下移。此处覆写为实际行高 = heading-font-size × line-height + bn-block-content-padding ── */
.bn-side-menu[data-block-type="heading"][data-level="1"] {
  height: calc(var(--bn-desc-h1) * 1.28 + 6px) !important;
}
.bn-side-menu[data-block-type="heading"][data-level="2"] {
  height: calc(var(--bn-desc-h2) * 1.3 + 6px) !important;
}
.bn-side-menu[data-block-type="heading"][data-level="3"] {
  height: calc(var(--bn-desc-h3) * 1.32 + 6px) !important;
}

/* ── Side menu: FloatingPortal renders at body level — BlockNote manages show/hide
      internally; just style size, colour, and layout here ── */
.bn-side-menu {
  display: flex !important;
  align-items: center !important;
  /* @blocknote/mantine SideMenu 已是 Group gap=0；勿再加 gap，否则与拖拽外包 Menu 叠起来显宽 */
  gap: 0 !important;
  column-gap: 0 !important;
}
/* 拖拽在 MantineMenu 内，第二个 flex 子项（Menu 根）略向左收，避免与「+」之间空一条 */
.bn-side-menu > * + * {
  margin: 0 !important;
  padding: 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  width: auto !important;
  min-width: 0 !important;
  margin-inline-start: -2px !important;
}

/* ── Side menu button sizing and colour ──
      SideMenuButton renders as MantineActionIcon (mantine-ActionIcon-root) when it has
      an icon (drag handle), and MantineButton (mantine-Button-root) for the + button ── */
.bn-side-menu .mantine-ActionIcon-root,
.bn-side-menu .mantine-Button-root {
  width: 22px !important;
  height: 22px !important;
  min-width: 22px !important;
  min-height: 22px !important;
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
  width: 15px;
  height: 15px;
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
