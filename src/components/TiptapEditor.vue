<script setup lang="ts">
import { watch } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
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
  ],
  content: mdToHtml(props.modelValue ?? ''),
  onUpdate: ({ editor: ed }: { editor: { getHTML: () => string } }) => {
    emit('update:modelValue', htmlToMd(ed.getHTML()))
  },
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
    class="tiptap-editor-wrap"
    :style="{
      minHeight: `${minHeight}px`,
      '--placeholder-text': `'${placeholder}'`,
    }"
  >
    <EditorContent :editor="editor" />
  </div>
</template>

<style scoped>
.tiptap-editor-wrap :deep(.tiptap) {
  outline: none;
}
.tiptap-editor-wrap :deep(.tiptap p.is-editor-empty:first-child::before) {
  color: var(--placeholder-color, #9ca3af);
  content: var(--placeholder-text);
  float: left;
  height: 0;
  pointer-events: none;
}
</style>
