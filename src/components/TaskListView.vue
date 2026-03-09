<script setup lang="ts">
import { ref } from 'vue'
import { Circle, CheckCircle, Flame, ArrowUp, Minus, ArrowDown, Loader2 } from 'lucide-vue-next'
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

const statusIcons: Record<Status, typeof Circle> = {
  todo: Circle,
  in_progress: Loader2,
  done: CheckCircle
}

function toggle(groupKey: string) {
  collapsed.value[groupKey] = !collapsed.value[groupKey]
}

function assigneeName(task: Task): string {
  if (task.assigneeId == null || !props.users?.length) return 'Unassigned'
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.username ?? 'Unassigned'
}

function assigneeAvatar(task: Task): string | null {
  if (task.assigneeId == null || !props.users?.length) return null
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.avatar_url ?? null
}

function assigneeInitial(task: Task): string {
  const name = assigneeName(task)
  if (name === 'Unassigned') return '—'
  return name.slice(0, 1).toUpperCase()
}

function dueDateText(task: Task): string {
  if (task.dueDate == null) return '—'
  return new Date(task.dueDate).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function updatedText(task: Task): string {
  return new Date(task.updatedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
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

function setHoveredId(id: string | null) {
  rowHoveredId.value = id
}
</script>

<template>
  <div class="list-view">
    <div class="list-view-scroll">
      <div v-for="group in groups" :key="group.key" class="group">
        <div class="group-header">
          <button
            type="button"
            class="group-toggle"
            :aria-expanded="!collapsed[group.key]"
            @click="toggle(group.key)"
          >
            <span class="group-chevron">{{ collapsed[group.key] ? '▸' : '▾' }}</span>
            <span class="group-title">{{ group.label }}</span>
            <span class="group-count">{{ group.tasks.length }}</span>
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
            tabindex="0"
            @mouseenter="setHoveredId(task.id)"
            @mouseleave="setHoveredId(null)"
            @click="onRowClick(task)"
          >
            <div class="task-row-leading">
              <button
                v-if="rowHoveredId === task.id"
                type="button"
                class="task-check"
                :aria-label="task.status === 'done' ? 'Mark not done' : 'Mark done'"
                @click="toggleComplete($event, task)"
              >
                <CheckCircle v-if="task.status === 'done'" class="icon icon-14 icon-done" />
                <Circle v-else class="icon icon-14 icon-circle" />
              </button>
              <component
                v-else
                :is="priorityIcons[task.priority]"
                class="icon icon-14 priority-icon"
                :aria-label="task.priority"
              />
            </div>
            <span class="task-row-key">{{ task.id }}</span>
            <span class="task-row-status">
              <component :is="statusIcons[task.status]" class="icon icon-14 status-icon" />
            </span>
            <div class="task-row-content">
              <span class="task-row-title">{{ task.title }}</span>
            </div>
            <div class="task-row-trailing">
              <template v-if="show('project') && projectText(task)">
                <span class="task-meta">{{ projectText(task) }}</span>
              </template>
              <template v-if="show('status')">
                <span class="task-meta task-meta-status" :class="task.status">{{ statusLabel(task.status) }}</span>
              </template>
              <template v-if="show('assignee')">
                <span class="task-meta task-meta-assignee">
                  <img
                    v-if="assigneeAvatar(task)"
                    :src="assigneeAvatar(task)!"
                    :alt="assigneeName(task)"
                    class="avatar-18"
                  />
                  <span v-else class="avatar-18 fallback">{{ assigneeInitial(task) }}</span>
                  <span class="task-meta-label">{{ assigneeName(task) }}</span>
                </span>
              </template>
              <template v-if="show('dueDate')">
                <span class="task-meta" :class="{ overdue: isOverdue(task) }">{{ dueDateText(task) }}</span>
              </template>
              <template v-if="show('updatedAt')">
                <span class="task-meta">{{ updatedText(task) }}</span>
              </template>
              <template v-if="show('id')">
                <span class="task-meta task-meta-id">{{ task.id }}</span>
              </template>
            </div>
            <button
              type="button"
              class="task-row-add"
              aria-label="Add sub-issue"
              :class="{ visible: rowHoveredId === task.id }"
              @click.stop
            >
              +
            </button>
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
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.list-view-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

/* 分组：结构标题感，sticky */
.group {
  background: transparent;
  border-bottom: 1px solid var(--color-border-subtle);
}
.group:last-child {
  border-bottom: none;
}
.group-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  min-height: 32px;
  background: var(--color-bg-subtle);
  border-bottom: 1px solid var(--color-border-subtle);
  transition: background var(--transition-fast);
}
.group-header:hover {
  background: var(--color-bg-muted);
}
.group-toggle {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  text-align: left;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
}
.group-chevron {
  flex: 0 0 auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.group-title {
  flex: 0 0 auto;
}
.group-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  margin-left: 2px;
}
.group-create {
  width: 20px;
  height: 20px;
  padding: 0;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.group-create:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.group-rows {
  display: flex;
  flex-direction: column;
}

/* 行：表格式列，32px 行高，选中态左侧竖线 */
.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  height: 32px;
  padding: 0 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--color-border-subtle);
  transition: background-color var(--transition-fast), box-shadow var(--transition-fast);
  outline: none;
}
.task-row:last-child {
  border-bottom: none;
}
.task-row:hover {
  background: var(--color-bg-hover);
}
.task-row.selected {
  background: var(--color-accent-muted);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
.task-row.selected:hover {
  background: var(--color-accent-muted);
}
.task-row:focus-visible {
  box-shadow: inset 2px 0 0 var(--color-accent);
  background: var(--color-bg-hover);
}

.task-row-leading {
  flex: 0 0 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
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
.icon-14 {
  width: 14px;
  height: 14px;
}
.icon-done {
  color: var(--color-status-done);
}
.icon-circle {
  color: var(--color-text-secondary);
}
.priority-icon {
  color: var(--color-text-muted);
}
.status-icon {
  color: var(--color-text-secondary);
}

.task-row-key {
  flex: 0 0 auto;
  min-width: 56px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-row-status {
  flex: 0 0 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
}

.task-row-content {
  flex: 1 1 auto;
  min-width: 0;
}
.task-row-title {
  display: block;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.25;
}

.task-row-trailing {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
  min-width: 0;
}

.task-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  white-space: nowrap;
  flex-shrink: 0;
}
.task-meta-status.todo {
  color: var(--color-text-secondary);
}
.task-meta-status.in_progress {
  color: var(--color-status-in-progress);
}
.task-meta-status.done {
  color: var(--color-status-done);
}
.task-meta.overdue {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}
.task-meta-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.task-meta-assignee {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.task-meta-label {
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.avatar-18 {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}
.avatar-18.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: var(--font-weight-medium);
  background: var(--color-border);
  color: var(--color-text-secondary);
}

.task-row-add {
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}
.task-row-add.visible {
  opacity: 1;
}
.task-row-add:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
</style>
