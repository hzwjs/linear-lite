<script setup lang="ts">
import { Archive, ChevronRight, FileText, MoreHorizontal, Plus, Star } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'

const props = defineProps<{
  node: ProjectDocumentTreeNode
  depth: number
  childrenByParent: Map<number | null, ProjectDocumentTreeNode[]>
  expandedIds: Set<number>
  activeId: number | null
  previousSiblingId: number | null
  previousPreviousSiblingId: number | null
  nextSiblingId: number | null
  draggingDocumentId: number | null
  dragHintId: string
  moving: boolean
}>()

const emit = defineEmits<{
  select: [documentId: number]
  toggle: [documentId: number]
  createChild: [parentDocumentId: number]
  archive: [documentId: number]
  toggleFavorite: [node: ProjectDocumentTreeNode]
  move: [payload: { documentId: number; parentDocumentId: number | null; previousSiblingId: number | null }]
  navigateKey: [payload: { event: KeyboardEvent; documentId: number }]
  dragStart: [documentId: number]
  dragEnd: []
}>()

const { t } = useI18n()
const menuOpen = ref(false)
const dragPlacement = ref<'before' | 'inside' | 'after' | null>(null)
const children = computed(() => props.childrenByParent.get(props.node.id) ?? [])
const hasChildren = computed(() => children.value.length > 0)
const isExpanded = computed(() => props.expandedIds.has(props.node.id))
const isDragging = computed(() => props.draggingDocumentId === props.node.id)
const dropLabel = computed(() => {
  if (dragPlacement.value == null) return ''
  const labelKeys = {
    before: 'documents.dropBefore',
    inside: 'documents.dropInside',
    after: 'documents.dropAfter'
  } as const
  return t(labelKeys[dragPlacement.value], { title: props.node.title })
})
const nodesById = computed(() => {
  const entries = [...props.childrenByParent.values()].flat().map((node) => [node.id, node] as const)
  return new Map(entries)
})

watch(
  () => props.draggingDocumentId,
  (documentId) => {
    if (documentId != null) menuOpen.value = false
  }
)
const parentNode = computed(() => {
  if (props.node.parentDocumentId == null) return null
  for (const nodes of props.childrenByParent.values()) {
    const match = nodes.find((candidate) => candidate.id === props.node.parentDocumentId)
    if (match) return match
  }
  return null
})

function moveUp() {
  emit('move', {
    documentId: props.node.id,
    parentDocumentId: props.node.parentDocumentId,
    previousSiblingId: props.previousPreviousSiblingId
  })
}

function moveDown() {
  if (props.nextSiblingId == null) return
  emit('move', {
    documentId: props.node.id,
    parentDocumentId: props.node.parentDocumentId,
    previousSiblingId: props.nextSiblingId
  })
}

function indent() {
  if (props.previousSiblingId == null) return
  if (!props.expandedIds.has(props.previousSiblingId)) emit('toggle', props.previousSiblingId)
  emit('move', {
    documentId: props.node.id,
    parentDocumentId: props.previousSiblingId,
    previousSiblingId: null
  })
}

function outdent() {
  if (!parentNode.value) return
  emit('move', {
    documentId: props.node.id,
    parentDocumentId: parentNode.value.parentDocumentId,
    previousSiblingId: parentNode.value.id
  })
}

function isInsideDraggingSubtree(documentId: number) {
  const sourceId = props.draggingDocumentId
  if (sourceId == null) return false
  let current = nodesById.value.get(documentId)
  while (current) {
    if (current.id === sourceId) return true
    current = current.parentDocumentId == null ? undefined : nodesById.value.get(current.parentDocumentId)
  }
  return false
}

function onDragStart(event: DragEvent) {
  if (props.moving || !event.dataTransfer) {
    event.preventDefault()
    return
  }
  menuOpen.value = false
  event.dataTransfer?.setData('application/x-linear-lite-document-id', String(props.node.id))
  event.dataTransfer.effectAllowed = 'move'
  const row = (event.currentTarget as HTMLElement).closest<HTMLElement>('.document-tree-row')
  if (row) {
    const bounds = row.getBoundingClientRect()
    event.dataTransfer.setDragImage(row, event.clientX - bounds.left, event.clientY - bounds.top)
  }
  emit('dragStart', props.node.id)
}

function onDragOver(event: DragEvent) {
  // 拖拽源只读取树组件维护的唯一状态，避免依赖浏览器在 dragover 阶段暴露 DataTransfer 数据。
  if (props.draggingDocumentId == null || isInsideDraggingSubtree(props.node.id)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  const row = event.currentTarget as HTMLElement
  const bounds = row.getBoundingClientRect()
  const ratio = (event.clientY - bounds.top) / bounds.height
  dragPlacement.value = ratio < 0.28 ? 'before' : ratio > 0.72 ? 'after' : 'inside'
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  const documentId = props.draggingDocumentId
  const placement = dragPlacement.value
  dragPlacement.value = null
  if (documentId == null || isInsideDraggingSubtree(props.node.id) || placement == null) return
  if (placement === 'inside') {
    // 成为子文档后立即展开目标，确保移动结果在树中可见。
    if (!isExpanded.value) emit('toggle', props.node.id)
    emit('move', { documentId, parentDocumentId: props.node.id, previousSiblingId: null })
  } else if (placement === 'before') {
    emit('move', {
      documentId,
      parentDocumentId: props.node.parentDocumentId,
      previousSiblingId: props.previousSiblingId
    })
  } else {
    emit('move', {
      documentId,
      parentDocumentId: props.node.parentDocumentId,
      previousSiblingId: props.node.id
    })
  }
  emit('dragEnd')
}

function onDragEnd() {
  dragPlacement.value = null
  emit('dragEnd')
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (props.moving) return
  if (event.altKey && event.key === 'ArrowUp' && props.previousSiblingId != null) {
    event.preventDefault()
    moveUp()
  } else if (event.altKey && event.key === 'ArrowDown' && props.nextSiblingId != null) {
    event.preventDefault()
    moveDown()
  } else if (event.altKey && event.key === 'ArrowRight' && props.previousSiblingId != null) {
    event.preventDefault()
    indent()
  } else if (event.altKey && event.key === 'ArrowLeft' && parentNode.value) {
    event.preventDefault()
    outdent()
  } else {
    emit('navigateKey', { event, documentId: props.node.id })
  }
}
</script>

<template>
  <li
    class="document-tree-branch"
    role="treeitem"
    :aria-level="depth + 1"
    :aria-selected="activeId === node.id"
    :aria-expanded="hasChildren ? isExpanded : undefined"
  >
    <div
      class="document-tree-row"
      :class="[
        { 'document-tree-row--active': activeId === node.id },
        { 'document-tree-row--dragging': isDragging },
        dragPlacement && `document-tree-row--drop-${dragPlacement}`
      ]"
      :style="{ '--document-depth': depth }"
      :draggable="!moving"
      @dragstart="onDragStart"
      @dragover="onDragOver"
      @dragleave="dragPlacement = null"
      @drop="onDrop"
      @dragend="onDragEnd"
    >
      <button
        type="button"
        class="document-tree-row__toggle"
        :class="{ 'document-tree-row__toggle--hidden': !hasChildren }"
        :aria-label="isExpanded ? t('documents.collapse') : t('documents.expand')"
        :aria-expanded="hasChildren ? isExpanded : undefined"
        :tabindex="hasChildren ? 0 : -1"
        @click.stop="emit('toggle', node.id)"
      >
        <ChevronRight :class="{ 'document-tree-row__chevron--expanded': isExpanded }" aria-hidden="true" />
      </button>
      <button
        type="button"
        class="document-tree-row__main"
        :data-document-tree-id="node.id"
        :aria-current="activeId === node.id ? 'page' : undefined"
        :aria-describedby="dragHintId"
        aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown Alt+ArrowLeft Alt+ArrowRight"
        :title="node.title"
        @click="emit('select', node.id)"
        @keydown="onDocumentKeydown"
      >
        <FileText aria-hidden="true" />
        <span>{{ node.title }}</span>
        <Star v-if="node.favorited" class="document-tree-row__favorite" aria-hidden="true" />
      </button>
      <button
        type="button"
        class="document-tree-row__menu-trigger"
        :aria-label="t('documents.actionsFor', { title: node.title })"
        :aria-expanded="menuOpen"
        aria-haspopup="menu"
        @click.stop="menuOpen = !menuOpen"
      >
        <MoreHorizontal aria-hidden="true" />
      </button>
      <div v-if="menuOpen" class="document-tree-row__menu" role="menu">
        <button type="button" role="menuitem" @click="menuOpen = false; emit('toggleFavorite', node)">
          <Star aria-hidden="true" />{{ node.favorited ? t('documents.removeFavorite') : t('documents.addFavorite') }}
        </button>
        <button type="button" role="menuitem" @click="menuOpen = false; emit('createChild', node.id)">
          <Plus aria-hidden="true" />{{ t('documents.newChild') }}
        </button>
        <button type="button" role="menuitem" class="danger" @click="menuOpen = false; emit('archive', node.id)">
          <Archive aria-hidden="true" />{{ t('documents.archive') }}
        </button>
      </div>
      <span v-if="dragPlacement" class="document-tree-row__drop-label" aria-hidden="true">
        {{ dropLabel }}
      </span>
    </div>

    <ul v-if="hasChildren && isExpanded" role="group">
      <DocumentTreeNode
        v-for="(child, index) in children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        :children-by-parent="childrenByParent"
        :expanded-ids="expandedIds"
        :active-id="activeId"
        :previous-sibling-id="index > 0 ? children[index - 1]!.id : null"
        :previous-previous-sibling-id="index > 1 ? children[index - 2]!.id : null"
        :next-sibling-id="index < children.length - 1 ? children[index + 1]!.id : null"
        :dragging-document-id="draggingDocumentId"
        :drag-hint-id="dragHintId"
        :moving="moving"
        @select="emit('select', $event)"
        @toggle="emit('toggle', $event)"
        @create-child="emit('createChild', $event)"
        @archive="emit('archive', $event)"
        @toggle-favorite="emit('toggleFavorite', $event)"
        @move="emit('move', $event)"
        @navigate-key="emit('navigateKey', $event)"
        @drag-start="emit('dragStart', $event)"
        @drag-end="emit('dragEnd')"
      />
    </ul>
  </li>
</template>

<style scoped>
.document-tree-branch,
.document-tree-branch ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.document-tree-row {
  position: relative;
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 28px;
  align-items: center;
  min-height: 34px;
  padding-left: calc(8px + var(--document-depth) * 16px);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
}

.document-tree-row[draggable='true'] { cursor: grab; }

.document-tree-row--dragging {
  background: var(--color-bg-hover);
  cursor: grabbing;
  opacity: 0.48;
}

.document-tree-row:hover,
.document-tree-row--active {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.document-tree-row--active {
  font-weight: var(--font-weight-medium);
}

.document-tree-row--drop-before::before,
.document-tree-row--drop-after::after {
  position: absolute;
  right: 8px;
  left: calc(8px + var(--document-depth) * 16px);
  height: 2px;
  background: var(--color-accent);
  content: '';
}

.document-tree-row--drop-before::before { top: -1px; }
.document-tree-row--drop-after::after { bottom: -1px; }
.document-tree-row--drop-inside {
  background: var(--color-accent-muted);
  outline: 2px solid var(--color-accent-muted-border);
}

.document-tree-row__toggle,
.document-tree-row__menu-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0;
  border-radius: var(--radius-sm);
}

.document-tree-row__toggle svg,
.document-tree-row__menu-trigger svg,
.document-tree-row__main svg {
  width: 14px;
  height: 14px;
  flex: none;
}

.document-tree-row__toggle--hidden { visibility: hidden; }
.document-tree-row__chevron--expanded { transform: rotate(90deg); }
.document-tree-row__menu-trigger { opacity: 0; }
.document-tree-row:hover .document-tree-row__menu-trigger,
.document-tree-row__menu-trigger:focus-visible { opacity: 1; }

.document-tree-row__main {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-start;
  gap: 7px;
  padding: 4px;
  text-align: left;
}

.document-tree-row__main span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-tree-row__main .document-tree-row__favorite {
  width: 12px;
  height: 12px;
  color: var(--color-status-warning);
  fill: currentColor;
}

.document-tree-row button:focus-visible {
  outline: 2px solid var(--color-border-strong);
  outline-offset: -2px;
}

.document-tree-row__menu {
  position: absolute;
  z-index: 20;
  top: 30px;
  right: 0;
  width: 168px;
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
  box-shadow: var(--shadow-popover);
}

.document-tree-row__menu button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: var(--radius-sm);
  text-align: left;
}

.document-tree-row__menu button:hover { background: var(--color-bg-hover); }
.document-tree-row__menu svg { width: 14px; height: 14px; }
.document-tree-row__menu .danger {
  margin-top: 4px;
  border-top: 1px solid var(--color-border-subtle);
  border-radius: 0 0 var(--radius-sm) var(--radius-sm);
  color: var(--color-danger);
}

.document-tree-row__drop-label {
  position: absolute;
  z-index: 2;
  right: 6px;
  max-width: calc(100% - 32px);
  overflow: hidden;
  padding: 2px 6px;
  border: 1px solid var(--color-accent-muted-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-size: var(--font-size-xs);
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
}

.document-tree-row--drop-before .document-tree-row__drop-label { top: 0; transform: translateY(-55%); }
.document-tree-row--drop-inside .document-tree-row__drop-label { top: 50%; transform: translateY(-50%); }
.document-tree-row--drop-after .document-tree-row__drop-label { bottom: 0; transform: translateY(55%); }

</style>
