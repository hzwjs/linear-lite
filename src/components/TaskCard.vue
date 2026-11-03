<script setup lang="ts">
import { computed } from 'vue'
import { Calendar, Loader2 } from 'lucide-vue-next'
import type { Task, Status, Priority } from '../types/domain'
import type { VisibleProperty } from '../utils/viewPreference'
import { getInitials, getAvatarColorByUsername } from '../utils/avatar'
import { assigneeDisplayLabel } from '../utils/taskAssigneeDisplay'
import { formatBeijingDate, formatBeijingShortDate } from '../utils/beijingTime'
import { getPriorityLabel } from '../utils/enumLabels'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from './icons/PriorityIcons'
import UnassignedAvatarIcon from './icons/UnassignedAvatarIcon.vue'

const props = defineProps<{
  task: Task
  visibleProperties?: VisibleProperty[]
  selected?: boolean
  opening?: boolean
}>()

const emit = defineEmits<{
  click: [task: Task]
  transition: [taskId: string, nextStatus: Status]
}>()

const priorityIcons: Record<Priority, typeof PriorityUrgentIcon> = {
  urgent: PriorityUrgentIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon
}
const priorityLabel = computed(() => getPriorityLabel(props.task.priority))

const assigneeDisplay = computed(() =>
  assigneeDisplayLabel(props.task, 'Unassigned')
)

const assigneeInitial = computed(() => getInitials(assigneeDisplay.value))

const assigneeAvatarStyle = computed(() => props.task.assigneeKey?.startsWith('user:')
  ? getAvatarColorByUsername(assigneeDisplay.value)
  : undefined)

const show = (property: VisibleProperty) => props.visibleProperties?.includes(property) ?? false

const startText = computed(() =>
  show('plannedStart') && props.task.plannedStartDate != null
    ? formatBeijingShortDate(props.task.plannedStartDate)
    : null
)
const dueText = computed(() =>
  show('dueDate') && props.task.dueDate != null ? formatBeijingShortDate(props.task.dueDate) : null
)
const dateTitle = computed(() => [
  props.task.plannedStartDate != null ? `开始 ${formatBeijingDate(props.task.plannedStartDate)}` : null,
  props.task.dueDate != null ? `截止 ${formatBeijingDate(props.task.dueDate)}` : null
].filter(Boolean).join(' · '))
const updatedText = computed(() =>
  !startText.value && !dueText.value && show('updatedAt') ? formatBeijingShortDate(props.task.updatedAt) : null
)

const visibleLabels = computed(() => (show('labels') ? props.task.labels ?? [] : []))

const TERMINAL_STATUSES: Status[] = ['done', 'canceled', 'duplicate']
const isTerminal = computed(() => TERMINAL_STATUSES.includes(props.task.status))

const progressPercentClamped = computed(() => {
  const n = props.task.progressPercent ?? 0
  return Math.min(100, Math.max(0, n))
})
// 0% 与已结束任务的进度没有信息量，只在推进中展示
const showProgress = computed(() =>
  show('progress') && progressPercentClamped.value > 0 && !isTerminal.value
)

const isOverdue = computed(() => {
  if (props.task.dueDate == null || isTerminal.value) return false
  return props.task.dueDate < Date.now()
})

const hasMeta = computed(() =>
  Boolean(startText.value || dueText.value || updatedText.value || visibleLabels.value.length > 0)
)

const getNextStatus = (current: Status): Status | null => {
  if (current === 'backlog') return 'todo'
  if (current === 'todo') return 'in_progress'
  if (current === 'in_progress') return 'in_review'
  if (current === 'in_review') return 'done'
  return null
}

const handleTransition = (e: Event) => {
  e.stopPropagation()
  const next = getNextStatus(props.task.status)
  if (next) {
    emit('transition', props.task.id, next)
  }
}
</script>

<template>
  <div
    class="task-card"
    :class="{ selected, 'task-card--opening': opening, 'task-card--terminal': isTerminal }"
    :aria-busy="opening"
    @click="emit('click', task)"
  >
    <div v-if="show('id') || show('priority') || show('assignee') || opening" class="card-header">
      <span
        v-if="show('priority')"
        class="priority"
        :class="`priority--${task.priority}`"
        :title="priorityLabel"
      >
        <component :is="priorityIcons[task.priority]" :size="14" />
        <span v-if="task.priority === 'urgent' || task.priority === 'high'" class="priority-text">{{ priorityLabel }}</span>
      </span>
      <span v-if="show('id')" class="taskId">{{ task.id }}</span>
      <Loader2 v-if="opening" class="task-opening-spinner" :size="14" aria-label="正在打开任务" />
      <span
        v-if="show('assignee')"
        class="avatar"
        :class="{ placeholder: !task.assigneeKey }"
        :style="assigneeAvatarStyle"
        :title="assigneeDisplay"
        :aria-label="assigneeDisplay"
      >
        <span v-if="task.assigneeKey">{{ assigneeInitial }}</span>
        <UnassignedAvatarIcon v-else />
      </span>
    </div>
    <div class="card-title">{{ task.title }}</div>
    <div v-if="showProgress" class="card-progress" :title="`${progressPercentClamped}%`">
      <span class="card-progress-track" aria-hidden="true">
        <span class="card-progress-fill" :style="{ width: `${progressPercentClamped}%` }" />
      </span>
      <span class="card-progress-pct">{{ progressPercentClamped }}%</span>
    </div>
    <div v-if="hasMeta" class="card-meta">
      <span
        v-if="startText || dueText"
        class="meta-date"
        :class="{ 'meta-date--overdue': isOverdue }"
        :title="dateTitle"
      >
        <Calendar class="meta-icon" stroke-width="2" aria-hidden="true" />
        <template v-if="startText && dueText">{{ startText }} → {{ dueText }}</template>
        <template v-else>{{ dueText ?? startText }}</template>
      </span>
      <span v-else-if="updatedText" class="meta-date">{{ updatedText }}</span>
      <span v-for="label in visibleLabels" :key="label.id" class="meta-label" :title="label.name">
        {{ label.name }}
      </span>
    </div>
    <button
      v-if="getNextStatus(task.status)"
      type="button"
      class="transition-btn"
      title="Move to next status"
      aria-label="Move to next status"
      @click="handleTransition"
    >
      <span class="icon">➜</span>
    </button>
  </div>
</template>

<style scoped>
/* 看板卡片：头部放优先级/编号/负责人，正文标题，底部日期与标签；各行独立，避免元素挤压重叠 */
.task-card {
  position: relative;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.task-card:hover {
  border-color: var(--color-border-strong);
  box-shadow: 0 2px 8px rgba(16, 24, 40, 0.08);
}
.task-card.selected {
  border-color: var(--color-accent-muted-border);
  box-shadow: 0 0 0 1px var(--color-accent-muted-border);
}
.task-card--opening {
  border-color: var(--color-accent-muted-border);
}
.task-card--terminal .card-title {
  color: var(--color-text-secondary);
}
.task-opening-spinner {
  color: var(--color-text-muted);
  animation: task-opening-spin 700ms linear infinite;
}
@keyframes task-opening-spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .task-opening-spinner { animation: none; }
}

.card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 20px;
  font-size: var(--font-size-xs);
}
.taskId {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.priority {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  color: var(--color-text-muted);
  font-weight: var(--font-weight-medium);
}
.priority--urgent { color: #c94a4a; }
.priority--high { color: #b38600; }

.avatar {
  margin-left: auto;
  width: 20px;
  height: 20px;
  min-width: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-muted);
  font-size: 9px;
  font-weight: var(--font-weight-medium);
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar.placeholder {
  background: transparent;
  color: var(--color-text-muted);
}
.avatar.placeholder :deep(svg) { width: 18px; height: 18px; }

.card-title {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
  line-height: 1.45;
  letter-spacing: var(--letter-spacing);
  color: var(--color-text-primary);
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-progress-track {
  flex: 1;
  min-width: 0;
  height: 4px;
  border-radius: 999px;
  background: var(--color-bg-muted);
  overflow: hidden;
}
.card-progress-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--color-status-in-progress);
  transition: width var(--transition-fast);
}
.card-progress-pct {
  flex: 0 0 auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  min-width: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.meta-date {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.meta-icon {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}
.meta-date--overdue {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}
.meta-label {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 6px;
  line-height: 18px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-text-secondary);
}

/* 悬停时浮在右下角，不占布局空间 */
.transition-btn {
  position: absolute;
  right: 6px;
  bottom: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 11px;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.task-card:hover .transition-btn,
.transition-btn:focus-visible { opacity: 1; }
.transition-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
</style>
