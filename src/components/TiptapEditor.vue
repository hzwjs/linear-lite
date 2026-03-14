<script setup lang="ts">
import { watch, ref, onMounted, onUnmounted } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import { Extension } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import Heading from '@tiptap/extension-heading'
import BulletList from '@tiptap/extension-bullet-list'
import ListItem from '@tiptap/extension-list-item'
import OrderedList from '@tiptap/extension-ordered-list'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { createLowlight, common } from 'lowlight'
import { createCodeBlockLinear } from '../extensions/CodeBlockLinear'
import Blockquote from '@tiptap/extension-blockquote'
import Placeholder from '@tiptap/extension-placeholder'
import { mdToHtml, htmlToMd } from '../utils/editorMarkdown'
import TiptapSlashMenu from './TiptapSlashMenu.vue'

const lowlight = createLowlight(common)

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    minHeight?: number
  }>(),
  {
    modelValue: '',
    placeholder: '输入内容…',
    minHeight: 120,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const slashMenuOpen = ref(false)
const slashMenuPos = ref({ left: 0, top: 0 })
const slashMenuSelection = ref<{ from: number; to: number } | null>(null)
const editorWrapRef = ref<HTMLElement | null>(null)

function openSlashMenu(left: number, top: number, from: number, to: number) {
  slashMenuPos.value = { left, top }
  slashMenuSelection.value = { from, to }
  slashMenuOpen.value = true
}

const slashMenuExtension = Extension.create({
  name: 'slashMenu',
  addKeyboardShortcuts() {
    return {
      '/': () => {
        const { view, state } = this.editor
        const { $from } = state.selection
        if ($from.parent.type.name === 'codeBlock') return false
        const { from, to } = state.selection
        const coords = view.coordsAtPos(from)
        openSlashMenu(coords.left, coords.bottom, from, to)
        return true
      },
    }
  },
})

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      codeBlock: false,
      heading: false,
      blockquote: false,
      bulletList: false,
      listItem: false,
      orderedList: false,
    }),
    Heading.configure({ levels: [1, 2, 3] }),
    BulletList,
    ListItem,
    OrderedList,
    TaskList,
    TaskItem,
    createCodeBlockLinear({ lowlight, defaultLanguage: 'bash' }),
    Blockquote,
    Placeholder.configure({
      placeholder: props.placeholder,
      emptyEditorClass: 'is-editor-empty',
    }),
    slashMenuExtension,
  ],
  content: mdToHtml(props.modelValue ?? ''),
  onUpdate: ({ editor: ed }: { editor: { getHTML: () => string } }) => {
    emit('update:modelValue', htmlToMd(ed.getHTML()))
  },
})

function closeSlashMenu() {
  slashMenuOpen.value = false
  slashMenuSelection.value = null
}

function focusEditor() {
  editor.value?.commands.focus()
}
defineExpose({ focus: focusEditor })

function onWrapMouseDown(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (!editorWrapRef.value?.contains(target)) return
  if (target.closest('[data-slash-menu]')) return
  if (target.closest('.code-block-linear')) return
  editor.value?.commands.focus()
}

function onSlashMenuClose() {
  closeSlashMenu()
  editor.value?.commands.focus()
}

function handleClickOutside(e: MouseEvent) {
  if (!slashMenuOpen.value) return
  const target = e.target as Node
  const inEditor = editorWrapRef.value?.contains(target)
  const inMenu = document.querySelector('[data-slash-menu]')?.contains(target)
  if (!inEditor && !inMenu) closeSlashMenu()
}

onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside)
})
onUnmounted(() => {
  document.removeEventListener('mousedown', handleClickOutside)
})

watch(
  () => props.modelValue,
  (newVal) => {
    if (!editor.value) return
    const currentMd = htmlToMd(editor.value.getHTML())
    const newNorm = (newVal ?? '').trim()
    if (currentMd.trim() !== newNorm) {
      editor.value.commands.setContent(mdToHtml(newVal ?? ''), false)
    }
  }
)
</script>

<template>
  <div
    ref="editorWrapRef"
    class="tiptap-editor-wrap"
    :style="{ minHeight: `${minHeight}px` }"
    @mousedown="onWrapMouseDown"
  >
    <EditorContent :editor="editor" />
    <TiptapSlashMenu
      :visible="slashMenuOpen"
      :position="slashMenuPos"
      :editor="editor ?? null"
      :selection="slashMenuSelection"
      @close="onSlashMenuClose"
      @select="onSlashMenuClose"
    />
  </div>
</template>

<style scoped>
.tiptap-editor-wrap {
  background: var(--color-bg-subtle);
  cursor: text;
  padding-top: 0;
}
.tiptap-editor-wrap :deep(.tiptap) {
  outline: none;
  color: var(--color-text-primary);
  font-size: var(--font-size-body);
  padding: 0;
  min-height: 2.5em;
}
.tiptap-editor-wrap :deep(.tiptap p:first-child) {
  margin-top: 0;
}
.tiptap-editor-wrap :deep(.tiptap.is-editor-empty) {
  min-height: 2.5em;
}
.tiptap-editor-wrap :deep(.tiptap.is-editor-empty p:first-child::before) {
  color: var(--color-text-muted);
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}
.tiptap-editor-wrap :deep(.tiptap p.is-editor-empty:first-child::before) {
  color: var(--color-text-muted);
  content: attr(data-placeholder);
  float: left;
  height: 0;
  pointer-events: none;
}

/* Blockquote：左边框 + 左内边距，与正文区分 */
.tiptap-editor-wrap :deep(.tiptap blockquote) {
  margin: 0.5em 0;
  padding-left: 1em;
  border-left: 4px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}
</style>
