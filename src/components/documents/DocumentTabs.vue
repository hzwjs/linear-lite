<script setup lang="ts">
import { ChevronDown, Code2, FileText, Pin, PinOff, X } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DocumentTabItem } from '../../store/documentTabsStore'

const props = defineProps<{
  tabs: DocumentTabItem[]
  activeId: number | null
  dirtyIds?: number[]
}>()

const emit = defineEmits<{
  select: [documentId: number]
  close: [documentId: number]
  closeOthers: [documentId: number]
  closeToRight: [documentId: number]
  closeAll: []
  togglePin: [documentId: number]
  reorder: [sourceId: number, targetId: number, place: 'before' | 'after']
}>()

const { t } = useI18n()
const scrollContainer = ref<HTMLElement | null>(null)
const contextMenuRef = ref<HTMLElement | null>(null)
const moreMenuBtnRef = ref<HTMLElement | null>(null)
const moreMenuRef = ref<HTMLElement | null>(null)

// 拖拽重排状态
const draggingTabId = ref<number | null>(null)
const dragOverTabId = ref<number | null>(null)
const dragOverPlace = ref<'before' | 'after' | null>(null)

// 右键上下文菜单状态
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  tabId: null as number | null
})

// 溢出“全部标签页”下拉面板状态
const moreMenu = reactive({
  visible: false,
  x: 0,
  y: 0
})

function isDirty(tabId: number): boolean {
  return props.dirtyIds?.includes(tabId) ?? false
}

const contextMenuTab = computed(() => {
  if (contextMenu.tabId == null) return null
  return props.tabs.find((t) => t.id === contextMenu.tabId) ?? null
})

const isContextMenuTabPinned = computed(() => contextMenuTab.value?.pinned === true)

const hasUnpinnedTabsToRight = computed(() => {
  if (contextMenu.tabId == null) return false
  const index = props.tabs.findIndex((t) => t.id === contextMenu.tabId)
  if (index === -1) return false
  return props.tabs.slice(index + 1).some((t) => !t.pinned)
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

function handleMenuTogglePin() {
  if (contextMenu.tabId != null) {
    emit('togglePin', contextMenu.tabId)
  }
  closeContextMenu()
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

function handleMenuCloseToRight() {
  if (contextMenu.tabId != null) {
    emit('closeToRight', contextMenu.tabId)
  }
  closeContextMenu()
}

function handleMenuCloseAll() {
  emit('closeAll')
  closeContextMenu()
}

function closeMoreMenu() {
  moreMenu.visible = false
}

function toggleMoreMenu() {
  if (moreMenu.visible) {
    closeMoreMenu()
    return
  }
  if (!moreMenuBtnRef.value) return
  const rect = moreMenuBtnRef.value.getBoundingClientRect()
  moreMenu.x = Math.max(10, rect.right - 240)
  moreMenu.y = rect.bottom + 4
  moreMenu.visible = true
}

function handleSelectFromMoreMenu(tabId: number) {
  emit('select', tabId)
  closeMoreMenu()
}

function handleCloseFromMoreMenu(event: MouseEvent, tabId: number) {
  event.stopPropagation()
  emit('close', tabId)
}

function onAuxClick(event: MouseEvent, tabId: number) {
  // 鼠标中键直接关闭非固定标签页
  if (event.button === 1) {
    event.preventDefault()
    const tab = props.tabs.find((t) => t.id === tabId)
    if (!tab?.pinned) {
      emit('close', tabId)
    }
  }
}

function onWheel(event: WheelEvent) {
  if (!scrollContainer.value) return
  if (Math.abs(event.deltaY) > Math.abs(event.deltaX)) {
    scrollContainer.value.scrollLeft += event.deltaY
    event.preventDefault()
  }
}

// 原生 HTML5 拖拽重排逻辑
function onDragStart(event: DragEvent, tabId: number) {
  draggingTabId.value = tabId
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(tabId))
  }
}

function onDragOver(event: DragEvent, tabId: number) {
  if (draggingTabId.value == null || draggingTabId.value === tabId) {
    dragOverTabId.value = null
    dragOverPlace.value = null
    return
  }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const midX = rect.left + rect.width / 2
  const place = event.clientX < midX ? 'before' : 'after'
  dragOverTabId.value = tabId
  dragOverPlace.value = place
}

function onDragLeave(_event: DragEvent, tabId: number) {
  if (dragOverTabId.value === tabId) {
    dragOverTabId.value = null
    dragOverPlace.value = null
  }
}

function onDrop(event: DragEvent, tabId: number) {
  event.preventDefault()
  if (draggingTabId.value != null && draggingTabId.value !== tabId && dragOverPlace.value) {
    emit('reorder', draggingTabId.value, tabId, dragOverPlace.value)
  }
  resetDragState()
}

function onDragEnd() {
  resetDragState()
}

function resetDragState() {
  draggingTabId.value = null
  dragOverTabId.value = null
  dragOverPlace.value = null
}

function onGlobalClick(event: MouseEvent) {
  const target = event.target as Node
  if (contextMenu.visible && contextMenuRef.value && !contextMenuRef.value.contains(target)) {
    closeContextMenu()
  }
  if (
    moreMenu.visible &&
    moreMenuRef.value &&
    !moreMenuRef.value.contains(target) &&
    !moreMenuBtnRef.value?.contains(target)
  ) {
    closeMoreMenu()
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    if (contextMenu.visible) closeContextMenu()
    if (moreMenu.visible) closeMoreMenu()
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
          :class="[
            'document-tab',
            {
              'document-tab--active': tab.id === activeId,
              'document-tab--pinned': tab.pinned,
              'document-tab--dragging': draggingTabId === tab.id,
              'document-tab--drag-before': dragOverTabId === tab.id && dragOverPlace === 'before',
              'document-tab--drag-after': dragOverTabId === tab.id && dragOverPlace === 'after'
            }
          ]"
          :title="tab.pinned ? `${tab.title || t('documents.untitled')} (${t('documents.pinTab')})` : (tab.title || t('documents.untitled'))"
          tabindex="0"
          draggable="true"
          @dragstart="onDragStart($event, tab.id)"
          @dragover="onDragOver($event, tab.id)"
          @dragleave="onDragLeave($event, tab.id)"
          @drop="onDrop($event, tab.id)"
          @dragend="onDragEnd"
          @click="emit('select', tab.id)"
          @auxclick="onAuxClick($event, tab.id)"
          @contextmenu.prevent="openContextMenu($event, tab.id)"
          @keydown.enter="emit('select', tab.id)"
          @keydown.space.prevent="emit('select', tab.id)"
        >
          <Pin v-if="tab.pinned" class="document-tab__pin-icon" aria-hidden="true" />
          <Code2 v-else-if="tab.contentFormat === 'HTML'" class="document-tab__icon" aria-hidden="true" />
          <FileText v-else class="document-tab__icon" aria-hidden="true" />
          <span class="document-tab__title">{{ tab.title || t('documents.untitled') }}</span>
          <span
            v-if="isDirty(tab.id) && !tab.pinned"
            class="document-tab__dirty-dot"
            :title="t('documents.unsavedChanges')"
            aria-hidden="true"
          />
          <button
            v-if="!tab.pinned"
            type="button"
            :class="['document-tab__close', { 'document-tab__close--has-dirty': isDirty(tab.id) }]"
            :aria-label="t('documents.closeTab') + ': ' + (tab.title || t('documents.untitled'))"
            :title="t('documents.closeTab')"
            @click.stop="emit('close', tab.id)"
          >
            <X aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>

    <!-- 右侧动作栏：全部标签页下拉按钮 -->
    <div class="document-tabs__actions">
      <button
        ref="moreMenuBtnRef"
        type="button"
        class="document-tabs__more-btn"
        :aria-label="t('documents.moreTabs')"
        :title="t('documents.moreTabs')"
        @click.stop="toggleMoreMenu"
      >
        <ChevronDown aria-hidden="true" />
      </button>
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
        <button type="button" role="menuitem" @click="handleMenuTogglePin">
          <PinOff v-if="isContextMenuTabPinned" aria-hidden="true" />
          <Pin v-else aria-hidden="true" />
          <span>{{ isContextMenuTabPinned ? t('documents.unpinTab') : t('documents.pinTab') }}</span>
        </button>
        <button type="button" role="menuitem" @click="handleMenuCloseTab">
          <X aria-hidden="true" />
          <span>{{ t('documents.closeTab') }}</span>
        </button>
        <button type="button" role="menuitem" @click="handleMenuCloseOthers">
          <span>{{ t('documents.closeOtherTabs') }}</span>
        </button>
        <button
          type="button"
          role="menuitem"
          :disabled="!hasUnpinnedTabsToRight"
          @click="handleMenuCloseToRight"
        >
          <span>{{ t('documents.closeTabsToRight') }}</span>
        </button>
        <button type="button" role="menuitem" class="danger" @click="handleMenuCloseAll">
          <span>{{ t('documents.closeAllTabs') }}</span>
        </button>
      </div>
    </Teleport>

    <!-- 全部标签页下拉面板 -->
    <Teleport to="body">
      <div
        v-if="moreMenu.visible"
        ref="moreMenuRef"
        class="document-tabs-more-menu"
        role="menu"
        :style="{ left: `${moreMenu.x}px`, top: `${moreMenu.y}px` }"
        @click.stop
      >
        <div class="document-tabs-more-menu__header">
          <span>{{ t('documents.moreTabs') }} ({{ tabs.length }})</span>
        </div>
        <div class="document-tabs-more-menu__list">
          <div
            v-for="tab in tabs"
            :key="tab.id"
            role="menuitem"
            :class="['document-tabs-more-menu__item', { 'document-tabs-more-menu__item--active': tab.id === activeId }]"
            tabindex="0"
            @click="handleSelectFromMoreMenu(tab.id)"
            @keydown.enter="handleSelectFromMoreMenu(tab.id)"
          >
            <Pin v-if="tab.pinned" class="document-tab__pin-icon" aria-hidden="true" />
            <Code2 v-else-if="tab.contentFormat === 'HTML'" class="document-tab__icon" aria-hidden="true" />
            <FileText v-else class="document-tab__icon" aria-hidden="true" />
            <span class="document-tabs-more-menu__title">{{ tab.title || t('documents.untitled') }}</span>
            <span
              v-if="isDirty(tab.id)"
              class="document-tab__dirty-dot"
              :title="t('documents.unsavedChanges')"
              aria-hidden="true"
            />
            <button
              v-if="!tab.pinned"
              type="button"
              class="document-tabs-more-menu__close"
              :title="t('documents.closeTab')"
              @click="handleCloseFromMoreMenu($event, tab.id)"
            >
              <X aria-hidden="true" />
            </button>
          </div>
        </div>
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

.document-tab--pinned {
  max-width: 140px;
  padding-left: 10px;
  padding-right: 10px;
  background: var(--color-bg-muted);
}

.document-tab--pinned.document-tab--active {
  background: var(--color-bg-base);
}

.document-tab--dragging {
  opacity: 0.4;
}

.document-tab--drag-before::before {
  content: '';
  position: absolute;
  left: -2px;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--color-status-in-progress, #2563eb);
  border-radius: 1px;
  z-index: 2;
}

.document-tab--drag-after::after {
  content: '';
  position: absolute;
  right: -2px;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--color-status-in-progress, #2563eb);
  border-radius: 1px;
  z-index: 2;
}

.document-tab__icon {
  width: 14px;
  height: 14px;
  flex: none;
  color: var(--color-text-muted);
}

.document-tab__pin-icon {
  width: 13px;
  height: 13px;
  flex: none;
  color: var(--color-accent);
}

.document-tab--active .document-tab__icon,
.document-tab--active .document-tab__pin-icon {
  color: var(--color-accent);
}

.document-tab__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-tab__dirty-dot {
  display: block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: var(--color-status-in-progress, #2563eb);
  flex-shrink: 0;
  margin-left: 2px;
}

.document-tab:hover .document-tab__dirty-dot {
  display: none;
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

.document-tab__close--has-dirty {
  display: none;
}

.document-tab:hover .document-tab__close--has-dirty {
  display: inline-flex;
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

.document-tabs__actions {
  display: flex;
  align-items: center;
  padding: 0 4px;
  border-left: 1px solid var(--color-border);
  background: var(--color-bg-subtle);
  flex-shrink: 0;
}

.document-tabs__more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm, 4px);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background-color 150ms ease, color 150ms ease;
}

.document-tabs__more-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.document-tabs__more-btn svg {
  width: 14px;
  height: 14px;
}

.document-tabs-context-menu {
  position: fixed;
  z-index: 1000;
  min-width: 150px;
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

.document-tabs-context-menu button svg {
  width: 13px;
  height: 13px;
  color: var(--color-text-muted);
}

.document-tabs-context-menu button:hover:not(:disabled) {
  background: var(--color-bg-hover);
}

.document-tabs-context-menu button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.document-tabs-context-menu button.danger {
  color: var(--color-danger);
}

.document-tabs-context-menu button.danger:hover:not(:disabled) {
  background: rgba(201, 74, 74, 0.08);
}

.document-tabs-more-menu {
  position: fixed;
  z-index: 1000;
  width: 240px;
  max-height: 320px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md, 6px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.document-tabs-more-menu__header {
  padding: 8px 12px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border-subtle);
}

.document-tabs-more-menu__list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.document-tabs-more-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-sm, 4px);
  font-size: 12px;
  color: var(--color-text-secondary);
  cursor: pointer;
  user-select: none;
}

.document-tabs-more-menu__item:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.document-tabs-more-menu__item--active {
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  font-weight: 500;
}

.document-tabs-more-menu__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-tabs-more-menu__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm, 3px);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity 150ms ease, background-color 150ms ease, color 150ms ease;
}

.document-tabs-more-menu__item:hover .document-tabs-more-menu__close {
  opacity: 0.8;
}

.document-tabs-more-menu__close:hover {
  opacity: 1;
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
}

.document-tabs-more-menu__close svg {
  width: 12px;
  height: 12px;
}
</style>
