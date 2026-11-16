<script setup lang="ts">
import { Code2, FileText, X } from 'lucide-vue-next'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DocumentTabItem } from '../../store/documentTabsStore'

const props = defineProps<{
  tabs: DocumentTabItem[]
  activeId: number | null
}>()

const emit = defineEmits<{
  select: [documentId: number]
  close: [documentId: number]
  closeOthers: [documentId: number]
  closeAll: []
}>()

const { t } = useI18n()
const scrollContainer = ref<HTMLElement | null>(null)
const contextMenuRef = ref<HTMLElement | null>(null)

const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  tabId: null as number | null
})

function closeContextMenu() {
  contextMenu.visible = false
  contextMenu.tabId = null
}

function openContextMenu(event: MouseEvent, tabId: number) {
  event.preventDefault()
  contextMenu.tabId = tabId
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.visible = true
}

function handleMenuCloseTab() {
  if (contextMenu.tabId != null) {
    emit('close', contextMenu.tabId)
  }
  closeContextMenu()
}

function handleMenuCloseOthers() {
  if (contextMenu.tabId != null) {
    emit('closeOthers', contextMenu.tabId)
  }
  closeContextMenu()
}

function handleMenuCloseAll() {
  emit('closeAll')
  closeContextMenu()
}

function onAuxClick(event: MouseEvent, tabId: number) {
  // 鼠标中键直接关闭标签页
  if (event.button === 1) {
    event.preventDefault()
    emit('close', tabId)
  }
}

function onWheel(event: WheelEvent) {
  if (!scrollContainer.value) return
  if (Math.abs(event.deltaY) > Math.abs(event.deltaX)) {
    scrollContainer.value.scrollLeft += event.deltaY
    event.preventDefault()
  }
}

function onGlobalClick(event: MouseEvent) {
  if (contextMenu.visible && contextMenuRef.value && !contextMenuRef.value.contains(event.target as Node)) {
    closeContextMenu()
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && contextMenu.visible) {
    closeContextMenu()
  }
}

watch(
  () => props.activeId,
  (id) => {
    if (id == null) return
    void nextTick(() => {
      const activeEl = scrollContainer.value?.querySelector<HTMLElement>('.document-tab--active')
      if (activeEl && typeof activeEl.scrollIntoView === 'function') {
        activeEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
      }
    })
  }
)

onMounted(() => {
  window.addEventListener('click', onGlobalClick)
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', onGlobalClick)
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <nav v-if="tabs.length > 0" class="document-tabs" :aria-label="t('documents.tabList')">
    <div ref="scrollContainer" class="document-tabs__scroll" @wheel="onWheel">
      <div role="tablist" class="document-tabs__list">
        <div
          v-for="tab in tabs"
          :key="tab.id"
          role="tab"
          :aria-selected="tab.id === activeId"
          :class="['document-tab', { 'document-tab--active': tab.id === activeId }]"
          :title="tab.title || t('documents.untitled')"
          tabindex="0"
          @click="emit('select', tab.id)"
          @auxclick="onAuxClick($event, tab.id)"
          @contextmenu.prevent="openContextMenu($event, tab.id)"
          @keydown.enter="emit('select', tab.id)"
          @keydown.space.prevent="emit('select', tab.id)"
        >
          <Code2 v-if="tab.contentFormat === 'HTML'" class="document-tab__icon" aria-hidden="true" />
          <FileText v-else class="document-tab__icon" aria-hidden="true" />
          <span class="document-tab__title">{{ tab.title || t('documents.untitled') }}</span>
          <button
            type="button"
            class="document-tab__close"
            :aria-label="t('documents.closeTab') + ': ' + (tab.title || t('documents.untitled'))"
            :title="t('documents.closeTab')"
            @click.stop="emit('close', tab.id)"
          >
            <X aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>

    <!-- 右键上下文菜单 -->
    <Teleport to="body">
      <div
        v-if="contextMenu.visible"
        ref="contextMenuRef"
        class="document-tabs-context-menu"
        role="menu"
        :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        @click.stop
      >
        <button type="button" role="menuitem" @click="handleMenuCloseTab">
          <X aria-hidden="true" />
          <span>{{ t('documents.closeTab') }}</span>
        </button>
        <button type="button" role="menuitem" @click="handleMenuCloseOthers">
          <span>{{ t('documents.closeOtherTabs') }}</span>
        </button>
        <button type="button" role="menuitem" class="danger" @click="handleMenuCloseAll">
          <span>{{ t('documents.closeAllTabs') }}</span>
        </button>
      </div>
    </Teleport>
  </nav>
</template>

<style scoped>
.document-tabs {
  display: flex;
  height: 38px;
  min-height: 38px;
  width: 100%;
  align-items: stretch;
  background: var(--color-bg-subtle);
  border-bottom: 1px solid var(--color-border);
  user-select: none;
  position: relative;
  z-index: 10;
}

.document-tabs__scroll {
  display: flex;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
}

.document-tabs__scroll::-webkit-scrollbar {
  display: none;
}

.document-tabs__list {
  display: flex;
  align-items: stretch;
  height: 100%;
  padding: 0 4px;
  gap: 2px;
}

.document-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 200px;
  min-width: 80px;
  height: 100%;
  padding: 0 8px 0 10px;
  font-size: 13px;
  color: var(--color-text-secondary);
  background: transparent;
  border-top: 2px solid transparent;
  border-bottom: 2px solid transparent;
  border-left: 1px solid transparent;
  border-right: 1px solid transparent;
  cursor: pointer;
  transition: background-color 150ms ease, color 150ms ease;
  position: relative;
}

.document-tab:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.document-tab--active {
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-weight: 500;
  border-bottom-color: var(--color-accent);
  border-left-color: var(--color-border);
  border-right-color: var(--color-border);
}

.document-tab__icon {
  width: 14px;
  height: 14px;
  flex: none;
  color: var(--color-text-muted);
}

.document-tab--active .document-tab__icon {
  color: var(--color-accent);
}

.document-tab__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-tab__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-left: 2px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm, 3px);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0.6;
  transition: opacity 150ms ease, background-color 150ms ease, color 150ms ease;
}

.document-tab:hover .document-tab__close {
  opacity: 0.9;
}

.document-tab__close:hover {
  opacity: 1;
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
}

.document-tab__close svg {
  width: 12px;
  height: 12px;
}

.document-tabs-context-menu {
  position: fixed;
  z-index: 1000;
  min-width: 140px;
  padding: 4px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md, 6px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.document-tabs-context-menu button {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  border: none;
  border-radius: var(--radius-sm, 4px);
  background: transparent;
  font-size: 12px;
  color: var(--color-text-primary);
  cursor: pointer;
  text-align: left;
}

.document-tabs-context-menu button:hover {
  background: var(--color-bg-hover);
}

.document-tabs-context-menu button.danger {
  color: var(--color-danger);
}

.document-tabs-context-menu button.danger:hover {
  background: rgba(201, 74, 74, 0.08);
}

.document-tabs-context-menu svg {
  width: 13px;
  height: 13px;
}
</style>
