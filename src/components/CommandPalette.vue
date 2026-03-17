<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useOverlayStore } from '../store/overlayStore'

export interface CommandItem {
  id: string
  label: string
  keywords: string[]
  icon?: unknown
  run: () => void
}

const props = withDefaults(
  defineProps<{
    open: boolean
    commands: CommandItem[]
  }>(),
  { commands: () => [] }
)

const emit = defineEmits<{
  close: []
}>()

const overlayStore = useOverlayStore()
const { t } = useI18n()
const OVERLAY_ID = 'command-palette'

const query = ref('')
const selectedIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)
const listEl = ref<HTMLElement | null>(null)
let prevActiveElement: HTMLElement | null = null

const filteredCommands = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.commands
  return props.commands.filter(
    (c) =>
      c.label.toLowerCase().includes(q) ||
      c.keywords.some((k) => k.toLowerCase().includes(q))
  )
})

const selectedId = computed(() => {
  const list = filteredCommands.value
  if (list.length === 0) return null
  const i = Math.max(0, Math.min(selectedIndex.value, list.length - 1))
  return list[i]?.id ?? null
})

function close(restoreFocus = true) {
  overlayStore.remove(OVERLAY_ID)
  emit('close')
  if (restoreFocus) {
    nextTick(() => {
      prevActiveElement?.focus?.()
      prevActiveElement = null
    })
  } else {
    prevActiveElement = null
  }
}

function runCommand(cmd: CommandItem) {
  const skipRestore = cmd.id === 'focus-search'
  cmd.run()
  close(!skipRestore)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    e.stopPropagation()
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    selectedIndex.value = Math.min(
      selectedIndex.value + 1,
      filteredCommands.value.length - 1
    )
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    selectedIndex.value = Math.max(selectedIndex.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const list = filteredCommands.value
    const cmd = list[selectedIndex.value]
    if (cmd) runCommand(cmd)
    return
  }
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      prevActiveElement = (document.activeElement as HTMLElement) ?? null
      query.value = ''
      selectedIndex.value = 0
      overlayStore.push(OVERLAY_ID, close)
      nextTick(() => inputEl.value?.focus())
    }
  }
)

watch(filteredCommands, () => {
  selectedIndex.value = Math.min(selectedIndex.value, filteredCommands.value.length - 1)
})

// 焦点陷阱：焦点离开面板时拉回
function onFocusIn(e: FocusEvent) {
  const target = e.target as Node
  const root = listEl.value?.closest('.command-palette')
  if (root && target && !root.contains(target)) {
    setTimeout(() => inputEl.value?.focus(), 0)
  }
}

onMounted(() => {
  document.addEventListener('focusin', onFocusIn)
})
onUnmounted(() => {
  document.removeEventListener('focusin', onFocusIn)
  overlayStore.remove(OVERLAY_ID)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="command-palette"
      role="dialog"
      aria-modal="true"
      :aria-label="t('commandPalette.title')"
      @keydown="onKeydown"
    >
      <div class="command-palette-backdrop" @click="() => close()" />
      <div class="command-palette-panel" ref="listEl">
        <input
          ref="inputEl"
          v-model="query"
          type="text"
          class="command-palette-input"
          :placeholder="t('commandPalette.searchPlaceholder')"
          :aria-label="t('commandPalette.searchAria')"
          autocomplete="off"
        />
        <ul
          class="command-palette-list"
          role="listbox"
          :aria-activedescendant="selectedId ? `cmd-${selectedId}` : undefined"
        >
          <li
            v-for="(cmd, i) in filteredCommands"
            :key="cmd.id"
            :id="`cmd-${cmd.id}`"
            role="option"
            :aria-selected="i === selectedIndex"
            class="command-palette-item"
            :class="{ selected: i === selectedIndex }"
            @click="runCommand(cmd)"
            @mouseenter="selectedIndex = i"
          >
            <component
              v-if="cmd.icon"
              :is="cmd.icon"
              class="command-palette-icon"
              :aria-hidden="true"
            />
            <span class="command-palette-label">{{ cmd.label }}</span>
          </li>
          <li v-if="filteredCommands.length === 0" class="command-palette-empty">
            {{ t('commandPalette.noMatches') }}
          </li>
        </ul>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.command-palette {
  position: fixed;
  inset: 0;
  z-index: 300;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 20vh 24px 0;
  box-sizing: border-box;
}
.command-palette-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
}
.command-palette-panel {
  position: relative;
  width: 100%;
  max-width: 480px;
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-lg);
  box-shadow: var(--shadow-popover);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.command-palette-input {
  width: 100%;
  padding: 12px 16px;
  font-size: var(--font-size-body);
  color: var(--color-text-primary);
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--color-border);
  outline: none;
}
.command-palette-input::placeholder {
  color: var(--color-text-secondary);
}
.command-palette-list {
  list-style: none;
  margin: 0;
  padding: 8px 0;
  max-height: 320px;
  overflow-y: auto;
}
.command-palette-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  cursor: pointer;
  font-size: var(--font-size-body);
  color: var(--color-text-primary);
  transition: background var(--transition-fast);
}
.command-palette-item:hover,
.command-palette-item.selected {
  background: var(--color-bg-hover);
}
.command-palette-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  color: var(--color-text-secondary);
}
.command-palette-label {
  flex: 1;
}
.command-palette-empty {
  padding: 12px 16px;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  text-align: center;
}
</style>
