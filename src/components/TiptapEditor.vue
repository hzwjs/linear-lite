<script setup lang="ts">
import { watch } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
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
  extensions: [StarterKit],
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
