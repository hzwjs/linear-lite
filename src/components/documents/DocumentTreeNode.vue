<script setup lang="ts">
import { ChevronRight, FileText, MoreHorizontal, Plus, Archive } from 'lucide-vue-next'
import { computed, ref } from 'vue'
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
}>()

const emit = defineEmits<{
  select: [documentId: number]
  toggle: [documentId: number]
  createChild: [parentDocumentId: number]
  archive: [documentId: number]
  move: [payload: { documentId: number; parentDocumentId: number | null; previousSiblingId: number | null }]
  navigateKey: [payload: { event: KeyboardEvent; documentId: number }]
}>()

const { t } = useI18n()
const menuOpen = ref(false)
const dragPlacement = ref<'before' | 'inside' | 'after' | null>(null)
const children = computed(() => props.childrenByParent.get(props.node.id) ?? [])
const hasChildren = computed(() => children.value.length > 0)
const isExpanded = computed(() => props.expandedIds.has(props.node.id))
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

function onDragStart(event: DragEvent) {
  event.dataTransfer?.setData('application/x-linear-lite-document-id', String(props.node.id))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDragOver(event: DragEvent) {
  const source = Number(event.dataTransfer?.getData('application/x-linear-lite-document-id'))
  if (source === props.node.id) return
  event.preventDefault()
  const row = event.currentTarget as HTMLElement
  const bounds = row.getBoundingClientRect()
  const ratio = (event.clientY - bounds.top) / bounds.height
  dragPlacement.value = ratio < 0.28 ? 'before' : ratio > 0.72 ? 'after' : 'inside'
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  const documentId = Number(event.dataTransfer?.getData('application/x-linear-lite-document-id'))
  const placement = dragPlacement.value
  dragPlacement.value = null
  if (!Number.isInteger(documentId) || documentId === props.node.id || placement == null) return
  if (placement === 'inside') {
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
        dragPlacement && `document-tree-row--drop-${dragPlacement}`
      ]"
      :style="{ '--document-depth': depth }"
      draggable="true"
      @dragstart="onDragStart"
      @dragover="onDragOver"
      @dragleave="dragPlacement = null"
      @drop="onDrop"
      @dragend="dragPlacement = null"
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
        :title="node.title"
        @click="emit('select', node.id)"
        @keydown="emit('navigateKey', { event: $event, documentId: node.id })"
      >
        <FileText aria-hidden="true" />
        <span>{{ node.title }}</span>
      </button>
      <button
        type="button"
        class="document-tree-row__menu-trigger"
        :aria-label="t('documents.actionsFor', { title: node.title })"
        :aria-expanded="menuOpen"
        @click.stop="menuOpen = !menuOpen"
      >
        <MoreHorizontal aria-hidden="true" />
      </button>
      <div v-if="menuOpen" class="document-tree-row__menu" role="menu">
        <button type="button" role="menuitem" @click="menuOpen = false; emit('createChild', node.id)">
          <Plus aria-hidden="true" />{{ t('documents.newChild') }}
        </button>
        <button v-if="previousSiblingId != null" type="button" role="menuitem" @click="menuOpen = false; moveUp()">
          {{ t('documents.moveUp') }}
        </button>
        <button v-if="nextSiblingId != null" type="button" role="menuitem" @click="menuOpen = false; moveDown()">
          {{ t('documents.moveDown') }}
        </button>
        <button v-if="previousSiblingId != null" type="button" role="menuitem" @click="menuOpen = false; indent()">
          {{ t('documents.indent') }}
        </button>
        <button v-if="parentNode" type="button" role="menuitem" @click="menuOpen = false; outdent()">
          {{ t('documents.outdent') }}
        </button>
        <button type="button" role="menuitem" class="danger" @click="menuOpen = false; emit('archive', node.id)">
          <Archive aria-hidden="true" />{{ t('documents.archive') }}
        </button>
      </div>
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
        @select="emit('select', $event)"
        @toggle="emit('toggle', $event)"
        @create-child="emit('createChild', $event)"
        @archive="emit('archive', $event)"
        @move="emit('move', $event)"
        @navigate-key="emit('navigateKey', $event)"
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
.document-tree-row--drop-inside { outline: 2px solid var(--color-accent-muted-border); }

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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.document-tree-row__menu .danger { color: var(--color-danger); }
</style>
