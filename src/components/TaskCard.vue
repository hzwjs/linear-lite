<script setup lang="ts">
import { computed } from 'vue'
import type { Task, Status } from '../types/domain'
import type { User } from '../types/domain'
import type { VisibleProperty } from '../utils/viewPreference'
import { getInitials, getAvatarColor } from '../utils/avatar'

const props = defineProps<{
  task: Task
  users?: User[]
  visibleProperties?: VisibleProperty[]
  selected?: boolean
}>()

const emit = defineEmits<{
  click: [task: Task]
  transition: [taskId: string, nextStatus: Status]
}>()

const priorityLabel = computed(() => {
  const map: Record<string, string> = {
    low: 'Low',
    medium: 'Medium',
    high: 'High',
    urgent: 'Urgent'
  }
  return map[props.task.priority] || props.task.priority
})

const assignee = computed(() => {
  if (props.task.assigneeId == null || !props.users?.length) return null
  return props.users.find((u) => u.id === props.task.assigneeId) ?? null
})

const assigneeDisplay = computed(() => assignee.value?.username ?? 'Unassigned')

const assigneeInitial = computed(() =>
  getInitials(assignee.value?.username ?? 'Unassigned')
)

const assigneeAvatarStyle = computed(() =>
  assignee.value ? getAvatarColor(assignee.value.id) : undefined
)

const dueDateText = computed(() => {
  if (props.task.dueDate == null) return null
  return new Date(props.task.dueDate).toLocaleDateString()
})
const updatedText = computed(() => new Date(props.task.updatedAt).toLocaleDateString())
const projectText = computed(() => {
  if (props.task.projectId == null) return null
  return `Project ${props.task.projectId}`
})
const show = (property: VisibleProperty) => props.visibleProperties?.includes(property) ?? false

const TERMINAL_STATUSES: Status[] = ['done', 'canceled', 'duplicate']
const isOverdue = computed(() => {
  if (props.task.dueDate == null || TERMINAL_STATUSES.includes(props.task.status)) return false
  return props.task.dueDate < Date.now()
})

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
  <div class="task-card" :class="{ selected }" @click="emit('click', task)">
    <div class="card-header">
      <span v-if="show('id')" class="taskId">{{ task.id }}</span>
      <span v-if="show('priority')" class="priority" :class="task.priority">{{ priorityLabel }}</span>
    </div>
    <div class="card-title">{{ task.title }}</div>
    <div v-if="show('project') && projectText" class="meta-row">
      <span class="meta-pill">{{ projectText }}</span>
    </div>
    <div v-if="show('assignee')" class="assignee-row">
      <span class="assignee" :title="assigneeDisplay">
        <span class="avatar" :class="{ placeholder: !assignee }" :style="assigneeAvatarStyle">
          <img v-if="assignee?.avatar_url" :src="assignee.avatar_url" :alt="assignee.username" />
          <span v-else>{{ assigneeInitial }}</span>
        </span>
        <span class="assignee-name">{{ assigneeDisplay }}</span>
      </span>
    </div>
    <div class="card-footer">
      <span class="date">
        <template v-if="show('dueDate') && dueDateText">
          <span v-if="isOverdue" class="due-overdue" title="Overdue">📅 {{ dueDateText }}</span>
          <span v-else>📅 {{ dueDateText }}</span>
        </template>
        <template v-else-if="show('updatedAt')">{{ updatedText }}</template>
      </span>
      <button 
        v-if="getNextStatus(task.status)" 
        class="transition-btn" 
        @click="handleTransition"
        title="Move to next status"
      >
        <span class="icon">➜</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* P6-4: Board 与 List 同源 — 薄卡片、轻边框、统一选中态 */
.task-card {
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 6px;
}

.task-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border);
}
.task-card.selected {
  background: var(--color-accent-muted);
  border-color: var(--color-accent-muted-border);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--font-size-xs);
}

.taskId {
  color: var(--color-text-muted);
  font-family: ui-monospace, monospace;
}

.priority {
  padding: 1px 4px;
  border-radius: var(--radius-xs);
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-xs);
  background: var(--color-bg-muted);
}

.priority.urgent { color: #c94a4a; background: rgba(201, 74, 74, 0.1); }
.priority.high { color: #b38600; background: rgba(201, 162, 39, 0.12); }
.priority.medium { color: var(--color-text-primary); }
.priority.low { color: var(--color-text-muted); }

.card-title {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
  letter-spacing: var(--letter-spacing);
  color: var(--color-text-primary);
}

.assignee-row { font-size: var(--font-size-xs); }
.meta-row { display: flex; }
.meta-pill {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  background: var(--color-bg-muted);
  border-radius: var(--radius-xs);
  padding: 1px 5px;
}

.assignee {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-secondary);
}

/* 圆内两字留白：字号明显小于圆径，避免“志文”撑满 */
.avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-muted);
  font-size: 7px;
  font-weight: var(--font-weight-medium);
  line-height: 1;
  letter-spacing: 0.02em;
  flex-shrink: 0;
}

.avatar.placeholder { color: var(--color-text-muted); }
.avatar img { width: 100%; height: 100%; object-fit: cover; }

.assignee-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 1px;
}

.date {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.due-overdue {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}

.transition-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.transition-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
</style>
