<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from './icons/PriorityIcons'
import {
  Circle,
  CircleDashed,
  CircleX,
  CheckCircle,
  Copy,
  Eye,
  Loader2,
  User as UserIcon
} from 'lucide-vue-next'
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
import type { TaskGroup, TaskRow } from '../utils/taskView'
import type { VisibleProperty } from '../utils/viewPreference'
import { getSubtaskProgressDisplay } from '../utils/subtaskProgress'
import { getInitials, getAvatarColor } from '../utils/avatar'
import { getStatusLabel } from '../utils/enumLabels'

const props = defineProps<{
  groups: TaskGroup[]
  users?: User[]
  visibleProperties?: VisibleProperty[]
  selectedTaskId?: string | null
}>()

const emit = defineEmits<{
  rowClick: [task: Task]
  createInStatus: [status?: Status]
  addSubIssue: [task: Task]
}>()

const store = useTaskStore()
const { t } = useI18n()
const collapsed = ref<Record<string, boolean>>({})
const rowHoveredId = ref<string | null>(null)
const subtaskRingRadius = 5
const subtaskRingCircumference = 2 * Math.PI * subtaskRingRadius

const priorityIcons: Record<Priority, typeof PriorityUrgentIcon> = {
  urgent: PriorityUrgentIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon
}

const statusIcons: Record<Status, typeof Circle> = {
  backlog: CircleDashed,
  todo: Circle,
  in_progress: Loader2,
  in_review: Eye,
  done: CheckCircle,
  canceled: CircleX,
  duplicate: Copy
}

function toggle(groupKey: string) {
  collapsed.value[groupKey] = !collapsed.value[groupKey]
}

function assigneeName(task: Task): string {
  if (task.assigneeId == null || !props.users?.length) return t('common.unassigned')
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.username ?? t('common.unassigned')
}

function assigneeAvatar(task: Task): string | null {
  if (task.assigneeId == null || !props.users?.length) return null
  const u = props.users.find((u) => u.id === task.assigneeId)
  return u?.avatar_url ?? null
}

function hasAssignee(task: Task): boolean {
  if (task.assigneeId == null || !props.users?.length) return false
  return props.users.some((u) => u.id === task.assigneeId)
}

function assigneeInitial(task: Task): string {
  return getInitials(assigneeName(task))
}

function assigneeFallbackStyle(task: Task): { background: string; color: string } | undefined {
  if (task.assigneeId == null || !hasAssignee(task)) return undefined
  return getAvatarColor(task.assigneeId)
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

const TERMINAL_STATUSES: Status[] = ['done', 'canceled', 'duplicate']
function isOverdue(task: Task): boolean {
  if (task.dueDate == null || TERMINAL_STATUSES.includes(task.status)) return false
  return task.dueDate < Date.now()
}

function statusLabel(s: Status): string {
  return getStatusLabel(s)
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

/** 每组用于渲染的行（含子任务时带 depth/parentTitle） */
function groupListRows(group: TaskGroup): TaskRow[] {
  if (group.rows?.length) return group.rows
  return group.tasks.map((t) => ({ task: t, depth: 0 }))
}

function rowTitle(row: TaskRow): string {
  if (row.depth > 0 && row.parentTitle) return `${row.task.title} > ${row.parentTitle}`
  return row.task.title
}

function subtaskProgress(task: Task) {
  return getSubtaskProgressDisplay(task.completedSubIssueCount ?? 0, task.subIssueCount ?? 0)
}

function subtaskRingOffset(progress: number): number {
  return subtaskRingCircumference * (1 - progress)
}

function onAddSubIssue(e: MouseEvent, task: Task) {
  e.stopPropagation()
  emit('addSubIssue', task)
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
            <span class="group-count">{{ group.rows ? group.rows.length : group.tasks.length }}</span>
          </button>
          <button
            type="button"
            class="group-create"
            :aria-label="t('taskList.createIssueInGroup')"
            @click.stop="onCreateInStatus(group.key === 'todo' || group.key === 'in_progress' || group.key === 'done' ? (group.key as Status) : undefined)"
          >
            +
          </button>
        </div>
        <div v-show="!collapsed[group.key]" class="group-rows">
          <div
            v-for="row in groupListRows(group)"
            :key="row.task.id"
            class="task-row"
            :class="{ overdue: isOverdue(row.task), selected: props.selectedTaskId === row.task.id }"
            :style="{ paddingLeft: row.depth > 0 ? `calc(12px + ${row.depth * 20}px)` : undefined }"
            tabindex="0"
            @mouseenter="setHoveredId(row.task.id)"
            @mouseleave="setHoveredId(null)"
            @click="onRowClick(row.task)"
          >
            <div class="task-row-leading">
              <button
                v-if="rowHoveredId === row.task.id"
                type="button"
                class="task-check"
                :aria-label="row.task.status === 'done' ? t('taskList.markNotDone') : t('taskList.markDone')"
                @click="toggleComplete($event, row.task)"
              >
                <CheckCircle v-if="row.task.status === 'done'" class="icon icon-14 icon-done" />
                <Circle v-else class="icon icon-14 icon-circle" />
              </button>
              <component
                v-else
                :is="priorityIcons[row.task.priority]"
                class="icon icon-14 priority-icon"
                :aria-label="row.task.priority"
              />
            </div>
            <span class="task-row-key">{{ row.task.id }}</span>
            <span class="task-row-status">
              <component :is="statusIcons[row.task.status]" class="icon icon-14 status-icon" />
            </span>
            <div class="task-row-content">
              <span class="task-row-title-cluster">
                <span class="task-row-title">{{ rowTitle(row) }}</span>
                <span
                  v-if="subtaskProgress(row.task).visible"
                  class="task-row-sub-count"
                  :class="{ completed: subtaskProgress(row.task).completed }"
                >
                  <svg class="task-row-sub-count-ring" viewBox="0 0 16 16" aria-hidden="true">
                    <circle class="task-row-sub-count-track" cx="8" cy="8" :r="subtaskRingRadius" />
                    <circle
                      class="task-row-sub-count-progress"
                      cx="8"
                      cy="8"
                      :r="subtaskRingRadius"
                      :stroke-dasharray="subtaskRingCircumference"
                      :stroke-dashoffset="subtaskRingOffset(subtaskProgress(row.task).progress)"
                    />
                  </svg>
                  <span class="task-row-sub-count-text">{{ subtaskProgress(row.task).countText }}</span>
                </span>
              </span>
            </div>
            <div class="task-row-trailing">
              <template v-if="show('project') && projectText(row.task)">
                <span class="task-meta-slot task-meta-slot-project">
                  <span class="task-meta">{{ projectText(row.task) }}</span>
                </span>
              </template>
              <template v-if="show('status')">
                <span class="task-meta-slot task-meta-slot-status">
                  <span class="task-meta task-meta-status" :class="row.task.status">{{ statusLabel(row.task.status) }}</span>
                </span>
              </template>
              <template v-if="show('assignee')">
                <span class="task-meta-slot task-meta-slot-assignee">
                  <span class="task-meta task-meta-assignee">
                    <span
                      class="task-assignee-trigger"
                      :class="{ assigned: hasAssignee(row.task), unassigned: !hasAssignee(row.task) }"
                      :data-tooltip="assigneeName(row.task)"
                      tabindex="0"
                    >
                      <img
                        v-if="assigneeAvatar(row.task)"
                        :src="assigneeAvatar(row.task)!"
                        :alt="assigneeName(row.task)"
                        class="avatar-18"
                      />
                      <span
                        v-else-if="hasAssignee(row.task)"
                        class="avatar-18 fallback"
                        :style="assigneeFallbackStyle(row.task)"
                      >{{ assigneeInitial(row.task) }}</span>
                      <UserIcon v-else class="task-assignee-icon" aria-hidden="true" />
                    </span>
                  </span>
                </span>
              </template>
              <template v-if="show('dueDate')">
                <span class="task-meta-slot task-meta-slot-date">
                  <span class="task-meta" :class="{ overdue: isOverdue(row.task) }">{{ dueDateText(row.task) }}</span>
                </span>
              </template>
              <template v-if="show('updatedAt')">
                <span class="task-meta-slot task-meta-slot-date">
                  <span class="task-meta">{{ updatedText(row.task) }}</span>
                </span>
              </template>
            </div>
            <button
              type="button"
              class="task-row-add"
              :aria-label="t('taskList.addSubIssue')"
              :class="{ visible: rowHoveredId === row.task.id }"
              @click.stop="onAddSubIssue($event, row.task)"
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
/* 任务列表区：统一表面、弱化分割与选中色块，减少干扰 */
.list-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  --list-row-border: #f5f5f5;
}

.list-view-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

/* 分组：轻底 + 稍重边框 + 字重，让标题更易辨认 */
.group {
  background: transparent;
  border-bottom: 1px solid var(--list-row-border);
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
  min-height: 36px;
  background: var(--color-bg-subtle);
  border-bottom: 1px solid var(--color-border);
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
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
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
  margin-left: 4px;
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

/* 行：分割线更轻；选中态仅左侧条，背景与 hover 一致不抢眼 */
.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  height: 32px;
  padding: 0 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--list-row-border);
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
  background: var(--color-bg-hover);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
.task-row.selected:hover {
  background: var(--color-bg-hover);
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
  display: flex;
  align-items: center;
}
.task-row-title-cluster {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.task-row-title {
  flex: 0 1 auto;
  min-width: 0;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.25;
}
.task-row-sub-count {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 36px;
  height: 22px;
  padding: 0 7px 0 6px;
  font-size: 10px;
  line-height: 1;
  color: #5f6675;
  background: #fbfcff;
  border: 1px solid #d8deef;
  border-radius: 999px;
  box-sizing: border-box;
}
.task-row-sub-count.completed {
  color: #59636f;
  background: #fbfcfd;
  border-color: #dce3ea;
}
.task-row-sub-count-ring {
  width: 12px;
  height: 12px;
  flex: 0 0 12px;
  transform: rotate(-90deg);
}
.task-row-sub-count-track,
.task-row-sub-count-progress {
  fill: none;
  stroke-width: 2;
}
.task-row-sub-count-track {
  stroke: #dbe1f2;
}
.task-row-sub-count-progress {
  stroke: #5f6eea;
  stroke-linecap: round;
  transition: stroke-dashoffset var(--transition-fast), stroke var(--transition-fast);
}
.task-row-sub-count.completed .task-row-sub-count-track {
  stroke: #dce3ea;
}
.task-row-sub-count.completed .task-row-sub-count-progress {
  stroke: #5f6eea;
}
.task-row-sub-count-text {
  font-size: 11px;
  font-weight: 450;
  letter-spacing: -0.01em;
}

.task-row-trailing {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: max-content;
  align-items: center;
  justify-content: end;
  column-gap: 14px;
  flex: 0 0 auto;
  min-width: 0;
}
.task-meta-slot {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
}
.task-meta-slot-project {
  min-width: 72px;
}
.task-meta-slot-status {
  min-width: 88px;
}
.task-meta-slot-assignee {
  width: 26px;
}
.task-meta-slot-date {
  width: 72px;
}

.task-meta {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  white-space: nowrap;
  min-width: 0;
}
.task-meta-status.backlog,
.task-meta-status.todo {
  color: var(--color-text-secondary);
}
.task-meta-status.in_progress,
.task-meta-status.in_review {
  color: var(--color-status-in-progress);
}
.task-meta-status.done {
  color: var(--color-status-done);
}
.task-meta-status.canceled,
.task-meta-status.duplicate {
  color: var(--color-text-muted);
}
.task-meta.overdue {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}
.task-meta-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.task-meta-assignee {
  position: relative;
  width: 100%;
  justify-content: center;
}
.task-assignee-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  color: var(--color-text-muted);
  outline: none;
}
.task-assignee-trigger:focus-visible::before,
.task-assignee-trigger:hover::before {
  opacity: 1;
  transform: translate(-50%, -2px);
}
.task-assignee-trigger:focus-visible::after,
.task-assignee-trigger:hover::after {
  opacity: 1;
  transform: translateX(-50%);
}
.task-assignee-trigger::before {
  content: attr(data-tooltip);
  position: absolute;
  left: 50%;
  bottom: calc(100% + 8px);
  transform: translate(-50%, 2px);
  padding: 5px 8px;
  border-radius: 6px;
  background: #111827;
  color: #fff;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  box-shadow: var(--shadow-popover);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
  z-index: 4;
}
.task-assignee-trigger::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: calc(100% + 4px);
  width: 8px;
  height: 8px;
  background: #111827;
  transform: translateX(-50%) rotate(45deg);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--transition-fast), transform var(--transition-fast);
  z-index: 3;
}
.task-assignee-trigger.unassigned {
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
}
.task-assignee-trigger.assigned {
  background: transparent;
}
.task-assignee-icon {
  width: 14px;
  height: 14px;
}

.avatar-18 {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}
/* 圆内两字留白：字号明显小于圆径，避免“志文”撑满 */
.avatar-18.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 7px;
  font-weight: var(--font-weight-medium);
  line-height: 1;
  letter-spacing: 0.02em;
  /* assigned fallback uses inline style from getAvatarColor; unassigned not shown here */
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
