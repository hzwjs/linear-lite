<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Gantt from 'frappe-gantt'
import '../styles/frappe-gantt.css'
import { useTaskStore } from '../store/taskStore'
import { useViewModeStore } from '../store/viewModeStore'
import { dateRangeToTaskPatch, getGanttRows } from '../utils/ganttChart'

const UPDATE_DEBOUNCE_MS = 320

let nextChartId = 0

const store = useTaskStore()
const viewModeStore = useViewModeStore()
const { t } = useI18n()
const chartHostRef = ref<HTMLElement | null>(null)
const chartId = `gantt-chart-${++nextChartId}`
const ganttRef = shallowRef<Gantt | null>(null)
const ganttFlatRoots = computed(
  () =>
    store.searchQuery.trim().length > 0 ||
    store.filterStatusList.length > 0 ||
    store.filterPriorityList.length > 0 ||
    store.filterAssigneeList.length > 0 ||
    store.filterLabelIds.length > 0
)

const rows = computed(() =>
  getGanttRows(store.filteredTasks, viewModeStore.viewConfig, ganttFlatRoots.value)
)
const hasRows = computed(() => rows.value.length > 0)

let persistTimer: ReturnType<typeof setTimeout> | null = null
let pendingDateUpdate:
  | {
      taskId: string
      patch: ReturnType<typeof dateRangeToTaskPatch>
    }
  | null = null

const displayRows = shallowRef<any[]>([])
let frozenIds: string[] | null = null

// Unlock sorting when user actively changes view configurations or filters
watch(
  () => [
    store.searchQuery,
    store.filterStatusList,
    store.filterPriorityList,
    store.filterAssigneeList,
    store.filterLabelIds,
    viewModeStore.viewConfig
  ],
  () => {
    frozenIds = null
  },
  { deep: true }
)

function refreshChart(nextRows = displayRows.value) {
  if (!ganttRef.value) return
  ganttRef.value.refresh(nextRows)
}

async function flushPendingDateUpdate() {
  const current = pendingDateUpdate
  pendingDateUpdate = null
  if (!current) return

  try {
    await store.updateTask(current.taskId, current.patch)
  } catch {
    await store.fetchTasks()
    refreshChart(displayRows.value)
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
  displayRows.value = rows.value
  ganttRef.value = new Gantt(`#${chartId}`, displayRows.value, {
    view_mode: 'Day',
    // 默认 true：在 scrollLeft 处于前半段时任意 mousewheel 都会向过去扩展并重绘，
    // 横向滑动也会被当成触发条件，表现为只能往更早日期跑、无法稳定滑向未来。
    infinite_padding: false,
    // 「今天」若不在任务时间范围内时 scroll 行为怪异；对齐到时间轴起点（含视图 padding）
    scroll_to: 'start',
    today_button: true,
    readonly_progress: true,
    popup: false,
    on_date_change(task, start, end) {
      if (!task?.id) return
      // Freeze the sorting order so the adjusted task doesn't visually jump to a new row
      if (!frozenIds && displayRows.value.length > 0) {
        frozenIds = displayRows.value.map(r => r.id)
      }
      scheduleDateUpdate(String(task.id), start, end)
    }
  })
  // 任务常在 fetch 完成后才写入 store，首帧可能为空，需与当前 rows 对齐
  refreshChart(displayRows.value)
})

watch(
  rows,
  (nextRows) => {
    // Break the visual freeze if collection size changes (add/delete)
    if (frozenIds && frozenIds.length !== nextRows.length) {
      frozenIds = null
    }

    let finalRows = nextRows
    if (frozenIds) {
      const rowMap = new Map(nextRows.map(r => [r.id, r]))
      const preserved: any[] = []
      for (const id of frozenIds) {
        if (rowMap.has(id)) {
          preserved.push(rowMap.get(id))
          rowMap.delete(id)
        }
      }
      // append any unmatched rows at the end just in case
      for (const r of rowMap.values()) {
        preserved.push(r)
      }
      finalRows = preserved
    }

    displayRows.value = finalRows
    refreshChart(finalRows)
  },
  { flush: 'post', immediate: true }
)

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
  flex: 1 1 0;
  min-height: 0;
  min-width: 0;
  width: 100%;
  display: flex;
  position: relative;
  background: var(--color-bg-base);

  /* Overrides to match frappe.io/gantt official example */
  --g-arrow-color: #1f2937; /* official default dark color for arrows */
  --g-bar-color: var(--color-bg-base);
  --g-bar-border: var(--color-border); /* Tasks need clear borders */
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
  --g-today-highlight: #323d4e; /* Dark prominent color matching official */
  --g-popup-actions: var(--color-bg-muted);
  --g-weekend-highlight-color: var(--color-bg-subtle);
}

.gantt-chart__canvas {
  flex: 1 1 0;
  min-height: 0;
  min-width: 0;
  width: 100%;
  overflow: auto;
  overscroll-behavior: contain;
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
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  border-radius: 0;
  background: transparent;
  overflow: visible !important; /* Forces sticky children to stick to .gantt-chart__canvas */
  overscroll-behavior-x: contain;
  -webkit-overflow-scrolling: touch;
}

.gantt-chart :deep(.gantt-container .grid-header) {
  border-bottom-color: var(--color-border-subtle);
}

.gantt-chart :deep(.gantt .bar-label) {
  font-family: inherit;
  font-weight: 500;
}

/* 1. 任务 item 明显化: add visible stroke/border and slight radius */
.gantt-chart :deep(.gantt .bar-wrapper .bar) {
  stroke-width: 1px !important;
  stroke: #e0e0e0 !important;
  rx: 4px; /* rounded corners */
  ry: 4px;
}

/* 2. 缺了 Today 标记: Ensure "Today" is styled prominently like official */
.gantt-chart :deep(.gantt-container .current-highlight) {
  width: 1px;
  background-color: var(--g-today-highlight);
}
.gantt-chart :deep(.gantt-container .current-date-highlight) {
  background-color: var(--g-today-highlight);
  color: #ffffff;
  font-weight: bold;
  border-radius: 4px;
  padding: 2px 6px; /* give it proper badge appearance */
}
.gantt-chart :deep(.gantt-container .current-ball-highlight) {
  background-color: var(--g-today-highlight);
  width: 6px !important;
  height: 6px !important;
  margin-left: -2.5px;
}

/* 3. 主/子任务的连线明显化 */
.gantt-chart :deep(.gantt .arrow) {
  stroke-width: 1.5px !important; /* Standard width */
  stroke: #1f2937 !important; /* Official arrow color */
}
</style>
