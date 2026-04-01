<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Task } from '../types/domain'
import { useTaskStore } from '../store/taskStore'

const props = defineProps<{
  task: Task
}>()

const { t } = useI18n()
const store = useTaskStore()

const cellRef = ref<HTMLElement | null>(null)
const dragging = ref(false)
const dragPreview = ref<number | null>(null)

const basePercent = computed(() => {
  const n = props.task.progressPercent ?? 0
  return Math.min(100, Math.max(0, n))
})

const displayPercent = computed(() =>
  dragPreview.value != null ? dragPreview.value : basePercent.value
)

watch(
  () => props.task.id,
  () => {
    dragPreview.value = null
    dragging.value = false
  }
)

function percentFromClientX(clientX: number): number {
  const el = cellRef.value
  if (!el) return basePercent.value
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0) return basePercent.value
  const x = clientX - rect.left
  return Math.min(100, Math.max(0, Math.round((x / rect.width) * 100)))
}

function onPointerDown(e: PointerEvent) {
  e.stopPropagation()
  if (e.button !== 0) return
  const el = cellRef.value
  if (!el) return
  el.setPointerCapture(e.pointerId)
  dragging.value = true
  dragPreview.value = percentFromClientX(e.clientX)
}

function onPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  dragPreview.value = percentFromClientX(e.clientX)
}

async function finishPointer(e: PointerEvent) {
  if (!dragging.value) return
  e.stopPropagation()
  const el = cellRef.value
  if (el) {
    try {
      el.releasePointerCapture(e.pointerId)
    } catch {
      /* ignore */
    }
  }
  dragging.value = false
  const next = dragPreview.value
  dragPreview.value = null
  if (next == null) return
  if (next === basePercent.value) return
  try {
    await store.updateTask(props.task.id, { progressPercent: next })
  } catch {
    /* store.error 已更新 */
  }
}

function onProgressKeydown(e: KeyboardEvent) {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
  e.preventDefault()
  const delta = e.key === 'ArrowLeft' ? -5 : 5
  const n = Math.min(100, Math.max(0, basePercent.value + delta))
  if (n === basePercent.value) return
  void store.updateTask(props.task.id, { progressPercent: n })
}

onUnmounted(() => {
  dragging.value = false
  dragPreview.value = null
})
</script>

<template>
  <span
    ref="cellRef"
    class="task-meta task-meta-progress-cell task-row-progress-cell"
    role="slider"
    :aria-valuemin="0"
    :aria-valuemax="100"
    :aria-valuenow="displayPercent"
    :aria-label="t('taskList.progressDragAria')"
    :title="`${t('taskList.columnProgress')}: ${displayPercent}%`"
    tabindex="0"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="finishPointer"
    @pointercancel="finishPointer"
    @click.stop
    @keydown.stop="onProgressKeydown"
  >
    <span class="task-progress-track" aria-hidden="true">
      <span class="task-progress-fill" :style="{ width: `${displayPercent}%` }" />
    </span>
    <span class="task-progress-pct">{{ displayPercent }}%</span>
  </span>
</template>

<style scoped>
.task-meta.task-meta-progress-cell {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  white-space: nowrap;
  min-width: 0;
}
.task-progress-track {
  width: 52px;
  height: 5px;
  border-radius: 999px;
  background: var(--color-bg-muted);
  overflow: hidden;
  flex-shrink: 0;
}
.task-progress-fill {
  display: block;
  height: 100%;
  min-width: 0;
  border-radius: 999px;
  background: var(--color-accent, #5f6eea);
  transition: width var(--transition-fast);
}
.task-progress-pct {
  font-variant-numeric: tabular-nums;
  min-width: 2.25rem;
  text-align: right;
}
.task-row-progress-cell {
  cursor: ew-resize;
  touch-action: none;
  user-select: none;
}
.task-row-progress-cell:focus-visible {
  outline: 2px solid var(--color-status-done);
  outline-offset: 2px;
  border-radius: var(--border-radius-sm, 4px);
}
</style>
