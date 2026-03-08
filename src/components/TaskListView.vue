<script setup lang="ts">
import { ref } from 'vue'
import { Circle, CheckCircle, Flame, ArrowUp, Minus, ArrowDown } from 'lucide-vue-next'
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
import type { TaskGroup } from '../utils/taskView'
import type { VisibleProperty } from '../utils/viewPreference'

const props = defineProps<{
  groups: TaskGroup[]
  users?: User[]
  visibleProperties?: VisibleProperty[]
  selectedTaskId?: string | null
}>()

const emit = defineEmits<{
  rowClick: [task: Task]
  createInStatus: [status?: Status]
}>()

const store = useTaskStore()
const collapsed = ref<Record<string, boolean>>({})

const rowHoveredId = ref<string | null>(null)

const priorityIcons: Record<Priority, typeof Flame> = {
  urgent: Flame,
  high: ArrowUp,
  medium: Minus,
  low: ArrowDown
}

function toggle(groupKey: string) {
  collapsed.value[groupKey] = !collapsed.value[groupKey]
}

function assigneeName(task: Task): string {
  if (task.assigneeId == null || !props.users?.length) return '—'
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.username ?? '—'
}

function assigneeAvatar(task: Task): string | null {
  if (task.assigneeId == null || !props.users?.length) return null
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.avatar_url ?? null
}

function assigneeInitial(task: Task): string {
  const name = assigneeName(task)
  if (name === '—') return '—'
  return name.slice(0, 1).toUpperCase()
}

function dueDateText(task: Task): string {
  if (task.dueDate == null) return '—'
  return new Date(task.dueDate).toLocaleDateString()
}

function updatedText(task: Task): string {
  return new Date(task.updatedAt).toLocaleDateString()
}

function projectText(task: Task): string | null {
  if (task.projectId == null) return null
  return `P-${task.projectId}`
}

function isOverdue(task: Task): boolean {
  if (task.dueDate == null || task.status === 'done') return false
  return task.dueDate < Date.now()
}

function statusLabel(s: Status): string {
  const map: Record<Status, string> = {
    todo: 'Todo',
    in_progress: 'In Progress',
    done: 'Done'
  }
  return map[s]
}

async function toggleComplete(e: MouseEvent, task: Task) {
  e.stopPropagation()
  const newStatus = task.status === 'done' ? 'todo' : 'done'
  await store.transitionTask(task.id, newStatus)
}

function onRowClick(task: Task) {
  emit('rowClick', task)
}

function onCreateInStatus(status?: Status) {
  emit('createInStatus', status)
}

function show(property: VisibleProperty) {
  return props.visibleProperties?.includes(property) ?? false
}
</script>

<template>
  <div class="list-view">
    <div v-for="group in groups" :key="group.key" class="group">
      <div class="group-header">
        <button
          type="button"
          class="group-toggle"
          :aria-expanded="!collapsed[group.key]"
          @click="toggle(group.key)"
        >
          <span class="group-title">{{ group.label }}</span>
          <span class="group-count">{{ group.tasks.length }}</span>
          <span class="group-chevron">{{ collapsed[group.key] ? '▶' : '▼' }}</span>
        </button>
        <button
          type="button"
          class="group-create"
          aria-label="Create issue in group"
          @click.stop="onCreateInStatus(group.key === 'todo' || group.key === 'in_progress' || group.key === 'done' ? (group.key as Status) : undefined)"
        >
          +
        </button>
      </div>
      <div v-show="!collapsed[group.key]" class="group-rows">
        <div
          v-for="task in group.tasks"
          :key="task.id"
          class="task-row"
          :class="{ overdue: isOverdue(task), selected: props.selectedTaskId === task.id }"
          @mouseenter="rowHoveredId = task.id"
          @mouseleave="rowHoveredId = null"
          @click="onRowClick(task)"
        >
          <div class="task-row-left">
            <template v-if="rowHoveredId === task.id">
              <button
                type="button"
                class="task-check"
                :aria-label="task.status === 'done' ? 'Mark not done' : 'Mark done'"
                @click="toggleComplete($event, task)"
              >
                <CheckCircle v-if="task.status === 'done'" class="icon icon-20 icon-done" />
                <Circle v-else class="icon icon-20 icon-circle" />
              </button>
            </template>
            <template v-else>
              <component
                :is="priorityIcons[task.priority]"
                class="icon icon-20 priority-icon"
                :aria-label="task.priority"
              />
            </template>
          </div>
          <div class="task-row-main">
            <div class="task-row-title">{{ task.title }}</div>
            <div v-if="show('project') && projectText(task)" class="task-row-meta">
              <span class="meta-tag">{{ projectText(task) }}</span>
            </div>
          </div>
          <div class="task-row-pills">
            <span v-if="show('id')" class="pill pill-id">{{ task.id }}</span>
            <span v-if="show('status')" class="pill pill-status" :class="task.status">{{ statusLabel(task.status) }}</span>
            <span v-if="show('assignee')" class="pill pill-assignee">
              <img
                v-if="assigneeAvatar(task)"
                :src="assigneeAvatar(task)!"
                :alt="assigneeName(task)"
                class="avatar-24"
              />
              <span v-else class="avatar-24 fallback">{{ assigneeInitial(task) }}</span>
            </span>
            <span v-if="show('dueDate')" class="pill pill-due" :class="{ overdue: isOverdue(task) }">
              {{ dueDateText(task) }}
            </span>
            <span v-if="show('updatedAt')" class="pill pill-updated">{{ updatedText(task) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list-view {
  display: flex;
  flex-direction: column;
  gap: 0;
  height: 100%;
  overflow: auto;
}
/* P4-4.4: 分组去卡片化，用线+字区分 */
.group {
  background: transparent;
  border-bottom: 1px solid var(--color-border);
  overflow: hidden;
}
.group:last-child {
  border-bottom: none;
}
.group-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  transition: background var(--transition-fast);
}
.group-header:hover {
  background: var(--color-hover);
}
.group-toggle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  font-size: var(--font-size-body, 14px);
  font-weight: 500;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
}
.group-title {
  flex: 0 0 auto;
}
.group-count {
  font-size: var(--font-size-caption, 12px);
  background: var(--color-bg-main);
  padding: 2px 6px;
  border-radius: 12px;
}
.group-create {
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: 6px;
  color: var(--color-text-secondary);
}
.group-create:hover {
  background: var(--color-bg-main);
  color: var(--color-text-primary);
}
.group-chevron {
  font-size: 11px;
  color: var(--color-text-muted, #888);
}
.group-rows {
  display: flex;
  flex-direction: column;
}
.task-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 42px;
  padding: 0 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--color-border);
  transition: background-color 150ms ease;
}
.task-row:hover {
  background: var(--color-hover);
}
.task-row.selected {
  background: rgba(94, 106, 210, 0.08);
}
.task-row:last-child {
  border-bottom: none;
}
.task-row-left {
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 42px;
}
.task-check {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-secondary);
}
.task-check:hover {
  color: var(--color-status-done);
}
.icon {
  flex-shrink: 0;
}
.icon-20 {
  width: 20px;
  height: 20px;
}
.icon-done {
  color: var(--color-status-done);
}
.icon-circle {
  color: var(--color-text-secondary);
}
.priority-icon {
  color: var(--color-text-secondary);
}
.task-row-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.task-row-title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: var(--font-size-body, 14px);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-row-meta {
  display: flex;
}
.meta-tag {
  font-size: 11px;
  color: var(--color-text-secondary);
  background: var(--color-hover);
  border-radius: 999px;
  padding: 2px 8px;
}
.task-row-pills {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.pill {
  font-size: var(--font-size-caption, 12px);
  padding: 2px 8px;
  border-radius: 12px;
  background: var(--color-bg-main);
  color: var(--color-text-secondary);
  white-space: nowrap;
}
.pill-status.todo {
  background: var(--color-hover);
  color: var(--color-text-secondary);
}
.pill-status.in_progress {
  background: rgba(242, 201, 76, 0.2);
  color: #b38600;
}
.pill-status.done {
  background: rgba(94, 106, 210, 0.15);
  color: var(--color-status-done);
}
.pill-assignee {
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
}
.pill-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.avatar-24 {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
}
.avatar-24.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  background: var(--color-border);
  color: var(--color-text-secondary);
}
.pill-due.overdue {
  color: var(--color-status-warning);
  font-weight: 500;
}
.task-row.overdue .pill-due {
  color: var(--color-status-warning);
  font-weight: 500;
}
</style>
