<script setup lang="ts">
import { computed } from 'vue'
import type { Task, Status } from '../types/domain'
import type { User } from '../types/domain'
import type { VisibleProperty } from '../utils/viewPreference'

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

const assigneeInitial = computed(() => {
  const u = assignee.value
  return u?.username?.charAt(0)?.toUpperCase() ?? '—'
})

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

const isOverdue = computed(() => {
  if (props.task.dueDate == null || props.task.status === 'done') return false
  return props.task.dueDate < Date.now()
})

const getNextStatus = (current: Status): Status | null => {
  if (current === 'todo') return 'in_progress'
  if (current === 'in_progress') return 'done'
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
        <span class="avatar" :class="{ placeholder: !assignee }">
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
/* P4-6.6: 去卡片化，视觉像板上的条 */
.task-card {
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 9px 10px;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 8px;
}

.task-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border);
}
.task-card.selected {
  border-color: var(--color-accent);
  box-shadow: inset 0 0 0 1px rgba(94, 106, 210, 0.12);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
}

.taskId {
  color: var(--color-text-secondary);
  font-family: monospace;
  letter-spacing: 0.01em;
}

.priority {
  padding: 1px 6px;
  border-radius: 999px;
  font-weight: 500;
  font-size: 10px;
  background: rgba(17, 24, 39, 0.04);
}

.priority.urgent { color: #E5484D; background: rgba(229, 72, 77, 0.1); }
.priority.high { color: #F5A623; background: rgba(245, 166, 35, 0.1); }
.priority.medium { color: var(--color-text-primary); }
.priority.low { color: var(--color-text-secondary); }

.card-title {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: var(--color-text-primary);
}

.assignee-row {
  font-size: 11px;
}
.meta-row {
  display: flex;
}
.meta-pill {
  font-size: 11px;
  color: var(--color-text-secondary);
  background: rgba(17, 24, 39, 0.04);
  border-radius: 999px;
  padding: 2px 7px;
}

.assignee {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-secondary);
}

.avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-elevated);
  font-size: 10px;
  font-weight: 500;
  flex-shrink: 0;
}

.avatar.placeholder {
  color: var(--color-text-muted, #666);
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.assignee-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 112px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 2px;
}

.date {
  font-size: 11px;
  color: var(--color-text-secondary);
}
.due-overdue {
  color: #e5484d;
  font-weight: 500;
}

.transition-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  transition: background var(--transition-fast);
}

.transition-btn:hover {
  background: var(--color-hover);
  color: var(--color-text-primary);
}
</style>
