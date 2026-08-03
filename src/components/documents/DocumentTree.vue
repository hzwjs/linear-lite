<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import DocumentTreeNode from './DocumentTreeNode.vue'

const props = defineProps<{
  projectId: number
  nodes: ProjectDocumentTreeNode[]
  activeId: number | null
  moving: boolean
}>()

const emit = defineEmits<{
  select: [documentId: number]
  createChild: [parentDocumentId: number]
  archive: [documentId: number]
  toggleFavorite: [node: ProjectDocumentTreeNode]
  move: [payload: { documentId: number; parentDocumentId: number | null; previousSiblingId: number | null }]
}>()

const { t } = useI18n()
const STORAGE_PREFIX = 'linear-lite:document-tree-expanded:'
const dragHintId = `${useId()}-document-tree-drag-hint`

function readExpanded(): Set<number> {
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}${props.projectId}`)
    if (!raw) return new Set()
    const ids = JSON.parse(raw)
    return new Set(Array.isArray(ids) ? ids.filter(Number.isInteger) : [])
  } catch {
    return new Set()
  }
}

const expandedIds = ref(readExpanded())
const treeRef = ref<HTMLElement | null>(null)
const draggingDocumentId = ref<number | null>(null)

watch(
  () => props.projectId,
  () => { expandedIds.value = readExpanded() }
)

function persistExpanded() {
  localStorage.setItem(`${STORAGE_PREFIX}${props.projectId}`, JSON.stringify([...expandedIds.value]))
}

function toggle(documentId: number) {
  const next = new Set(expandedIds.value)
  if (next.has(documentId)) next.delete(documentId)
  else next.add(documentId)
  expandedIds.value = next
  persistExpanded()
}

function expandActiveAncestors(documentId: number) {
  const nodesById = new Map(props.nodes.map((node) => [node.id, node]))
  const nextExpandedIds = new Set(expandedIds.value)
  let currentId = documentId

  while (true) {
    const currentNode = nodesById.get(currentId)
    if (!currentNode || currentNode.parentDocumentId == null) break
    nextExpandedIds.add(currentNode.parentDocumentId)
    currentId = currentNode.parentDocumentId
  }

  if (nextExpandedIds.size === expandedIds.value.size) return
  expandedIds.value = nextExpandedIds
  persistExpanded()
}

async function locateActiveDocument() {
  const activeId = props.activeId
  if (activeId == null) return
  // 先展开当前文档的祖先，再滚动当前行，确保从搜索或深链接进入时树中可见。
  expandActiveAncestors(activeId)
  await nextTick()
  if (activeId !== props.activeId) return
  const activeRow = treeRef.value?.querySelector<HTMLElement>(`[data-document-tree-id="${activeId}"]`)
  if (activeRow && typeof activeRow.scrollIntoView === 'function') {
    activeRow.scrollIntoView({ block: 'nearest' })
  }
}

watch(
  [() => props.activeId, () => props.nodes],
  () => { void locateActiveDocument() },
  { immediate: true }
)

const orderedNodes = computed(() => [...props.nodes].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id))

const childrenByParent = computed(() => {
  const map = new Map<number | null, ProjectDocumentTreeNode[]>()
  for (const node of orderedNodes.value) {
    const list = map.get(node.parentDocumentId) ?? []
    list.push(node)
    map.set(node.parentDocumentId, list)
  }
  return map
})

const rootNodes = computed(() => childrenByParent.value.get(null) ?? [])

function onNavigateKey(payload: { event: KeyboardEvent; documentId: number }) {
  const { event, documentId } = payload
  const buttons = Array.from(treeRef.value?.querySelectorAll<HTMLButtonElement>('[data-document-tree-id]') ?? [])
  const index = buttons.findIndex((button) => Number(button.dataset.documentTreeId) === documentId)
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    buttons[index + (event.key === 'ArrowDown' ? 1 : -1)]?.focus()
    return
  }
  const node = props.nodes.find((candidate) => candidate.id === documentId)
  if (!node) return
  if (event.key === 'ArrowRight' && childrenByParent.value.has(documentId) && !expandedIds.value.has(documentId)) {
    event.preventDefault()
    toggle(documentId)
  } else if (event.key === 'ArrowLeft' && expandedIds.value.has(documentId)) {
    event.preventDefault()
    toggle(documentId)
  } else if (event.key === 'ArrowLeft' && node.parentDocumentId != null) {
    event.preventDefault()
    buttons.find((button) => Number(button.dataset.documentTreeId) === node.parentDocumentId)?.focus()
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    emit('select', documentId)
  }
}
</script>

<template>
  <div ref="treeRef" class="document-tree">
    <ul role="tree" :aria-label="t('documents.treeLabel')">
      <DocumentTreeNode
        v-for="(node, index) in rootNodes"
        :key="node.id"
        :node="node"
        :depth="0"
        :children-by-parent="childrenByParent"
        :expanded-ids="expandedIds"
        :active-id="activeId"
        :previous-sibling-id="index > 0 ? rootNodes[index - 1]!.id : null"
        :previous-previous-sibling-id="index > 1 ? rootNodes[index - 2]!.id : null"
        :next-sibling-id="index < rootNodes.length - 1 ? rootNodes[index + 1]!.id : null"
        :dragging-document-id="draggingDocumentId"
        :drag-hint-id="dragHintId"
        :moving="moving"
        @select="emit('select', $event)"
        @toggle="toggle"
        @create-child="emit('createChild', $event)"
        @archive="emit('archive', $event)"
        @toggle-favorite="emit('toggleFavorite', $event)"
        @move="emit('move', $event)"
        @navigate-key="onNavigateKey"
        @drag-start="draggingDocumentId = $event"
        @drag-end="draggingDocumentId = null"
      />
    </ul>
    <p :id="dragHintId" class="sr-only">{{ t('documents.dragHint') }}</p>
  </div>
</template>

<style scoped>
.document-tree { min-height: 0; }
.document-tree > ul { margin: 0; padding: 0; list-style: none; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
</style>
