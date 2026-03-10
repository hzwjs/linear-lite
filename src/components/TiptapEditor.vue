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
import CodeBlock from '@tiptap/extension-code-block'
import Blockquote from '@tiptap/extension-blockquote'
import { mdToHtml, htmlToMd } from '../utils/editorMarkdown'
import TiptapSlashMenu from './TiptapSlashMenu.vue'

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
const editorWrapRef = ref<HTMLElement | null>(null)

function openSlashMenu(left: number, top: number) {
  slashMenuPos.value = { left, top }
  slashMenuOpen.value = true
}

const slashMenuExtension = Extension.create({
  name: 'slashMenu',
  addKeyboardShortcuts() {
    return {
      '/': () => {
        const { view, state } = this.editor
        const pos = state.selection.from
        const coords = view.coordsAtPos(pos)
        openSlashMenu(coords.left, coords.bottom)
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
    CodeBlock,
    Blockquote,
    slashMenuExtension,
  ],
  content: mdToHtml(props.modelValue ?? ''),
  onUpdate: ({ editor: ed }: { editor: { getHTML: () => string } }) => {
    emit('update:modelValue', htmlToMd(ed.getHTML()))
  },
})

function closeSlashMenu() {
  slashMenuOpen.value = false
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
    :style="{
      minHeight: `${minHeight}px`,
      '--placeholder-text': `'${placeholder}'`,
    }"
  >
    <EditorContent :editor="editor" />
    <TiptapSlashMenu
      :visible="slashMenuOpen"
      :position="slashMenuPos"
      :editor="editor ?? null"
      @close="onSlashMenuClose"
      @select="onSlashMenuClose"
    />
  </div>
</template>

<style scoped>
.tiptap-editor-wrap {
  background: var(--color-bg-subtle);
}
.tiptap-editor-wrap :deep(.tiptap) {
  outline: none;
  color: var(--color-text-primary);
  font-size: var(--font-size-body);
}
.tiptap-editor-wrap :deep(.tiptap p.is-editor-empty:first-child::before) {
  color: var(--color-text-muted);
  content: var(--placeholder-text);
  float: left;
  height: 0;
  pointer-events: none;
}
</style>
