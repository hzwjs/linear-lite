<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Gantt from 'frappe-gantt'
import '../styles/frappe-gantt.css'
import { useTaskStore } from '../store/taskStore'
import { dateRangeToTaskPatch, getTopLevelGanttRows } from '../utils/ganttChart'

const UPDATE_DEBOUNCE_MS = 320

let nextChartId = 0

const store = useTaskStore()
const { t } = useI18n()
const chartHostRef = ref<HTMLElement | null>(null)
const chartId = `gantt-chart-${++nextChartId}`
const ganttRef = shallowRef<Gantt | null>(null)
const rows = computed(() => getTopLevelGanttRows(store.filteredTasks))
const hasRows = computed(() => rows.value.length > 0)

let persistTimer: ReturnType<typeof setTimeout> | null = null
let pendingDateUpdate:
  | {
      taskId: string
      patch: ReturnType<typeof dateRangeToTaskPatch>
    }
  | null = null

function refreshChart(nextRows = rows.value) {
  ganttRef.value?.refresh(nextRows)
}

async function flushPendingDateUpdate() {
  const current = pendingDateUpdate
  pendingDateUpdate = null
  if (!current) return

  try {
    await store.updateTask(current.taskId, current.patch)
  } catch {
    await store.fetchTasks()
    refreshChart(rows.value)
  }
}

function scheduleDateUpdate(taskId: string, start: Date, end: Date) {
  pendingDateUpdate = {
    taskId,
    patch: dateRangeToTaskPatch(start, end)
  }

  if (persistTimer != null) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    void flushPendingDateUpdate()
  }, UPDATE_DEBOUNCE_MS)
}

onMounted(() => {
  ganttRef.value = new Gantt(`#${chartId}`, rows.value, {
    view_mode: 'Day',
    today_button: false,
    readonly_progress: true,
    popup: false,
    on_date_change(task, start, end) {
      if (!task?.id) return
      scheduleDateUpdate(String(task.id), start, end)
    }
  })
})

watch(rows, (nextRows) => {
  refreshChart(nextRows)
})

onUnmounted(() => {
  if (persistTimer != null) {
    clearTimeout(persistTimer)
    persistTimer = null
  }

  const activeElement = document.activeElement
  if (activeElement instanceof HTMLElement && chartHostRef.value?.contains(activeElement)) {
    activeElement.blur()
  }

  ganttRef.value?.clear?.()
  ganttRef.value = null
  pendingDateUpdate = null
})
</script>

<template>
  <div class="gantt-chart">
    <div v-if="!hasRows" class="gantt-chart__empty">
      {{ t('boardView.ganttEmpty') }}
    </div>
    <div :id="chartId" ref="chartHostRef" class="gantt-chart__canvas" />
  </div>
</template>

<style scoped>
.gantt-chart {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
  background: var(--color-bg-base);
  --g-arrow-color: var(--color-border);
  --g-bar-color: var(--color-bg-base);
  --g-bar-border: var(--color-border);
  --g-tick-color-thick: var(--color-border-subtle);
  --g-tick-color: var(--color-border-subtle);
  --g-actions-background: var(--color-bg-muted);
  --g-border-color: var(--color-border-subtle);
  --g-text-muted: var(--color-text-muted);
  --g-text-light: var(--color-text-primary);
  --g-text-dark: var(--color-text-primary);
  --g-progress-color: var(--color-accent-muted);
  --g-handle-color: var(--color-accent);
  --g-weekend-label-color: var(--color-bg-muted);
  --g-expected-progress: var(--color-accent-muted);
  --g-header-background: var(--color-bg-base);
  --g-row-color: var(--color-bg-base);
  --g-row-border-color: var(--color-border-subtle);
  --g-today-highlight: var(--color-accent);
  --g-popup-actions: var(--color-bg-muted);
  --g-weekend-highlight-color: var(--color-bg-subtle);
}

.gantt-chart__canvas {
  flex: 1;
  min-height: 0;
}

.gantt-chart__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  pointer-events: none;
  z-index: 1;
}

.gantt-chart :deep(.gantt-container) {
  height: 100%;
  border-radius: 0;
  background: transparent;
}

.gantt-chart :deep(.gantt-container .grid-header) {
  border-bottom-color: var(--color-border-subtle);
}

.gantt-chart :deep(.gantt .bar-label) {
  font-family: inherit;
}
</style>
