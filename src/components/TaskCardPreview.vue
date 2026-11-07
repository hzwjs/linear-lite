<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Task } from '../types/domain'
import { taskApi } from '../services/api/task'
import { assigneeDisplayLabel } from '../utils/taskAssigneeDisplay'
import { formatBeijingDate } from '../utils/beijingTime'
import { getStatusLabel } from '../utils/enumLabels'
import { renderMarkdown } from '../utils/markdown'
import TaskStatusIcon from './TaskStatusIcon.vue'

const props = defineProps<{ task: Task }>()
const { t } = useI18n()
const trigger = ref<HTMLElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const detail = ref<Task | null>(null)
const position = ref({ left: 0, top: 0 })
let openTimer: ReturnType<typeof setTimeout> | undefined
let closeTimer: ReturnType<typeof setTimeout> | undefined
let requestId = 0
const OPEN_DELAY = 400
const CLOSE_DELAY = 150
const VIEWPORT_MARGIN = 8
const PANEL_WIDTH = 340

const description = computed(() => {
  if (!detail.value?.description) return ''
  const template = document.createElement('template')
  template.innerHTML = renderMarkdown(detail.value.description)
  return (template.content.textContent ?? '').trim()
})

function close() {
  clearTimeout(openTimer)
  clearTimeout(closeTimer)
  requestId++
  detail.value = null
  window.removeEventListener('scroll', close, true)
  window.removeEventListener('resize', close)
  window.removeEventListener('dragstart', close)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('task-preview-enter', close)
}

function scheduleClose() {
  clearTimeout(openTimer)
  if (!detail.value) requestId++
  closeTimer = setTimeout(close, CLOSE_DELAY)
}

function keepOpen() {
  clearTimeout(closeTimer)
}

function enter() {
  clearTimeout(closeTimer)
  if (detail.value) return
  window.dispatchEvent(new Event('task-preview-enter'))
  window.addEventListener('task-preview-enter', close)
  window.addEventListener('scroll', close, true)
  window.addEventListener('resize', close)
  window.addEventListener('dragstart', close)
  window.addEventListener('keydown', onKeydown)
  clearTimeout(openTimer)
  openTimer = setTimeout(async () => {
    const id = ++requestId
    try {
      const task = await taskApi.get(props.task.id)
      if (id !== requestId || !trigger.value) return
      const rect = trigger.value.getBoundingClientRect()
      const left = rect.right + PANEL_WIDTH <= window.innerWidth - VIEWPORT_MARGIN
        ? rect.right : Math.max(VIEWPORT_MARGIN, rect.left - PANEL_WIDTH)
      position.value = { left, top: rect.top }
      detail.value = task
      await nextTick()
      if (panel.value) {
        position.value.top = Math.max(VIEWPORT_MARGIN, Math.min(rect.top, window.innerHeight - panel.value.offsetHeight - VIEWPORT_MARGIN))
      }
    } catch {
      // 详情加载失败时保留卡片的点击入口。
    }
  }, OPEN_DELAY)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}

onBeforeUnmount(close)
</script>

<template>
  <div ref="trigger" @mouseenter="enter" @mouseleave="scheduleClose" @mousedown="close">
    <slot />
  </div>
  <Teleport to="body">
    <aside
      v-if="detail"
      ref="panel"
      class="task-preview"
      :style="{ left: `${position.left}px`, top: `${position.top}px` }"
      @mouseenter="keepOpen"
      @mouseleave="scheduleClose"
    >
      <div class="preview-id">{{ detail.id }}</div>
      <div class="preview-title">{{ detail.title }}</div>
      <div class="preview-fields">
        <span>{{ t('common.status') }}</span><span class="preview-status"><TaskStatusIcon :status="detail.status" :size="14" />{{ getStatusLabel(detail.status) }}</span>
        <span>{{ t('common.assignee') }}</span><span>{{ assigneeDisplayLabel(detail, t('common.unassigned')) }}</span>
        <span>{{ t('fieldLabel.progressPercent') }}</span><span>{{ detail.progressPercent }}%</span>
        <template v-if="detail.plannedStartDate != null"><span>{{ t('common.plannedStartDate') }}</span><span>{{ formatBeijingDate(detail.plannedStartDate) }}</span></template>
        <template v-if="detail.dueDate != null"><span>{{ t('common.dueDate') }}</span><span>{{ formatBeijingDate(detail.dueDate) }}</span></template>
      </div>
      <p v-if="description" class="preview-description">{{ description }}</p>
    </aside>
  </Teleport>
</template>

<style scoped>
.task-preview {
  position: fixed;
  z-index: 1000;
  box-sizing: border-box;
  width: min(340px, calc(100vw - 16px));
  max-height: calc(100vh - 16px);
  overflow: auto;
  padding: 16px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-popover);
  color: var(--color-text-primary);
}
.preview-id { color: var(--color-text-muted); font-size: var(--font-size-xs); }
.preview-title { margin-top: 4px; font-weight: var(--font-weight-medium); overflow-wrap: anywhere; }
.preview-fields { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 16px; margin-top: 12px; font-size: var(--font-size-xs); }
.preview-fields > span:nth-child(odd) { color: var(--color-text-muted); }
.preview-status { display: inline-flex; align-items: center; gap: 5px; }
.preview-description { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 5; overflow: hidden; white-space: pre-wrap; overflow-wrap: anywhere; margin: 12px 0 0; color: var(--color-text-secondary); font-size: var(--font-size-xs); }
</style>
