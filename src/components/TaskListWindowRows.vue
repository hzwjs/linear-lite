<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { TaskRow } from '../utils/taskView'

const props = defineProps<{
  rows: TaskRow[]
  scrollRoot: HTMLElement | null
  rowHeight: number
  layoutKey: string
  topInset: number
  pinnedTaskId: string | null
}>()

const OVERSCAN = 6
const hostRef = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewportHeight = ref(0)
const groupTop = ref(0)
let detachRoot: (() => void) | undefined

function syncScroll() {
  if (!props.scrollRoot) return
  scrollTop.value = props.scrollRoot.scrollTop
  viewportHeight.value = props.scrollRoot.clientHeight
}

function syncLayout() {
  const root = props.scrollRoot
  const host = hostRef.value
  if (!root || !host) return
  groupTop.value = host.getBoundingClientRect().top - root.getBoundingClientRect().top + root.scrollTop
  syncScroll()
}

watch(() => props.scrollRoot, root => {
  detachRoot?.()
  detachRoot = undefined
  if (!root) return
  root.addEventListener('scroll', syncScroll, { passive: true })
  const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(syncLayout)
  observer?.observe(root)
  detachRoot = () => { root.removeEventListener('scroll', syncScroll); observer?.disconnect() }
  syncLayout()
}, { immediate: true, flush: 'post' })

// 组折叠、子任务展开、排序和范围变化后，偏移在 DOM 更新后重算。
watch([() => props.rows, () => props.layoutKey], syncLayout, { flush: 'post' })
onMounted(syncLayout)

const rowIndexById = computed(() => new Map(props.rows.map((row, index) => [row.task.id, index])))
const windowItems = computed(() => {
  const count = props.rows.length
  const start = Math.min(count, Math.max(0, Math.floor((scrollTop.value - groupTop.value) / props.rowHeight) - OVERSCAN))
  const end = Math.min(count, Math.max(start, Math.ceil((scrollTop.value + viewportHeight.value - groupTop.value) / props.rowHeight) + OVERSCAN))
  const indices = new Set<number>()
  for (let index = start; index < end; index++) indices.add(index)
  if (props.pinnedTaskId != null) {
    const pinned = rowIndexById.value.get(props.pinnedTaskId)
    if (pinned != null) indices.add(pinned)
  }
  let previous = -1
  return [...indices].sort((a, b) => a - b).map(index => {
    const item = { row: props.rows[index]!, index, gap: (index - previous - 1) * props.rowHeight }
    previous = index
    return item
  })
})
const bottomGap = computed(() => {
  const last = windowItems.value[windowItems.value.length - 1]
  return (props.rows.length - (last == null ? 0 : last.index + 1)) * props.rowHeight
})

async function ensureTaskVisible(taskId: string): Promise<boolean> {
  // 让调用方同一轮的排序/筛选更新入队，等待 Vue 回写新的行集合。
  await Promise.resolve()
  await nextTick()
  const index = rowIndexById.value.get(taskId)
  const root = props.scrollRoot
  if (index == null || !root || !hostRef.value) return false
  syncLayout()
  const top = groupTop.value + index * props.rowHeight
  const bottom = top + props.rowHeight
  if (top < root.scrollTop + props.topInset) root.scrollTop = Math.max(0, top - props.topInset)
  else if (bottom > root.scrollTop + root.clientHeight) root.scrollTop = Math.max(0, bottom - root.clientHeight)
  syncScroll()
  await nextTick()
  return true
}

defineExpose({ ensureTaskVisible })
onBeforeUnmount(() => detachRoot?.())
</script>

<template>
  <div ref="hostRef" class="task-window-rows" role="list">
    <template v-for="item in windowItems" :key="item.row.task.id">
      <div v-if="item.gap > 0" :style="{ height: `${item.gap}px` }" aria-hidden="true" />
      <slot :row="item.row" :index="item.index" :total="rows.length" />
    </template>
    <div v-if="bottomGap > 0" :style="{ height: `${bottomGap}px` }" aria-hidden="true" />
  </div>
</template>
