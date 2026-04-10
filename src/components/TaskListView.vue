<script setup lang="ts">
import { ref, watch, onUnmounted, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from './icons/PriorityIcons'
import {
  Check,
  Circle,
  CheckCircle,
  Copy,
  CircleDashed,
  CircleX,
  Eye,
  FoldVertical,
  Loader2,
  UnfoldVertical,
  User as UserIcon,
  UserPlus,
  UserCheck,
  UserX,
  X,
  ListChecks,
  Gauge,
  Tag,
  Calendar
} from 'lucide-vue-next'
import CommandPalette, { type CommandItem } from './CommandPalette.vue'
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import type { TaskLabelWriteItem } from '../services/api/types'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'
import { projectApi } from '../services/api/project'
import { getStatusLabel, getPriorityLabel } from '../utils/enumLabels'
import { parseDateInputValue, formatDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { labelListDotColor, sortedTaskLabelsForList } from '../utils/taskLabelListDisplay'
import { filterVisibleTaskRows, type TaskGroup, type TaskRow } from '../utils/taskView'
import type { VisibleProperty } from '../utils/viewPreference'
import { getSubtaskProgressDisplay } from '../utils/subtaskProgress'
import { getInitials, getAvatarColor } from '../utils/avatar'
import {
  assigneeDisplayLabel,
  resolveAssigneeUser,
  taskHasAssignableDisplay
} from '../utils/taskAssigneeDisplay'
import TaskRowStatusPicker from './TaskRowStatusPicker.vue'
import TaskRowAssigneePicker from './TaskRowAssigneePicker.vue'
import TaskRowInlineDateCell from './TaskRowInlineDateCell.vue'
import TaskRowProgressCell from './TaskRowProgressCell.vue'

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

const subtaskExpanded = defineModel<Record<string, boolean>>('subtaskExpanded', { default: () => ({}) })

const store = useTaskStore()
const projectStore = useProjectStore()
const authStore = useAuthStore()
const { t } = useI18n()
const collapsed = ref<Record<string, boolean>>({})
const rowHoveredId = ref<string | null>(null)
/** 复制成功后的短暂反馈（图标与提示文案） */
const copyFeedbackTaskId = ref<string | null>(null)
let copyFeedbackClearTimer: ReturnType<typeof setTimeout> | null = null
const COPY_FEEDBACK_MS = 1800

const selectedTaskIds = ref<Set<string>>(new Set())
const bulkCommandsOpen = ref(false)

type BulkSubmenu = 'none' | 'assign' | 'status' | 'priority' | 'labels' | 'dueDate'
const bulkSubmenu = ref<BulkSubmenu>('none')
const bulkLabelsList = ref<{ id: number; name: string }[]>([])
const bulkLabelsLoading = ref(false)

const bulkStatusIcons: Record<Status, typeof Circle> = {
  backlog: CircleDashed,
  todo: Circle,
  in_progress: Loader2,
  in_review: Eye,
  done: CheckCircle,
  canceled: CircleX,
  duplicate: Copy
}

const ALL_STATUSES: Status[] = [
  'backlog',
  'todo',
  'in_progress',
  'in_review',
  'done',
  'canceled',
  'duplicate'
]

const sortedPickerUsers = computed(() => {
  const list = [...(props.users ?? [])]
  list.sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: 'base' }))
  return list
})

function closeBulkPalette() {
  bulkCommandsOpen.value = false
  bulkSubmenu.value = 'none'
}

function onBulkPaletteBack() {
  bulkSubmenu.value = 'none'
}

watch(bulkCommandsOpen, (open) => {
  if (!open) bulkSubmenu.value = 'none'
})

watch(
  () =>
    bulkCommandsOpen.value && bulkSubmenu.value === 'labels'
      ? projectStore.activeProjectId
      : null,
  async (pid) => {
    if (pid == null) {
      bulkLabelsList.value = []
      bulkLabelsLoading.value = false
      return
    }
    bulkLabelsLoading.value = true
    try {
      bulkLabelsList.value = await projectApi.listLabels(pid)
    } catch {
      bulkLabelsList.value = []
    } finally {
      bulkLabelsLoading.value = false
    }
  }
)

function endOfLocalWeekMs(): number {
  const d = new Date()
  const day = d.getDay()
  const add = day === 0 ? 0 : 7 - day
  const sun = new Date(d.getFullYear(), d.getMonth(), d.getDate() + add)
  return parseDateInputValue(formatDateInputValue(sun.getTime()))!
}

function toLabelWriteItemsMerge(task: Task, addedLabelId: number): TaskLabelWriteItem[] {
  const existing = task.labels ?? []
  if (existing.some((l) => l.id === addedLabelId)) {
    return existing.map((l) => ({ id: l.id }))
  }
  return [...existing.map((l) => ({ id: l.id })), { id: addedLabelId }]
}

async function bulkUpdateAll(updater: (taskKey: string) => Promise<unknown>): Promise<void> {
  const ids = [...selectedTaskIds.value]
  await Promise.allSettled(ids.map((id) => updater(id)))
}

async function applyBulkAssignUser(userId: number) {
  await bulkUpdateAll((id) => store.updateTask(id, { assigneeId: userId }))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkUnassign() {
  await bulkUpdateAll((id) => store.updateTask(id, { clearAssignee: true }))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkAssignToMe() {
  const uid = authStore.currentUser?.id
  if (uid == null) return
  await bulkUpdateAll((id) => store.updateTask(id, { assigneeId: uid }))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkStatus(status: Status) {
  await bulkUpdateAll((id) => store.transitionTask(id, status))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkPriority(priority: Priority) {
  await bulkUpdateAll((id) => store.updateTask(id, { priority }))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkAddLabel(labelId: number) {
  await bulkUpdateAll(async (id) => {
    const task = store.tasks.find((x) => x.id === id)
    if (!task) return
    await store.updateTask(id, { labels: toLabelWriteItemsMerge(task, labelId) })
  })
  closeBulkPalette()
  clearSelection()
}

async function applyBulkDueClear() {
  await bulkUpdateAll((id) => store.updateTask(id, { clearDueDate: true }))
  closeBulkPalette()
  clearSelection()
}

async function applyBulkDueMs(ms: number) {
  await bulkUpdateAll((id) => store.updateTask(id, { dueDate: ms }))
  closeBulkPalette()
  clearSelection()
}

const bulkPaletteNestedDepth = computed(() => (bulkSubmenu.value === 'none' ? 0 : 1))

const bulkPaletteNestedTitle = computed(() => {
  switch (bulkSubmenu.value) {
    case 'assign':
      return t('taskList.bulk.nestedAssign')
    case 'status':
      return t('taskList.bulk.nestedStatus')
    case 'priority':
      return t('taskList.bulk.nestedPriority')
    case 'labels':
      return t('taskList.bulk.nestedLabels')
    case 'dueDate':
      return t('taskList.bulk.nestedDueDate')
    default:
      return ''
  }
})

const bulkPaletteCommands = computed<CommandItem[]>(() => {
  if (bulkSubmenu.value === 'assign') {
    const rows: CommandItem[] = [
      {
        id: 'bulk-assign-unassigned',
        label: t('common.unassigned'),
        keywords: ['unassigned', 'clear', 'none'],
        icon: UserX,
        run: () => {
          void applyBulkUnassign()
        }
      }
    ]
    for (const u of sortedPickerUsers.value) {
      const name = u.username
      rows.push({
        id: `bulk-assign-${u.id}`,
        label: name,
        keywords: [name.toLowerCase()],
        icon: UserIcon,
        run: () => {
          void applyBulkAssignUser(u.id)
        }
      })
    }
    return rows
  }

  if (bulkSubmenu.value === 'status') {
    return ALL_STATUSES.map((s) => ({
      id: `bulk-status-${s}`,
      label: getStatusLabel(s),
      keywords: [getStatusLabel(s).toLowerCase(), s],
      icon: bulkStatusIcons[s],
      run: () => {
        void applyBulkStatus(s)
      }
    }))
  }

  if (bulkSubmenu.value === 'priority') {
    const order: Priority[] = ['urgent', 'high', 'medium', 'low']
    return order.map((p) => ({
      id: `bulk-priority-${p}`,
      label: getPriorityLabel(p),
      keywords: [getPriorityLabel(p).toLowerCase(), p],
      icon: priorityIcons[p],
      run: () => {
        void applyBulkPriority(p)
      }
    }))
  }

  if (bulkSubmenu.value === 'labels') {
    if (bulkLabelsLoading.value) {
      return [
        {
          id: 'bulk-labels-loading',
          label: t('taskList.bulk.labelsLoading'),
          keywords: [],
          icon: Tag,
          run: () => {},
          keepOpen: true
        }
      ]
    }
    if (projectStore.activeProjectId == null) {
      return [
        {
          id: 'bulk-labels-no-project',
          label: t('taskList.bulk.labelsNeedProject'),
          keywords: [],
          icon: Tag,
          run: () => {},
          keepOpen: true
        }
      ]
    }
    if (bulkLabelsList.value.length === 0) {
      return [
        {
          id: 'bulk-labels-empty',
          label: t('taskList.bulk.labelsEmpty'),
          keywords: [],
          icon: Tag,
          run: () => {},
          keepOpen: true
        }
      ]
    }
    return bulkLabelsList.value.map((lab) => ({
      id: `bulk-label-${lab.id}`,
      label: lab.name,
      keywords: [lab.name.toLowerCase()],
      icon: Tag,
      run: () => {
        void applyBulkAddLabel(lab.id)
      }
    }))
  }

  if (bulkSubmenu.value === 'dueDate') {
    const todayMs = parseDateInputValue(todayDateInputValue())!
    const weekEnd = endOfLocalWeekMs()
    return [
      {
        id: 'bulk-due-clear',
        label: t('taskList.bulk.dueClear'),
        keywords: ['clear', 'remove'],
        icon: Calendar,
        run: () => {
          void applyBulkDueClear()
        }
      },
      {
        id: 'bulk-due-today',
        label: t('taskList.bulk.dueToday'),
        keywords: ['today'],
        icon: Calendar,
        run: () => {
          void applyBulkDueMs(todayMs)
        }
      },
      {
        id: 'bulk-due-week',
        label: t('taskList.bulk.dueEndOfWeek'),
        keywords: ['week', 'sunday'],
        icon: Calendar,
        run: () => {
          void applyBulkDueMs(weekEnd)
        }
      }
    ]
  }

  return [
    {
      id: 'assign-to',
      label: t('taskList.bulk.assignTo'),
      keywords: ['assign'],
      icon: UserPlus,
      run: () => {
        bulkSubmenu.value = 'assign'
      },
      keepOpen: true
    },
    {
      id: 'assign-to-me',
      label: t('taskList.bulk.assignToMe'),
      keywords: ['assign', 'me'],
      icon: UserCheck,
      run: () => {
        void applyBulkAssignToMe()
      }
    },
    {
      id: 'change-status',
      label: t('taskList.bulk.changeStatus'),
      keywords: ['status'],
      icon: ListChecks,
      run: () => {
        bulkSubmenu.value = 'status'
      },
      keepOpen: true
    },
    {
      id: 'change-priority',
      label: t('taskList.bulk.changePriority'),
      keywords: ['priority'],
      icon: Gauge,
      run: () => {
        bulkSubmenu.value = 'priority'
      },
      keepOpen: true
    },
    {
      id: 'change-labels',
      label: t('taskList.bulk.changeLabels'),
      keywords: ['label'],
      icon: Tag,
      run: () => {
        bulkSubmenu.value = 'labels'
      },
      keepOpen: true
    },
    {
      id: 'set-due-date',
      label: t('taskList.bulk.setDueDate'),
      keywords: ['date', 'due'],
      icon: Calendar,
      run: () => {
        bulkSubmenu.value = 'dueDate'
      },
      keepOpen: true
    }
  ]
})

function toggleSelection(taskId: string) {
  const next = new Set(selectedTaskIds.value)
  if (next.has(taskId)) {
    next.delete(taskId)
  } else {
    next.add(taskId)
  }
  selectedTaskIds.value = next
}

function clearSelection() {
  selectedTaskIds.value.clear()
}

function onKeydown(e: KeyboardEvent) {
  if (selectedTaskIds.value.size > 0 && e.key === 'k' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    e.stopPropagation()
    bulkCommandsOpen.value = true
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown, true)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown, true)
  if (copyFeedbackClearTimer != null) {
    clearTimeout(copyFeedbackClearTimer)
    copyFeedbackClearTimer = null
  }
})
const subtaskRingRadius = 5
const subtaskRingCircumference = 2 * Math.PI * subtaskRingRadius

const priorityIcons: Record<Priority, typeof PriorityUrgentIcon> = {
  urgent: PriorityUrgentIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon
}

function toggle(groupKey: string) {
  collapsed.value[groupKey] = !collapsed.value[groupKey]
}

function assigneeName(task: Task): string {
  return assigneeDisplayLabel(task, props.users, t('common.unassigned'))
}

function assigneeAvatar(task: Task): string | null {
  const u = resolveAssigneeUser(task, props.users)
  return u?.avatar_url ?? null
}

function hasAssignee(task: Task): boolean {
  return taskHasAssignableDisplay(task, props.users)
}

function assigneeInitial(task: Task): string {
  return getInitials(assigneeName(task))
}

function assigneeFallbackStyle(task: Task): { background: string; color: string } | undefined {
  const u = resolveAssigneeUser(task, props.users)
  if (u == null) return undefined
  return getAvatarColor(u.id)
}

function updatedText(task: Task): string {
  return new Date(task.updatedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function projectDisplayLabel(task: Task): string | null {
  if (task.projectId == null) return null
  const p = projectStore.projects.find((x) => x.id === task.projectId)
  return p?.identifier ?? p?.name ?? `P-${task.projectId}`
}

const TERMINAL_STATUSES: Status[] = ['done', 'canceled', 'duplicate']
function isOverdue(task: Task): boolean {
  if (task.dueDate == null || TERMINAL_STATUSES.includes(task.status)) return false
  return task.dueDate < Date.now()
}

function statusLabel(s: Status): string {
  return getStatusLabel(s)
}

async function onStatusPicked(task: Task, next: Status) {
  await store.transitionTask(task.id, next)
}

async function onAssigneePicked(
  task: Task,
  userId: number | null,
  opts: { clearAssignee: boolean }
) {
  try {
    if (opts.clearAssignee) {
      await store.updateTask(task.id, { clearAssignee: true })
    } else if (userId != null) {
      await store.updateTask(task.id, { assigneeId: userId })
    }
  } catch {
    /* store.error */
  }
}

async function onInlinePlannedCommit(task: Task, value: number | null) {
  try {
    if (value == null) await store.updateTask(task.id, { clearPlannedStart: true })
    else await store.updateTask(task.id, { plannedStartDate: value })
  } catch {
    /* store.error */
  }
}

async function onInlineDueCommit(task: Task, value: number | null) {
  try {
    if (value == null) await store.updateTask(task.id, { clearDueDate: true })
    else await store.updateTask(task.id, { dueDate: value })
  } catch {
    /* store.error */
  }
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

function showLabelCluster(task: Task): boolean {
  const listOn = show('labels') && sortedTaskLabelsForList(task.labels).length > 0
  const projOn = show('project') && projectDisplayLabel(task) != null
  return listOn || projOn
}

function setHoveredId(id: string | null) {
  rowHoveredId.value = id
}

/** 每组用于渲染的行（含子任务时带 depth/parentTitle） */
function groupListRows(group: TaskGroup): TaskRow[] {
  if (group.rows?.length) return group.rows
  return group.tasks.map((t) => ({ task: t, depth: 0 }))
}

function visibleGroupRows(group: TaskGroup): TaskRow[] {
  return filterVisibleTaskRows(groupListRows(group), subtaskExpanded.value)
}

function hasExpandableSubtasksInGroup(task: Task, group: TaskGroup): boolean {
  if ((task.subIssueCount ?? 0) > 0) return true
  const rows = groupListRows(group)
  const idx = rows.findIndex((r) => r.task.id === task.id)
  if (idx === -1) return false
  const cur = rows[idx]!
  const next = rows[idx + 1]
  if (!next || next.depth !== cur.depth + 1) return false
  const path = next.subtaskExpandPath
  return path != null && path.length > 0 && path[path.length - 1] === task.id
}

/** 与 filterVisibleTaskRows 一致：凡出现在子行 path 中的父 id 都需展开，避免仅靠 subIssueCount/邻行判断漏掉多级祖先 */
function expandableParentTaskIdsInGroup(group: TaskGroup): string[] {
  const rows = groupListRows(group)
  const idSet = new Set<string>()
  for (const row of rows) {
    const path = row.subtaskExpandPath
    if (!path?.length) continue
    for (const id of path) {
      idSet.add(id)
    }
  }
  return [...idSet]
}

function groupHasExpandableSubtasks(group: TaskGroup): boolean {
  return expandableParentTaskIdsInGroup(group).length > 0
}

/** 当前视图任意分组存在可展开父任务时，所有分组统一留展开列，避免分组间列错位 */
const listHasExpandableSubtasks = computed(() =>
  props.groups.some((g) => groupHasExpandableSubtasks(g))
)

function groupAllExpandableExpanded(group: TaskGroup): boolean {
  const ids = expandableParentTaskIdsInGroup(group)
  return ids.length > 0 && ids.every((id) => subtaskExpanded.value[id] === true)
}

function toggleExpandAllSubtasksInGroup(group: TaskGroup, e: MouseEvent) {
  if (groupAllExpandableExpanded(group)) {
    collapseAllSubtasksInGroup(group, e)
  } else {
    expandAllSubtasksInGroup(group, e)
  }
}

function expandAllSubtasksInGroup(group: TaskGroup, e: MouseEvent) {
  e.stopPropagation()
  const next = { ...subtaskExpanded.value }
  for (const id of expandableParentTaskIdsInGroup(group)) {
    next[id] = true
  }
  subtaskExpanded.value = next
}

function collapseAllSubtasksInGroup(group: TaskGroup, e: MouseEvent) {
  e.stopPropagation()
  const next = { ...subtaskExpanded.value }
  for (const id of expandableParentTaskIdsInGroup(group)) {
    next[id] = false
  }
  subtaskExpanded.value = next
}

function isSubtasksExpanded(taskId: string): boolean {
  return subtaskExpanded.value[taskId] === true
}

function toggleSubtasksExpanded(taskId: string, e: MouseEvent) {
  e.stopPropagation()
  subtaskExpanded.value = {
    ...subtaskExpanded.value,
    [taskId]: !subtaskExpanded.value[taskId]
  }
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

async function copyTaskTitle(e: MouseEvent, taskId: string, title: string) {
  e.stopPropagation()
  try {
    await navigator.clipboard.writeText(title)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = title
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
    } finally {
      document.body.removeChild(ta)
    }
  }
  if (copyFeedbackClearTimer != null) {
    clearTimeout(copyFeedbackClearTimer)
  }
  copyFeedbackTaskId.value = taskId
  copyFeedbackClearTimer = setTimeout(() => {
    copyFeedbackTaskId.value = null
    copyFeedbackClearTimer = null
  }, COPY_FEEDBACK_MS)
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
            <span class="group-toggle-chevron tree-chevron-glyph" aria-hidden="true">{{
              collapsed[group.key] ? '▸' : '▾'
            }}</span>
            <span class="group-title">{{ group.label }}</span>
            <span class="group-count">{{ visibleGroupRows(group).length }}</span>
          </button>
          <div
            v-if="groupHasExpandableSubtasks(group)"
            class="group-subtask-bulk"
            @click.stop
          >
            <button
              type="button"
              class="group-subtask-bulk-btn"
              :aria-expanded="groupAllExpandableExpanded(group)"
              :aria-label="
                groupAllExpandableExpanded(group)
                  ? t('taskList.collapseAllSubtasks')
                  : t('taskList.expandAllSubtasks')
              "
              :title="
                groupAllExpandableExpanded(group)
                  ? t('taskList.collapseAllSubtasks')
                  : t('taskList.expandAllSubtasks')
              "
              @click="toggleExpandAllSubtasksInGroup(group, $event)"
            >
              <UnfoldVertical
                v-if="!groupAllExpandableExpanded(group)"
                class="group-subtask-bulk-icon"
                stroke-width="2"
                aria-hidden="true"
              />
              <FoldVertical
                v-else
                class="group-subtask-bulk-icon"
                stroke-width="2"
                aria-hidden="true"
              />
            </button>
          </div>
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
            v-for="row in visibleGroupRows(group)"
            :key="row.task.id"
            class="task-row"
            :class="{ overdue: isOverdue(row.task), selected: props.selectedTaskId === row.task.id }"
            :style="{ paddingLeft: row.depth > 0 ? `calc(var(--task-row-pad-left) + ${row.depth * 20}px)` : undefined }"
            tabindex="0"
            @mouseenter="setHoveredId(row.task.id)"
            @mouseleave="setHoveredId(null)"
            @click="onRowClick(row.task)"
          >
            <div class="task-row-meta-leading">
              <div
                class="task-row-sub-expand"
                :class="{
                  'task-row-sub-expand--empty':
                    !hasExpandableSubtasksInGroup(row.task, group) &&
                    !listHasExpandableSubtasks
                }"
              >
                <button
                  v-if="hasExpandableSubtasksInGroup(row.task, group)"
                  type="button"
                  class="task-row-sub-toggle"
                  :aria-expanded="isSubtasksExpanded(row.task.id)"
                  :aria-label="
                    isSubtasksExpanded(row.task.id) ? t('taskList.collapseSubtasks') : t('taskList.expandSubtasks')
                  "
                  @click="toggleSubtasksExpanded(row.task.id, $event)"
                >
                  <span class="tree-chevron-glyph" aria-hidden="true">{{
                    isSubtasksExpanded(row.task.id) ? '▾' : '▸'
                  }}</span>
                </button>
              </div>
              <div
                class="task-row-select"
                :class="{ visible: selectedTaskIds.size > 0 || rowHoveredId === row.task.id }"
                @click.stop
              >
                <input
                  type="checkbox"
                  class="task-row-checkbox"
                  :checked="selectedTaskIds.has(row.task.id)"
                  @click.stop="toggleSelection(row.task.id)"
                >
              </div>
              <div class="task-row-leading">
                <component
                  :is="priorityIcons[row.task.priority]"
                  class="icon icon-14 priority-icon"
                  :aria-label="row.task.priority"
                />
              </div>
            </div>
            <span class="task-row-key">{{ row.task.id }}</span>
            <TaskRowStatusPicker
              :task-id="row.task.id"
              :status="row.task.status"
              @change="(s) => onStatusPicked(row.task, s)"
            />
            <div class="task-row-content">
              <span class="task-row-title-cluster">
                <span class="task-row-title">{{ rowTitle(row) }}</span>
                <button
                  type="button"
                  class="task-row-copy-title"
                  :class="{
                    visible: rowHoveredId === row.task.id || copyFeedbackTaskId === row.task.id,
                    success: copyFeedbackTaskId === row.task.id
                  }"
                  :aria-label="
                    copyFeedbackTaskId === row.task.id ? t('taskList.titleCopied') : t('taskList.copyTitle')
                  "
                  :title="
                    copyFeedbackTaskId === row.task.id ? t('taskList.titleCopied') : t('taskList.copyTitle')
                  "
                  @click.stop="copyTaskTitle($event, row.task.id, rowTitle(row))"
                >
                  <Check
                    v-if="copyFeedbackTaskId === row.task.id"
                    class="task-row-copy-title-icon"
                    stroke-width="2.5"
                    aria-hidden="true"
                  />
                  <Copy v-else class="task-row-copy-title-icon" stroke-width="2" aria-hidden="true" />
                </button>
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
              <div
                v-if="showLabelCluster(row.task)"
                class="task-row-label-cluster"
                @click.stop
              >
                <template v-if="show('labels')">
                  <span
                    v-for="lab in sortedTaskLabelsForList(row.task.labels)"
                    :key="lab.id"
                    class="task-row-label-pill"
                  >
                    <span
                      class="task-row-label-dot"
                      :style="{ background: labelListDotColor(lab.name) }"
                      aria-hidden="true"
                    />
                    <span class="task-row-label-text">{{ lab.name }}</span>
                  </span>
                </template>
                <span
                  v-if="show('project') && projectDisplayLabel(row.task)"
                  class="task-row-label-pill task-row-label-pill--project"
                >
                  <Circle
                    class="task-row-label-project-icon"
                    stroke-width="1.75"
                    aria-hidden="true"
                  />
                  <span class="task-row-label-text">{{ projectDisplayLabel(row.task) }}</span>
                </span>
              </div>
            </div>
            <div class="task-row-trailing">
              <template v-if="show('status')">
                <span class="task-meta-slot task-meta-slot-status">
                  <span class="task-meta task-meta-status" :class="row.task.status">{{ statusLabel(row.task.status) }}</span>
                </span>
              </template>
              <template v-if="show('assignee')">
                <span class="task-meta-slot task-meta-slot-assignee">
                  <span class="task-meta task-meta-assignee">
                    <TaskRowAssigneePicker
                      :task-id="row.task.id"
                      :task="row.task"
                      :users="users"
                      :tooltip="assigneeName(row.task)"
                      @pick="(uid, o) => onAssigneePicked(row.task, uid, o)"
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
                    </TaskRowAssigneePicker>
                  </span>
                </span>
              </template>
              <template v-if="show('plannedStart')">
                <span class="task-meta-slot task-meta-slot-date task-meta-slot-planned-start">
                  <TaskRowInlineDateCell
                    :task-id="row.task.id"
                    :date-ms="row.task.plannedStartDate"
                    variant="planned"
                    @commit="(v) => onInlinePlannedCommit(row.task, v)"
                  />
                </span>
              </template>
              <template v-if="show('dueDate')">
                <span class="task-meta-slot task-meta-slot-date task-meta-slot-due">
                  <TaskRowInlineDateCell
                    :task-id="row.task.id"
                    :date-ms="row.task.dueDate"
                    variant="due"
                    :overdue="isOverdue(row.task)"
                    @commit="(v) => onInlineDueCommit(row.task, v)"
                  />
                </span>
              </template>
              <template v-if="show('progress')">
                <span class="task-meta-slot task-meta-slot-progress">
                  <TaskRowProgressCell :task="row.task" />
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
    <Teleport to="body">
      <transition name="fade-up">
        <div v-if="selectedTaskIds.size > 0" class="bulk-action-bar-wrapper">
          <div class="bulk-action-bar">
            <div class="bulk-bar-pill bulk-bar-count">
              <span class="bulk-text">{{ t('taskList.bulk.selectedCount', { n: selectedTaskIds.size }) }}</span>
            </div>
            <button type="button" class="bulk-bar-dismiss" aria-label="Clear selection" @click="clearSelection">
              <X class="bulk-icon-14" stroke-width="2.5" aria-hidden="true" />
            </button>
            <button type="button" class="bulk-bar-pill bulk-action-btn" @click="bulkCommandsOpen = true">
              <span class="bulk-kbd">⌘</span> <span class="bulk-text">{{ t('taskList.bulk.actions') }}</span>
            </button>
          </div>
        </div>
      </transition>
      <CommandPalette
        v-if="bulkCommandsOpen"
        :open="bulkCommandsOpen"
        :badge="t('taskList.bulk.issuesBadge', { n: selectedTaskIds.size })"
        :commands="bulkPaletteCommands"
        :nested-depth="bulkPaletteNestedDepth"
        :nested-title="bulkPaletteNestedTitle"
        @back="onBulkPaletteBack"
        @close="bulkCommandsOpen = false"
      />
    </Teleport>
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
  /** 子任务展开列宽：与分组 chevron 同宽，整表无子任务树时收起 */
  --task-row-sub-expand-width: 22px;
  --task-row-pad-left: 6px;
  /** 展开钮 / 复选框 / 优先级 三者之间的间距（行内其余列仍用 .task-row 的 gap） */
  --task-row-meta-leading-gap: 3px;
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
.tree-chevron-glyph {
  font-size: 15px;
  line-height: 1;
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-muted);
}

.group-toggle {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  min-height: 32px;
}
.group-toggle-chevron {
  flex: 0 0 auto;
  width: var(--task-row-sub-expand-width);
  height: var(--task-row-sub-expand-width);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.group-title {
  flex: 0 0 auto;
}
.group-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  margin-left: 4px;
}
.group-subtask-bulk {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.group-subtask-bulk-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
}
.group-subtask-bulk-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.group-subtask-bulk-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}
.group-create {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-body);
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
.task-row-meta-leading {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex: 0 0 auto;
  gap: var(--task-row-meta-leading-gap);
}
.task-row-sub-expand {
  flex: 0 0 var(--task-row-sub-expand-width);
  width: var(--task-row-sub-expand-width);
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 仅当整表都没有可展开父任务时收起；否则保留宽度，跨分组列对齐 */
.task-row-sub-expand--empty {
  flex: 0 0 0;
  width: 0;
  min-width: 0;
  overflow: hidden;
}
.task-row-sub-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: var(--task-row-sub-expand-width);
  min-height: var(--task-row-sub-expand-width);
  width: var(--task-row-sub-expand-width);
  height: var(--task-row-sub-expand-width);
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  color: var(--color-text-muted);
}
.task-row-sub-toggle:hover {
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
}
.task-row-sub-toggle:hover .tree-chevron-glyph {
  color: var(--color-text-secondary);
}

.task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  height: 36px;
  padding: 0 12px 0 var(--task-row-pad-left);
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
  flex: 0 0 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
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

.task-row-content {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.task-row-title-cluster {
  flex: 1 1 auto;
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
.task-row-copy-title {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}
.task-row-copy-title.visible {
  opacity: 1;
}
.task-row-copy-title.success {
  color: var(--color-status-done);
  pointer-events: none;
}
.task-row-copy-title:hover:not(.success) {
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
}
.task-row-copy-title-icon {
  width: 14px;
  height: 14px;
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

.task-row-label-cluster {
  flex: 0 1 auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: min(420px, 46%);
  overflow-x: auto;
  flex-wrap: nowrap;
  scrollbar-width: thin;
  -webkit-overflow-scrolling: touch;
}
.task-row-label-cluster::-webkit-scrollbar {
  height: 4px;
}
.task-row-label-cluster::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: 4px;
}
.task-row-label-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  padding: 2px 9px 2px 7px;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  background: var(--color-bg-base);
  border: 1px solid #e6e8eb;
  border-radius: 999px;
  line-height: 1.2;
  white-space: nowrap;
}
.task-row-label-pill--project {
  color: var(--color-text-muted);
}
.task-row-label-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.task-row-label-text {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-row-label-project-icon {
  width: 11px;
  height: 11px;
  flex-shrink: 0;
  color: var(--color-text-muted);
  stroke: currentColor;
  fill: none;
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
.task-meta-slot-status {
  min-width: 88px;
}
.task-meta-slot-assignee {
  width: 26px;
}
.task-meta-slot-date {
  min-width: 88px;
  width: 88px;
}

.task-meta-slot-progress {
  min-width: 100px;
}

.task-meta-with-icon {
  justify-content: flex-end;
}

.task-meta-date-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  opacity: 0.7;
  color: var(--color-text-muted);
}

.task-meta.overdue .task-meta-date-icon {
  color: var(--color-status-warning);
  opacity: 1;
}

.task-meta-progress-cell {
  gap: 8px;
}

.task-progress-track {
  width: 52px;
  height: 5px;
  border-radius: 999px;
  background: var(--color-bg-muted);
  overflow: hidden;
  flex-shrink: 0;
}

.task-progress-fill {
  display: block;
  height: 100%;
  min-width: 0;
  border-radius: 999px;
  background: var(--color-accent, #5f6eea);
  transition: width var(--transition-fast);
}

.task-progress-pct {
  font-variant-numeric: tabular-nums;
  min-width: 2.25rem;
  text-align: right;
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
.task-assignee-trigger:focus-visible::before {
  opacity: 1;
  transform: translate(-50%, -2px);
}
.task-assignee-trigger:focus-visible::after {
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

/* 批量操作复选框 */
.task-row-select {
  flex: 0 0 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  opacity: 0;
  pointer-events: none;
}
.task-row-select.visible {
  opacity: 1;
  pointer-events: auto;
}
.task-row-checkbox {
  width: 14px;
  height: 14px;
  cursor: pointer;
  accent-color: var(--color-accent);
  border-radius: 3px;
  margin: 0;
}

/* 底部悬浮操作条 */
.bulk-action-bar-wrapper {
  position: fixed;
  bottom: 32px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  pointer-events: none;
  z-index: 200;
}
.bulk-action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  pointer-events: auto;
}
.bulk-bar-pill {
  display: flex;
  align-items: center;
  background: #ffffff;
  border: 1px solid var(--color-border);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
  border-radius: 20px;
  height: 36px;
  padding: 0 14px;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 500;
}
.bulk-bar-dismiss {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--color-border);
  background: #ffffff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
  color: var(--color-text-secondary);
  cursor: pointer;
  border-radius: 50%;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.bulk-bar-dismiss:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.bulk-icon-14 {
  width: 14px;
  height: 14px;
}
.bulk-action-btn {
  padding: 0 16px;
  cursor: pointer;
  transition: box-shadow var(--transition-fast);
}
.bulk-action-btn:hover {
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12), 0 2px 4px rgba(0, 0, 0, 0.05);
}
.bulk-kbd {
  font-family: inherit;
  font-size: 13px;
  color: var(--color-text-secondary);
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: 4px;
  padding: 1px 4px;
  margin-right: 8px;
  font-weight: 500;
}

html[data-theme="dark"] .bulk-bar-pill {
  background: var(--color-bg-elevated);
}

.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity 0.2s ease, transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.fade-up-enter-from,
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.96);
}
</style>
