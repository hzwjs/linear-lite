<script setup lang="ts">
import { ref, watch, onUnmounted, computed, onMounted, nextTick, type ComponentPublicInstance } from 'vue'
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
  Loader2,
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
import type { AssigneeOption, Task, Status, Priority } from '../types/domain'
import type { TaskLabelWriteItem } from '../services/api/types'
import { toLabelWriteItem } from '../utils/taskLabelWrite'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'
import { useAssigneeStore } from '../store/assigneeStore'
import { projectApi } from '../services/api/project'
import { getStatusLabel, getPriorityLabel } from '../utils/enumLabels'
import { parseDateInputValue, formatDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { formatBeijingDate } from '../utils/beijingTime'
import { labelListDotColor, sortedTaskLabelsForList } from '../utils/taskLabelListDisplay'
import { filterVisibleTaskRows, type TaskGroup, type TaskRow } from '../utils/taskView'
import type { VisibleProperty } from '../utils/viewPreference'
import { getSubtaskProgressDisplay } from '../utils/subtaskProgress'
import { assigneeDisplayLabel } from '../utils/taskAssigneeDisplay'
import { userAssigneeKey } from '../utils/assigneeKey'
import { copyTextToClipboard } from '../utils/clipboard'
import TaskRowStatusPicker from './TaskRowStatusPicker.vue'
import TaskRowAssigneePicker from './TaskRowAssigneePicker.vue'
import TaskRowInlineDateCell from './TaskRowInlineDateCell.vue'
import TaskRowProgressCell from './TaskRowProgressCell.vue'
import TaskListWindowRows from './TaskListWindowRows.vue'

const props = defineProps<{
  groups: TaskGroup[]
  grouped: boolean
  expandGroupsOnEntry: boolean
  projectId?: number | null
  visibleProperties?: VisibleProperty[]
  selectedTaskId?: string | null
  openingTaskId?: string | null
}>()

const emit = defineEmits<{
  rowClick: [task: Task]
  createInStatus: [status?: Status]
  addSubIssue: [task: Task]
  bulkStatusChange: [taskIds: string[], status: Status]
}>()

const subtaskExpanded = defineModel<Record<string, boolean>>('subtaskExpanded', { default: () => ({}) })

const store = useTaskStore()
const projectStore = useProjectStore()
const authStore = useAuthStore()
const assigneeStore = useAssigneeStore()
const { t } = useI18n()
const collapsedGroupKeys = ref(new Set<string>())
const scrollRef = ref<HTMLElement | null>(null)
const taskRowHeight = 42
const groupHeaderHeight = 50
const pinnedTaskId = ref<string | null>(null)
const groupWindowRefs = new Map<string, InstanceType<typeof TaskListWindowRows>>()

function setGroupWindowRef(key: string, instance: Element | ComponentPublicInstance | null) {
  if (instance == null) groupWindowRefs.delete(key)
  else groupWindowRefs.set(key, instance as InstanceType<typeof TaskListWindowRows>)
}

function trackInteractionRow(event: Event) {
  const target = event.target
  if (!(target instanceof Element)) return
  const row = target.closest<HTMLElement>('[data-task-id]')
  if (row && scrollRef.value?.contains(row)) {
    pinnedTaskId.value = row.dataset.taskId ?? null
  } else if (event.type === 'pointerdown' && scrollRef.value?.contains(target)) {
    // 拖动滚动条不改变焦点，保留原交互行；焦点进入其他控件时释放。
    return
  } else if (!target.closest('.task-row-status-panel, .assignee-popover, .custom-date-picker-panel')) {
    pinnedTaskId.value = null
  }
}
const rowHoveredId = ref<string | null>(null)
/** 复制成功后的短暂反馈（图标与提示文案） */
const copyFeedbackTaskId = ref<string | null>(null)
const copyFeedbackKind = ref<'title' | 'taskKey' | null>(null)
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

const sortedPickerAssignees = computed<AssigneeOption[]>(() => {
  const projectId = props.projectId ?? projectStore.activeProjectId
  const list = [...(projectId == null ? [] : assigneeStore.optionsByProject[projectId] ?? [])]
  list.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
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
  const existingItems = existing.flatMap((l) => {
    const item = toLabelWriteItem(l)
    return item ? [item] : []
  })
  if (existing.some((l) => l.id === addedLabelId && addedLabelId > 0)) {
    return existingItems
  }
  return [...existingItems, { id: addedLabelId }]
}

async function bulkUpdateAll(updater: (taskKey: string) => Promise<unknown>): Promise<void> {
  const ids = [...selectedTaskIds.value]
  await Promise.allSettled(ids.map((id) => updater(id)))
}

async function applyBulkAssign(assigneeKey: string) {
  await bulkUpdateAll((id) => store.updateTask(id, { assigneeKey }))
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
  await bulkUpdateAll((id) => store.updateTask(id, { assigneeKey: userAssigneeKey(uid) }))
  closeBulkPalette()
  clearSelection()
}

function applyBulkStatus(status: Status) {
  // 工作区承接批量保存及结果反馈，覆盖筛选清空后列表卸载的情况。
  emit('bulkStatusChange', [...selectedTaskIds.value], status)
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
    for (const assignee of sortedPickerAssignees.value) {
      const name = assignee.name
      rows.push({
        id: `bulk-assign-${assignee.key}`,
        label: name,
        keywords: [name.toLowerCase()],
        icon: UserIcon,
        run: () => {
          void applyBulkAssign(assignee.key)
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
  document.addEventListener('pointerdown', trackInteractionRow, true)
  document.addEventListener('focusin', trackInteractionRow, true)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.removeEventListener('pointerdown', trackInteractionRow, true)
  document.removeEventListener('focusin', trackInteractionRow, true)
  if (copyFeedbackClearTimer != null) {
    clearTimeout(copyFeedbackClearTimer)
    copyFeedbackClearTimer = null
  }
  copyFeedbackKind.value = null
})
const subtaskRingRadius = 5
const subtaskRingCircumference = 2 * Math.PI * subtaskRingRadius

const priorityIcons: Record<Priority, typeof PriorityUrgentIcon> = {
  urgent: PriorityUrgentIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon
}

function isGroupExpanded(groupKey: string): boolean {
  return !props.grouped || !collapsedGroupKeys.value.has(groupKey)
}

function toggle(groupKey: string) {
  const next = new Set(collapsedGroupKeys.value)
  if (next.has(groupKey)) next.delete(groupKey)
  else next.add(groupKey)
  collapsedGroupKeys.value = next
}

function resetGroupExpansion() {
  const selectedGroup = props.groups.find(group => group.rows != null
    ? group.rows.some(row => row.task.id === props.selectedTaskId)
    : group.tasks.some(task => task.id === props.selectedTaskId))
  const preferredKey = selectedGroup?.key ?? props.groups[0]?.key
  collapsedGroupKeys.value = props.expandGroupsOnEntry || !props.grouped
    ? new Set()
    : new Set(props.groups.filter(group => group.key !== preferredKey).map(group => group.key))
}

watch(
  () => [props.projectId, props.grouped, props.expandGroupsOnEntry],
  resetGroupExpansion,
  { immediate: true }
)
watch(
  () => props.groups.map(group => group.key),
  (keys, previousKeys) => {
    const collapsed = new Set([...collapsedGroupKeys.value].filter(key => keys.includes(key)))
    if (props.grouped && !props.expandGroupsOnEntry) {
      for (const key of keys) {
        if (!previousKeys.includes(key)) collapsed.add(key)
      }
    }
    collapsedGroupKeys.value = collapsed
    if (props.grouped && !props.expandGroupsOnEntry && keys.every(key => collapsed.has(key))) {
      resetGroupExpansion()
    }
  }
)

async function locateTask(taskId: string, focus = true): Promise<boolean> {
  const group = props.groups.find((candidate) => candidate.rows != null
    ? candidate.rows.some((row) => row.task.id === taskId)
    : candidate.tasks.some((task) => task.id === taskId))
  if (!group) return false
  collapsedGroupKeys.value.delete(group.key)
  const row = group.rows?.find((candidate) => candidate.task.id === taskId)
  if (row?.subtaskExpandPath) {
    const expanded = { ...subtaskExpanded.value }
    for (const id of row.subtaskExpandPath) expanded[id] = true
    subtaskExpanded.value = expanded
  }
  await nextTick()
  if (!(await groupWindowRefs.get(group.key)?.ensureTaskVisible(taskId))) return false
  const element = Array.from(scrollRef.value?.querySelectorAll<HTMLElement>('[data-task-id]') ?? [])
    .find((candidate) => candidate.dataset.taskId === taskId)
  if (!element) return false
  // 排序保持生效，通过定位展示更新后的行，避免冻结旧顺序。
  element.scrollIntoView({ block: 'nearest' })
  if (focus) element.focus({ preventScroll: true })
  return true
}

defineExpose({ locateTask })

watch(
  () => props.selectedTaskId,
  (taskId) => {
    if (taskId == null) return
    void locateTask(taskId, false)
  },
  { immediate: true, flush: 'post' }
)

function assigneeName(task: Task): string {
  return assigneeDisplayLabel(task, t('common.unassigned'))
}

function updatedText(task: Task): string {
  return formatBeijingDate(task.updatedAt)
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

async function onStatusPicked(task: Task, next: Status) {
  try {
    await store.transitionTask(task.id, next)
  } catch {
    // 状态保存结果由工作区统一反馈，失败时 store 恢复服务端确认的状态。
  }
}

async function onAssigneePicked(
  task: Task,
  assigneeKey: string | null,
  opts: { clearAssignee: boolean }
) {
  try {
    if (opts.clearAssignee) {
      await store.updateTask(task.id, { clearAssignee: true })
    } else if (assigneeKey != null) {
      await store.updateTask(task.id, { assigneeKey })
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
const groupRowsByKey = computed(() => {
  const rowsByKey = new Map<string, TaskRow[]>()
  for (const group of props.groups) {
    rowsByKey.set(group.key, group.rows != null ? group.rows : group.tasks.map(task => ({ task, depth: 0 })))
  }
  return rowsByKey
})
const visibleRowsByGroup = computed(() => new Map([...groupRowsByKey.value].map(([key, rows]) => [
  key, filterVisibleTaskRows(rows, subtaskExpanded.value)
])))
const groupLayoutKey = computed(() => props.groups.map(group =>
  `${group.key}:${isGroupExpanded(group.key) ? visibleGroupRows(group).length : 0}`
).join('|'))
const expandableTaskIdsByGroup = computed(() => {
  const index = new Map<string, Set<string>>()
  for (const [key, rows] of groupRowsByKey.value) {
    const ids = new Set<string>()
    rows.forEach((row, position) => {
      if ((row.task.subIssueCount ?? 0) > 0) ids.add(row.task.id)
      const next = rows[position + 1]
      const path = next?.subtaskExpandPath
      if (next?.depth === row.depth + 1 && path?.[path.length - 1] === row.task.id) ids.add(row.task.id)
    })
    index.set(key, ids)
  }
  return index
})

function visibleGroupRows(group: TaskGroup): TaskRow[] {
  return visibleRowsByGroup.value.get(group.key) ?? []
}

function hasExpandableSubtasksInGroup(task: Task, group: TaskGroup): boolean {
  return expandableTaskIdsByGroup.value.get(group.key)?.has(task.id) === true
}

/** 与 filterVisibleTaskRows 一致：凡出现在子行 path 中的父 id 都需展开，避免仅靠 subIssueCount/邻行判断漏掉多级祖先 */
/**
 * 任意分组存在可展开父任务时，全表统一留展开列，避免分组间、行与行之间列错位。
 * 仅看 subtaskExpandPath 不够：筛选后子行可能不在当前 group.rows 里，但父任务仍可能因
 * subIssueCount 显示展开钮；此时若不占位，仅带箭头的行会与分组头对齐，而其它行更靠右。
 */
const listHasExpandableSubtasks = computed(() =>
  [...expandableTaskIdsByGroup.value.values()].some(ids => ids.size > 0)
)

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
  // 非安全上下文（http 非 localhost）下 Clipboard API 不可用，copyTextToClipboard 内部降级为 execCommand
  await copyTextToClipboard(title)
  if (copyFeedbackClearTimer != null) {
    clearTimeout(copyFeedbackClearTimer)
  }
  copyFeedbackTaskId.value = taskId
  copyFeedbackKind.value = 'title'
  copyFeedbackClearTimer = setTimeout(() => {
    copyFeedbackTaskId.value = null
    copyFeedbackKind.value = null
    copyFeedbackClearTimer = null
  }, COPY_FEEDBACK_MS)
}

async function copyTaskKey(e: MouseEvent, taskId: string) {
  e.stopPropagation()
  // 非安全上下文（http 非 localhost）下 Clipboard API 不可用，copyTextToClipboard 内部降级为 execCommand
  await copyTextToClipboard(taskId)
  if (copyFeedbackClearTimer != null) clearTimeout(copyFeedbackClearTimer)
  copyFeedbackTaskId.value = taskId
  copyFeedbackKind.value = 'taskKey'
  copyFeedbackClearTimer = setTimeout(() => {
    copyFeedbackTaskId.value = null
    copyFeedbackKind.value = null
    copyFeedbackClearTimer = null
  }, COPY_FEEDBACK_MS)
}
</script>

<template>
  <div class="list-view" :style="{ '--task-row-height': `${taskRowHeight}px` }">
    <div ref="scrollRef" class="list-view-scroll">
      <div v-for="group in groups" :key="group.key" class="group">
        <div v-if="grouped" class="group-header">
          <button
            type="button"
            class="group-toggle"
            :aria-expanded="isGroupExpanded(group.key)"
            @click="toggle(group.key)"
          >
            <span class="group-toggle-chevron tree-chevron-glyph" aria-hidden="true">{{
              isGroupExpanded(group.key) ? '▾' : '▸'
            }}</span>
            <span class="group-title">{{ group.label }}</span>
            <span class="group-count">{{ visibleGroupRows(group).length }}</span>
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
        <TaskListWindowRows
          v-if="isGroupExpanded(group.key)"
          :ref="instance => setGroupWindowRef(group.key, instance)"
          class="group-rows"
          :rows="visibleGroupRows(group)"
          :scroll-root="scrollRef"
          :row-height="taskRowHeight"
          :layout-key="groupLayoutKey"
          :top-inset="grouped ? groupHeaderHeight : 0"
          :pinned-task-id="pinnedTaskId"
        >
          <template #default="{ row, index, total }">
          <div
            :key="row.task.id"
            :data-task-id="row.task.id"
            class="task-row"
            :class="{
              overdue: isOverdue(row.task),
              selected: props.selectedTaskId === row.task.id,
              'task-row--opening': props.openingTaskId === row.task.id
            }"
            :aria-busy="props.openingTaskId === row.task.id"
            :style="{ paddingLeft: row.depth > 0 ? `calc(var(--task-row-pad-left) + ${row.depth * 20}px)` : undefined }"
            tabindex="0"
            role="listitem"
            :aria-posinset="index + 1"
            :aria-setsize="total"
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
            <span class="task-row-key-wrap" :title="row.task.id">
              <span class="task-row-key">{{ row.task.id }}</span>
              <button
                type="button"
                class="task-row-copy-key"
                :class="{ visible: rowHoveredId === row.task.id || (copyFeedbackTaskId === row.task.id && copyFeedbackKind === 'taskKey'), success: copyFeedbackTaskId === row.task.id && copyFeedbackKind === 'taskKey' }"
                :aria-label="copyFeedbackTaskId === row.task.id && copyFeedbackKind === 'taskKey' ? t('taskList.taskKeyCopied') : t('taskList.copyTaskKey')"
                :title="copyFeedbackTaskId === row.task.id && copyFeedbackKind === 'taskKey' ? t('taskList.taskKeyCopied') : t('taskList.copyTaskKey')"
                @click.stop="copyTaskKey($event, row.task.id)"
              >
                <Check v-if="copyFeedbackTaskId === row.task.id && copyFeedbackKind === 'taskKey'" class="task-row-copy-key-icon" stroke-width="2.5" aria-hidden="true" />
                <Copy v-else class="task-row-copy-key-icon" stroke-width="2" aria-hidden="true" />
              </button>
            </span>
            <TaskRowStatusPicker
              :task-id="row.task.id"
              :status="row.task.status"
              @change="(s) => onStatusPicked(row.task, s)"
            />
            <div class="task-row-content">
              <span class="task-row-title-cluster">
                <span class="task-row-title">{{ rowTitle(row) }}</span>
                <Loader2 v-if="props.openingTaskId === row.task.id" class="task-row-opening-spinner" :size="14" aria-label="正在打开任务" />
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
              <template v-if="show('assignee')">
                <span class="task-meta-slot task-meta-slot-assignee">
                  <span class="task-meta task-meta-assignee">
                    <TaskRowAssigneePicker
                      :task-id="row.task.id"
                      :task="row.task"
                      :tooltip="assigneeName(row.task)"
                      @pick="(uid, o) => onAssigneePicked(row.task, uid, o)"
                    />
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
          </template>
        </TaskListWindowRows>
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
  /** 任务编号列固定宽度，确保后续状态列在纵向列表中对齐 */
  --task-row-key-column-width: 112px;
}

.list-view-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

/* 分组：轻底 + 稍重边框 + 字重，让标题更易辨认 */
.group {
  margin: 0 12px;
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
  margin: 8px 0 4px;
  padding: 0 10px;
  min-height: 38px;
  background: linear-gradient(90deg, var(--color-bg-muted), var(--color-bg-subtle));
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.025);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}
.group-header:hover {
  background: var(--color-bg-muted);
  border-color: var(--color-border);
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
  min-height: 34px;
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
.group-create {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-body);
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
  flex-shrink: 0;
  transition: background var(--transition-fast), color var(--transition-fast);
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
  min-height: var(--task-row-height);
  height: var(--task-row-height);
  padding: 0 16px 0 var(--task-row-pad-left);
  cursor: pointer;
  border-bottom: 1px solid var(--list-row-border);
  transition: background-color var(--transition-fast), box-shadow var(--transition-fast);
  outline: none;
}
.task-row:last-child {
  border-bottom: none;
}
.task-row:hover {
  background: color-mix(in srgb, var(--color-bg-hover) 68%, transparent);
}
.task-row.selected {
  background: var(--color-bg-hover);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
.task-row.selected:hover {
  background: var(--color-bg-hover);
}
.task-row--opening {
  background: var(--color-bg-hover);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
.task-row-opening-spinner {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  animation: task-row-opening-spin 700ms linear infinite;
}
@keyframes task-row-opening-spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .task-row-opening-spinner { animation: none; }
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
  flex: 1 1 auto;
  min-width: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-row-key-wrap {
  display: inline-flex;
  align-items: center;
  flex: 0 0 var(--task-row-key-column-width);
  width: var(--task-row-key-column-width);
  min-width: var(--task-row-key-column-width);
  gap: 3px;
  overflow: hidden;
}
.task-row-copy-key {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.task-row-copy-key.visible,
.task-row-key-wrap:hover .task-row-copy-key,
.task-row-copy-key:focus-visible { opacity: 1; }
.task-row-copy-key:hover,
.task-row-copy-key:focus-visible { color: var(--color-text-primary); background: var(--color-bg-hover); }
.task-row-copy-key.success { color: var(--color-success, #16a34a); }
.task-row-copy-key-icon { width: 13px; height: 13px; }

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
  font-size: var(--font-size-subhead);
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
.task-meta-slot-assignee {
  width: 30px;
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
  width: 26px;
  height: 26px;
  border-radius: 999px;
  color: var(--color-text-muted);
  outline: none;
  transition: transform 140ms cubic-bezier(0.23, 1, 0.32, 1),
    background-color var(--transition-fast), box-shadow var(--transition-fast);
}
.task-assignee-trigger:hover {
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-accent) 10%, transparent);
}
.task-assignee-trigger:active {
  transform: scale(0.94);
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
  background: transparent;
  color: var(--color-text-secondary);
}
.task-assignee-trigger.unassigned:hover {
  background: var(--color-bg-hover);
}
.task-assignee-trigger.assigned {
  background: transparent;
}
.task-assignee-icon {
  width: 16px;
  height: 16px;
}

.avatar-18 {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.12);
}
/* 两字缩写保持清晰，同时为彩色圆形保留呼吸感 */
.avatar-18.fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: var(--font-weight-medium);
  line-height: 1;
  letter-spacing: 0.02em;
  /* assigned fallback uses inline style from getAvatarColorByUsername; unassigned not shown here */
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
