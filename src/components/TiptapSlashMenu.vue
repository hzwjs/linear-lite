<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { Editor } from '@tiptap/core'

const menuEl = ref<HTMLElement | null>(null)

export type SlashMenuItemId =
  | 'heading1'
  | 'heading2'
  | 'heading3'
  | 'bulletList'
  | 'orderedList'
  | 'taskList'
  | 'codeBlock'
  | 'blockquote'

const items: { id: SlashMenuItemId; label: string }[] = [
  { id: 'heading1', label: 'Heading 1' },
  { id: 'heading2', label: 'Heading 2' },
  { id: 'heading3', label: 'Heading 3' },
  { id: 'bulletList', label: 'Bulleted list' },
  { id: 'orderedList', label: 'Numbered list' },
  { id: 'taskList', label: 'Checklist' },
  { id: 'codeBlock', label: 'Code block' },
  { id: 'blockquote', label: 'Blockquote' },
]

const props = defineProps<{
  visible: boolean
  position: { left: number; top: number }
  editor: Editor | null
}>()

const emit = defineEmits<{
  close: []
  select: []
}>()

const selectedIndex = ref(0)

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
  const chain = editor.chain().focus()
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
      chain.setCodeBlock().run()
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
      selectedIndex.value = (selectedIndex.value + 1) % items.length
      return
    case 'ArrowUp':
      e.preventDefault()
      selectedIndex.value = (selectedIndex.value - 1 + items.length) % items.length
      return
    case 'Enter': {
      e.preventDefault()
      const item = items[selectedIndex.value]
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
      aria-label="块类型菜单"
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
  z-index: 100;
  min-width: 180px;
  padding: 4px 0;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
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
