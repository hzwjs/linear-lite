<script setup lang="ts">
import { computed } from 'vue'
import type { Task, Status } from '../types/domain'
import type { User } from '../types/domain'

const props = defineProps<{
  task: Task
  users?: User[]
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
  <div class="task-card" @click="emit('click', task)">
    <div class="card-header">
      <span class="taskId">{{ task.id }}</span>
      <span class="priority" :class="task.priority">{{ priorityLabel }}</span>
    </div>
    <div class="card-title">{{ task.title }}</div>
    <div class="assignee-row">
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
        <template v-if="dueDateText">
          <span v-if="isOverdue" class="due-overdue" title="Overdue">📅 {{ dueDateText }}</span>
          <span v-else>📅 {{ dueDateText }}</span>
        </template>
        <template v-else>{{ new Date(task.updatedAt).toLocaleDateString() }}</template>
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
.task-card {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-md);
  padding: 12px;
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.task-card:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-accent);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
}

.taskId {
  color: var(--color-text-secondary);
  font-family: monospace;
}

.priority {
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
  background: var(--color-border);
}

.priority.urgent { color: #E5484D; background: rgba(229, 72, 77, 0.1); }
.priority.high { color: #F5A623; background: rgba(245, 166, 35, 0.1); }
.priority.medium { color: var(--color-text-primary); }
.priority.low { color: var(--color-text-secondary); }

.card-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.4;
  color: var(--color-text-primary);
}

.assignee-row {
  font-size: 12px;
}

.assignee {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
}

.avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-elevated);
  font-size: 11px;
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
  max-width: 120px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
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
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.05);
  transition: background var(--transition-fast);
}

.transition-btn:hover {
  background: var(--color-accent);
  color: white;
}
</style>
