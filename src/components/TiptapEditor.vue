<script setup lang="ts">
import { watch, ref, onMounted, onUnmounted, computed } from 'vue'
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
import { mdToHtml, htmlToMd, looksLikeMarkdownTaskList } from '../utils/editorMarkdown'
import { uploadApi } from '../services/api/upload'
import {
  FAILED_IMAGE_STATE,
  UPLOADED_IMAGE_STATE,
  UPLOADING_IMAGE_STATE,
  getEditorUploadStateSummaryFromHtml,
  serializeEditorHtmlForSave,
  validateEditorImageFile,
} from '../utils/editorImageUpload'
import { useI18n } from 'vue-i18n'
import { TaskImage } from '../extensions/TaskImage'
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
    placeholder: '',
    minHeight: 120,
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'upload-state-change': [state: { hasPending: boolean; hasFailed: boolean }]
  blur: []
}>()

const { t } = useI18n()
const resolvedPlaceholder = computed(() => props.placeholder || t('editor.placeholder'))

const slashMenuOpen = ref(false)
const slashMenuPos = ref({ left: 0, top: 0 })
const slashMenuSelection = ref<{ from: number; to: number } | null>(null)
const editorWrapRef = ref<HTMLElement | null>(null)
const uploadStateByLocalId = new Map<
  string,
  { file: File; objectUrl: string }
>()

function openSlashMenu(left: number, top: number, from: number, to: number) {
  slashMenuPos.value = { left, top }
  slashMenuSelection.value = { from, to }
  slashMenuOpen.value = true
}

function randomLocalId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
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
    TaskItem.configure({ nested: true }),
    TaskImage.configure({
      inline: true,
      onRetry: (localId: string) => {
        void retryImageUpload(localId)
      },
      onRemove: (localId: string) => {
        removeImageByLocalId(localId)
      },
    } as any),
    createCodeBlockLinear({ lowlight, defaultLanguage: 'bash' }),
    Blockquote,
    Placeholder.configure({
      placeholder: () => resolvedPlaceholder.value,
      emptyEditorClass: 'is-editor-empty',
    }),
    slashMenuExtension,
  ],
  content: mdToHtml(props.modelValue ?? ''),
  onUpdate: ({ editor: ed }: { editor: { getHTML: () => string } }) => {
    const html = ed.getHTML()
    emit('update:modelValue', htmlToMd(serializeEditorHtmlForSave(html)))
    emit('upload-state-change', getEditorUploadStateSummaryFromHtml(html))
  },
})

watch(
  editor,
  (e) => {
    if (!e?.view?.dom) return
    const dom = e.view.dom
    const onBlur = () => emit('blur')
    dom.addEventListener('blur', onBlur)
    return () => dom.removeEventListener('blur', onBlur)
  },
  { immediate: true }
)

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

function revokePreviewUrl(localId: string) {
  const entry = uploadStateByLocalId.get(localId)
  if (!entry) return
  URL.revokeObjectURL(entry.objectUrl)
}

function findImageNodePosition(localId: string): number | null {
  if (!editor.value) return null
  let found: number | null = null
  editor.value.state.doc.descendants((node, pos) => {
    if (node.type.name === 'taskImage' && node.attrs.localId === localId) {
      found = pos
      return false
    }
    return true
  })
  return found
}

function updateImageNodeByLocalId(localId: string, attrs: Record<string, unknown>) {
  if (!editor.value) return false
  const pos = findImageNodePosition(localId)
  if (pos == null) return false
  const node = editor.value.state.doc.nodeAt(pos)
  if (!node) return false
  const tr = editor.value.state.tr.setNodeMarkup(pos, undefined, {
    ...node.attrs,
    ...attrs,
  })
  editor.value.view.dispatch(tr)
  return true
}

function removeImageByLocalId(localId: string) {
  if (!editor.value) return
  const pos = findImageNodePosition(localId)
  if (pos == null) return
  const node = editor.value.state.doc.nodeAt(pos)
  if (!node) return
  revokePreviewUrl(localId)
  uploadStateByLocalId.delete(localId)
  const tr = editor.value.state.tr.delete(pos, pos + node.nodeSize)
  editor.value.view.dispatch(tr)
}

function insertPreviewImage(file: File, pos?: number) {
  if (!editor.value) return { localId: '', nextPos: pos }
  const localId = randomLocalId()
  const objectUrl = URL.createObjectURL(file)
  uploadStateByLocalId.set(localId, { file, objectUrl })
  const chain = editor.value.chain().focus()
  if (typeof pos === 'number') {
    chain.setTextSelection(pos)
  }
  chain
    .insertContent({
      type: 'taskImage',
      attrs: {
        src: objectUrl,
        alt: file.name || t('taskImage.altFallback'),
        localId,
        uploadState: UPLOADING_IMAGE_STATE,
        errorMessage: null,
      },
    })
    .insertContent(' ')
    .run()
  return { localId, nextPos: editor.value.state.selection.from }
}

async function startImageUpload(localId: string) {
  const entry = uploadStateByLocalId.get(localId)
  if (!entry) return
  updateImageNodeByLocalId(localId, {
    uploadState: UPLOADING_IMAGE_STATE,
    errorMessage: null,
  })
  try {
    const uploaded = await uploadApi.uploadImage(entry.file)
    const updated = updateImageNodeByLocalId(localId, {
      src: uploaded.url,
      uploadState: UPLOADED_IMAGE_STATE,
      errorMessage: null,
    })
    if (updated) {
      revokePreviewUrl(localId)
      uploadStateByLocalId.delete(localId)
    }
  } catch (error) {
    updateImageNodeByLocalId(localId, {
      uploadState: FAILED_IMAGE_STATE,
      errorMessage: error instanceof Error ? error.message : t('attachments.uploadFailed'),
    })
  }
}

async function retryImageUpload(localId: string) {
  if (!uploadStateByLocalId.has(localId)) return
  await startImageUpload(localId)
}

function queueImageUpload(localId: string) {
  void startImageUpload(localId)
}

function handleImageFiles(files: File[], pos?: number) {
  let nextPos = pos
  for (const file of files) {
  const validation = validateEditorImageFile(file)
  if (!validation.ok) {
      continue
  }
    const inserted = insertPreviewImage(file, nextPos)
    nextPos = inserted.nextPos
    if (inserted.localId) queueImageUpload(inserted.localId)
  }
}

function getClipboardImageFiles(event: ClipboardEvent): File[] {
  const items = Array.from(event.clipboardData?.items ?? [])
  return items
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file != null)
    .filter((file) => file.type.startsWith('image/'))
}

function getDroppedImageFiles(event: DragEvent): File[] {
  return Array.from(event.dataTransfer?.files ?? []).filter((file) =>
    file.type.startsWith('image/')
  )
}

async function handlePaste(event: ClipboardEvent) {
  const files = getClipboardImageFiles(event)
  if (files.length > 0) {
    event.preventDefault()
    handleImageFiles(files)
    return
  }

  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (!text || !looksLikeMarkdownTaskList(text)) return

  event.preventDefault()
  editor.value?.chain().focus().insertContent(mdToHtml(text)).run()
}

async function handleDrop(event: DragEvent) {
  const files = getDroppedImageFiles(event)
  if (files.length === 0) return
  event.preventDefault()
  const pos = editor.value?.view.posAtCoords({
    left: event.clientX,
    top: event.clientY,
  })?.pos
  handleImageFiles(files, pos)
}

onMounted(() => {
  document.addEventListener('mousedown', handleClickOutside)
})
onUnmounted(() => {
  document.removeEventListener('mousedown', handleClickOutside)
  for (const [localId] of uploadStateByLocalId) {
    revokePreviewUrl(localId)
  }
  uploadStateByLocalId.clear()
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
    @dragover.prevent
    @paste.capture="handlePaste"
    @drop.capture="handleDrop"
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
.tiptap-editor-wrap :deep(.tiptap > *:first-child) {
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

.tiptap-editor-wrap :deep(.tiptap h1) {
  margin: 1.35em 0 0.5em;
  font-size: 1.5rem;
  font-weight: 700;
  line-height: 1.24;
  letter-spacing: -0.025em;
}

.tiptap-editor-wrap :deep(.tiptap h1:first-child) {
  margin-top: 0.1em;
}

.tiptap-editor-wrap :deep(.tiptap h2) {
  margin: 1.2em 0 0.45em;
  font-size: 1.25rem;
  font-weight: 650;
  line-height: 1.3;
  letter-spacing: -0.018em;
}

.tiptap-editor-wrap :deep(.tiptap h3) {
  margin: 1.05em 0 0.4em;
  font-size: 1.05rem;
  font-weight: 650;
  line-height: 1.35;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"]) {
  list-style: none;
  padding-left: 1.5em;
  margin: 0.5em 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li) {
  display: flex;
  align-items: center;
  gap: 0.55em;
  list-style: none;
  margin: 0.2em 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li::marker) {
  content: '';
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > label) {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  margin: 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > div) {
  flex: 1;
  min-width: 0;
}

.tiptap-editor-wrap :deep(.tiptap ul[data-type="taskList"] li > div > p) {
  margin: 0;
}

/* Blockquote：左边框 + 左内边距，与正文区分 */
.tiptap-editor-wrap :deep(.tiptap blockquote) {
  margin: 0.5em 0;
  padding-left: 1em;
  border-left: 4px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}

</style>
