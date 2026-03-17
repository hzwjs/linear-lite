<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import type { Editor } from '@tiptap/core'
import { useI18n } from 'vue-i18n'

const menuEl = ref<HTMLElement | null>(null)
const { t } = useI18n()

export type SlashMenuItemId =
  | 'heading1'
  | 'heading2'
  | 'heading3'
  | 'bulletList'
  | 'orderedList'
  | 'taskList'
  | 'codeBlock'
  | 'blockquote'

const props = defineProps<{
  visible: boolean
  position: { left: number; top: number }
  editor: Editor | null
  /** 打开菜单时的选区，执行命令前先恢复，避免焦点在菜单时选区丢失 */
  selection: { from: number; to: number } | null
}>()

const emit = defineEmits<{
  close: []
  select: []
}>()

const selectedIndex = ref(0)
const items = computed<{ id: SlashMenuItemId; label: string }[]>(() => [
  { id: 'heading1', label: t('editor.slashMenu.heading1') },
  { id: 'heading2', label: t('editor.slashMenu.heading2') },
  { id: 'heading3', label: t('editor.slashMenu.heading3') },
  { id: 'bulletList', label: t('editor.slashMenu.bulletList') },
  { id: 'orderedList', label: t('editor.slashMenu.orderedList') },
  { id: 'taskList', label: t('editor.slashMenu.taskList') },
  { id: 'codeBlock', label: t('editor.slashMenu.codeBlock') },
  { id: 'blockquote', label: t('editor.slashMenu.blockquote') },
])

watch(
  () => props.visible,
  (v) => {
    if (v) {
      selectedIndex.value = 0
      nextTick(() => menuEl.value?.focus())
    }
  }
)

function runCommand(id: SlashMenuItemId) {
  const editor = props.editor
  if (!editor) return
  const sel = props.selection
  let chain = editor.chain().focus()
  if (sel) {
    chain = chain.setTextSelection({ from: sel.from, to: sel.to })
  }
  switch (id) {
    case 'heading1':
      chain.setHeading({ level: 1 }).run()
      break
    case 'heading2':
      chain.setHeading({ level: 2 }).run()
      break
    case 'heading3':
      chain.setHeading({ level: 3 }).run()
      break
    case 'bulletList':
      chain.toggleBulletList().run()
      break
    case 'orderedList':
      chain.toggleOrderedList().run()
      break
    case 'taskList':
      chain.toggleTaskList().run()
      break
    case 'codeBlock':
      chain.insertContent({ type: 'codeBlock', attrs: { language: 'bash' }, content: [] }).run()
      break
    case 'blockquote':
      chain.toggleBlockquote().run()
      break
  }
  emit('select')
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (!props.visible) return
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      selectedIndex.value = (selectedIndex.value + 1) % items.value.length
      return
    case 'ArrowUp':
      e.preventDefault()
      selectedIndex.value = (selectedIndex.value - 1 + items.value.length) % items.value.length
      return
    case 'Enter': {
      e.preventDefault()
      const item = items.value[selectedIndex.value]
      if (item) runCommand(item.id)
      return
    }
    case 'Escape':
      e.preventDefault()
      emit('close')
      return
  }
}

function onSelect(id: SlashMenuItemId) {
  runCommand(id)
}
</script>

<template>
  <Teleport to="body">
    <div
      ref="menuEl"
      v-show="visible"
      role="listbox"
      :aria-label="t('editor.slashMenu.ariaLabel')"
      tabindex="-1"
      class="slash-menu"
      data-slash-menu
      :style="{
        left: `${position.left}px`,
        top: `${position.top}px`,
      }"
      @keydown="onKeydown"
    >
      <button
        v-for="(item, i) in items"
        :key="item.id"
        type="button"
        role="option"
        :aria-selected="i === selectedIndex"
        :class="{ active: i === selectedIndex }"
        @mousedown.prevent="onSelect(item.id)"
      >
        {{ item.label }}
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.slash-menu {
  position: fixed;
  z-index: 250;
  min-width: 180px;
  padding: 4px 0;
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-popover);
  font-size: var(--font-size-body);
  color: var(--color-text-primary);
}

.slash-menu button {
  display: block;
  width: 100%;
  padding: 6px 12px;
  text-align: left;
  border: none;
  background: transparent;
  color: var(--color-text-primary);
  cursor: pointer;
  font-size: var(--font-size-body);
}

.slash-menu button:hover,
.slash-menu button.active {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
</style>
