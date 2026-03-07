<script setup lang="ts">
import { ref, computed } from 'vue'
import type { Task, Status } from '../types/domain'
import type { User } from '../types/domain'

const props = defineProps<{
  tasks: Task[]
  users?: User[]
}>()

const emit = defineEmits<{
  rowClick: [task: Task]
}>()

const collapsed = ref<Record<Status, boolean>>({
  todo: false,
  in_progress: false,
  done: false
})

const columns = [
  { id: 'todo', title: 'Todo' },
  { id: 'in_progress', title: 'In Progress' },
  { id: 'done', title: 'Done' }
] as const

const grouped = computed(() => {
  const list = props.tasks
  return {
    todo: list.filter((t) => t.status === 'todo'),
    in_progress: list.filter((t) => t.status === 'in_progress'),
    done: list.filter((t) => t.status === 'done')
  }
})

function toggle(col: (typeof columns)[number]) {
  collapsed.value[col.id] = !collapsed.value[col.id]
}

function assigneeName(task: Task): string {
  if (task.assigneeId == null || !props.users?.length) return '—'
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.username ?? '—'
}

function dueDateText(task: Task): string {
  if (task.dueDate == null) return '—'
  return new Date(task.dueDate).toLocaleDateString()
}

function isOverdue(task: Task): boolean {
  if (task.dueDate == null || task.status === 'done') return false
  return task.dueDate < Date.now()
}
</script>

<template>
  <div class="list-view">
    <div v-for="col in columns" :key="col.id" class="group">
      <button
        type="button"
        class="group-header"
        :aria-expanded="!collapsed[col.id]"
        @click="toggle(col)"
      >
        <span class="group-title">{{ col.title }}</span>
        <span class="group-count">{{ grouped[col.id].length }}</span>
        <span class="group-chevron">{{ collapsed[col.id] ? '▶' : '▼' }}</span>
      </button>
      <div v-show="!collapsed[col.id]" class="group-table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th class="col-id">ID</th>
              <th class="col-title">Title</th>
              <th class="col-status">Status</th>
              <th class="col-priority">Priority</th>
              <th class="col-assignee">Assignee</th>
              <th class="col-due">Due Date</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="task in grouped[col.id]"
              :key="task.id"
              class="task-row"
              :class="{ overdue: isOverdue(task) }"
              @click="emit('rowClick', task)"
            >
              <td class="col-id">{{ task.id }}</td>
              <td class="col-title">{{ task.title }}</td>
              <td class="col-status">{{ task.status }}</td>
              <td class="col-priority">{{ task.priority }}</td>
              <td class="col-assignee">{{ assigneeName(task) }}</td>
              <td class="col-due">{{ dueDateText(task) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  overflow: auto;
}
.group {
  background: rgba(255, 255, 255, 0.02);
  border-radius: var(--border-radius-md);
  overflow: hidden;
}
.group-header {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background var(--transition-fast);
}
.group-header:hover {
  background: var(--color-bg-hover);
}
.group-title {
  flex: 0 0 auto;
}
.group-count {
  font-size: 12px;
  background: var(--color-bg-elevated);
  padding: 2px 6px;
  border-radius: 12px;
}
.group-chevron {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-text-muted, #888);
}
.group-table-wrap {
  overflow-x: auto;
}
.task-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.task-table th,
.task-table td {
  padding: 8px 16px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}
.task-table th {
  font-weight: 500;
  color: var(--color-text-secondary);
  font-size: 12px;
}
.col-id {
  width: 80px;
  font-family: monospace;
}
.col-title {
  min-width: 180px;
  color: var(--color-text-primary);
}
.col-status,
.col-priority {
  width: 100px;
}
.col-assignee {
  width: 100px;
}
.col-due {
  width: 100px;
}
.task-row {
  cursor: pointer;
  transition: background var(--transition-fast);
}
.task-row:hover {
  background: var(--color-bg-hover);
}
.task-row.overdue .col-due {
  color: #e5484d;
  font-weight: 500;
}
</style>
