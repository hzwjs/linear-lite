<script lang="ts">
export function resolveWorkspaceSourceLabel(
  taskId: string,
  groups: Array<{
    label: string
    tasks: Array<{ id: string }>
    rows?: Array<{ task: { id: string } }>
  }>
): string | null {
  const group = groups.find((candidate) => {
    if (candidate.tasks.some((task) => task.id === taskId)) return true
    return candidate.rows?.some((row) => row.task.id === taskId) ?? false
  })
  return group?.label ?? null
}
</script>

<script setup lang="ts">
import {
  computed,
  ref,
  watch,
  nextTick,
  onUnmounted,
  defineAsyncComponent,
  type Component,
  type ComponentPublicInstance
} from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import type { TaskStatusUpdate } from '../store/taskStore'
import { X } from 'lucide-vue-next'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useOverlayStore } from '../store/overlayStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import TaskCard from '../components/TaskCard.vue'
import TaskListView from '../components/TaskListView.vue'
import type { User } from '../types/domain'
import type { Status, Task } from '../types/domain'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import { buildTaskGroups, filterVisibleTaskRows, getAdjacentTaskIds } from '../utils/taskView'
import { buildTaskRoute, getRouteTaskId } from '../utils/taskRoute'
import { tryRecoverDynamicImport } from '../utils/dynamicImportRecovery'
import { preloadTaskDetail } from '../utils/taskDetailPreload'

function resilientAsyncComponent<T extends Component>(loader: () => Promise<T>) {
  return defineAsyncComponent({
    loader,
    onError(error, retry, fail, attempts) {
      if (tryRecoverDynamicImport(error)) return
      if (attempts <= 1) {
        retry()
        return
      }
      fail()
    }
  })
}

const TaskEditor = resilientAsyncComponent(() => import('../components/TaskEditor.vue'))
// 新建任务编辑器同样只在用户点击“新建任务”后需要，避免首屏预加载编辑器运行时。
const IssueComposer = resilientAsyncComponent(() => import('../components/IssueComposer.vue'))
const GanttChart = resilientAsyncComponent(() => import('../components/GanttChart.vue'))

const props = defineProps<{
  users: User[]
}>()

const store = useTaskStore()
const projectStore = useProjectStore()
const viewModeStore = useViewModeStore()
const overlayStore = useOverlayStore()
const issuePanelStore = useIssuePanelStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const listSubtaskExpanded = ref<Record<string, boolean>>({})
const openingTaskId = ref<string | null>(null)
const listRef = ref<InstanceType<typeof TaskListView> | null>(null)
interface BulkStatusSummary {
  status: Status
  total: number
  saved: number
  failed: number
}
const statusFeedback = ref<(TaskStatusUpdate & { summary?: BulkStatusSummary }) | null>(null)
const bulkStatusTaskIds = new Set<string>()
let feedbackTimer: ReturnType<typeof setTimeout> | null = null
const taskEditorRef = ref<
  ComponentPublicInstance & { flushPendingSave?: () => Promise<void> }
 | null
>(null)

const DRAWER_OVERLAY_ID = 'task-editor-drawer'
const COMPOSER_OVERLAY_ID = 'issue-composer'

const routeTaskId = computed(() => getRouteTaskId(route))
const isEditorOpen = computed(() => routeTaskId.value != null)
const editorMode = computed<'create' | 'edit'>(() => (routeTaskId.value ? 'edit' : 'create'))

const viewType = computed(() => viewModeStore.viewType)
const taskGroups = computed(() =>
  buildTaskGroups(store.filteredTasks, viewModeStore.effectiveViewConfig, props.users, {
    searchActive: store.searchQuery.trim().length > 0,
    taskFiltersActive:
      store.filterStatusList.length > 0 ||
      store.filterPriorityList.length > 0 ||
      store.filterAssigneeList.length > 0 ||
      store.filterLabelIds.length > 0
  })
)
const localizedTaskGroups = computed(() =>
  taskGroups.value.map((group) => {
    let label = group.label
    switch (viewModeStore.groupBy) {
      case 'status':
        label = getStatusLabel(group.key)
        break
      case 'priority':
        label = getPriorityLabel(group.key)
        break
      case 'assignee':
        if (group.key === 'unassigned') label = t('common.unassigned')
        break
      case 'project':
        if (group.key === 'none') label = t('common.noProject')
        break
      case 'none':
        label = t({
          all: 'boardView.allIssues',
          active: 'boardView.active',
          backlog: 'boardView.backlog'
        }[viewModeStore.viewConfig.scope])
        break
      default:
        break
    }
    return { ...group, label }
  })
)
const flatTaskIds = computed(() =>
  taskGroups.value.flatMap((group) => {
    const rows = group.rows ?? group.tasks.map((t) => ({ task: t, depth: 0 }))
    const visible = filterVisibleTaskRows(rows, listSubtaskExpanded.value)
    return visible.map((r) => r.task.id)
  })
)
const adjacentTaskIds = computed(() =>
  getAdjacentTaskIds(flatTaskIds.value, store.currentTaskId)
)

const feedbackTaskInView = computed(() => {
  const taskId = statusFeedback.value?.task.id
  return taskGroups.value.some((group) => group.rows != null
    ? group.rows.some((row) => row.task.id === taskId)
    : group.tasks.some((task) => task.id === taskId))
})
const statusFeedbackMessage = computed(() => {
  const feedback = statusFeedback.value
  if (!feedback) return ''
  if (feedback.summary) {
    const summary = feedback.summary
    if (feedback.outcome === 'saving') return t('taskList.bulkStatusSaving', { n: summary.total })
    return t(summary.failed > 0 ? 'taskList.bulkStatusPartialFailure' : 'taskList.bulkStatusSaved', {
      saved: summary.saved,
      failed: summary.failed,
      status: getStatusLabel(summary.status)
    })
  }
  if (feedback.outcome === 'saving') return t('taskList.statusSaving', { key: feedback.task.id })
  if (feedback.outcome === 'failed') return t('taskList.statusSaveFailed', { key: feedback.task.id })
  return t(feedbackTaskInView.value ? 'taskList.statusSaved' : 'taskList.statusSavedOutsideView', {
    key: feedback.task.id,
    status: getStatusLabel(feedback.task.status)
  })
})

function dismissStatusFeedback() {
  statusFeedback.value = null
  if (feedbackTimer != null) clearTimeout(feedbackTimer)
  feedbackTimer = null
}

watch(() => projectStore.activeProjectId, dismissStatusFeedback)
watch(() => store.lastStatusUpdate, (update) => {
  if (!update || update.task.projectId !== projectStore.activeProjectId) return
  if (bulkStatusTaskIds.has(update.task.id)) return
  statusFeedback.value = update
})

async function changeBulkStatus(taskIds: string[], status: Status) {
  const projectId = projectStore.activeProjectId
  const tasks = store.tasks.filter(task => taskIds.includes(task.id))
  const first = tasks[0]
  if (!first) return
  tasks.forEach(task => bulkStatusTaskIds.add(task.id))
  const summary: BulkStatusSummary = { status, total: tasks.length, saved: 0, failed: 0 }
  statusFeedback.value = { task: first, outcome: 'saving', summary }
  const results = await Promise.allSettled(tasks.map(task => store.transitionTask(task.id, status)))
  // 保存 lane 在回写确认状态后发布逐项结果；汇总在同轮更新结束后替代它们。
  await nextTick()
  tasks.forEach(task => bulkStatusTaskIds.delete(task.id))
  if (projectStore.activeProjectId !== projectId) return
  const failedTasks: Task[] = []
  const savedTasks: Task[] = []
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') savedTasks.push(result.value)
    else failedTasks.push(tasks[index]!)
  })
  summary.saved = savedTasks.length
  summary.failed = failedTasks.length
  // 失败项留在用户的操作位置，成功项遵守当前排序与筛选。
  const ordered = [...failedTasks, ...savedTasks]
  statusFeedback.value = {
    task: ordered[0]!, outcome: summary.failed > 0 ? 'failed' : 'saved', summary
  }
}
watch([statusFeedback, isEditorOpen, viewType], async ([feedback, editorOpen, layout]) => {
  if (feedbackTimer != null) clearTimeout(feedbackTimer)
  feedbackTimer = null
  if (layout !== 'list') { dismissStatusFeedback(); return }
  if (!feedback || editorOpen || feedback.outcome === 'saving') return
  await nextTick()
  if (feedback !== statusFeedback.value || isEditorOpen.value) return
  if (feedbackTaskInView.value) {
    issuePanelStore.setSelectedTask(feedback.task.id)
    await listRef.value?.locateTask(feedback.task.id)
  } else if (issuePanelStore.selectedTaskId) {
    await listRef.value?.locateTask(issuePanelStore.selectedTaskId)
  }
  // 离开筛选结果的操作反馈保留到用户关闭，避免空列表时丢失去向。
  if (feedback.outcome === 'saved' && feedbackTaskInView.value) {
    feedbackTimer = setTimeout(dismissStatusFeedback, 6000)
  }
}, { flush: 'post' })

const emptyFilterHint = computed(() => {
  const hasSearch = store.searchQuery.trim().length > 0
  const hasIssueFilters =
    store.filterStatusList.length > 0 ||
    store.filterPriorityList.length > 0 ||
    store.filterAssigneeList.length > 0 ||
    store.filterLabelIds.length > 0
  if (hasSearch && hasIssueFilters) return t('boardView.noTasksMatchSearchAndFilters')
  if (hasSearch) return t('boardView.noTasksMatchSearchOnly')
  if (!hasIssueFilters) return t('boardView.noTasksMatchFilters')
  return t('boardView.noTasksMatchIssueFiltersOnly')
})

function openCreateEditor(defaultStatus?: Status, parentNumericId?: number) {
  issuePanelStore.openComposer({
    status: defaultStatus,
    projectId: projectStore.activeProjectId ?? undefined,
    parentNumericId
  })
}

function findWorkspaceSourceLabel(taskId: string) {
  return resolveWorkspaceSourceLabel(taskId, localizedTaskGroups.value)
}

async function openEditEditor(task: { id: string }, sourceLabel?: string | null) {
  if (openingTaskId.value != null) return
  openingTaskId.value = task.id
  issuePanelStore.setSelectedTask(task.id)
  issuePanelStore.workspaceSourceLabel = sourceLabel ?? findWorkspaceSourceLabel(task.id)
  try {
    const detailTask = store.tasks.find((candidate) => candidate.id === task.id)
      ?? await store.fetchTaskByKey(task.id)
    store.currentTaskId = task.id
    router.push(buildTaskRoute(task.id, projectStore.activeProjectId))
    // 先挂载任务主体，详情附属数据在后台预取；编辑器自身也会按任务上下文加载它们。
    void preloadTaskDetail(detailTask, store.fetchSubIssues).catch((error) => {
      console.error(`Failed to preload task detail ${task.id}:`, error)
    })
  } catch (error) {
    console.error(`Failed to open task detail ${task.id}:`, error)
  } finally {
    openingTaskId.value = null
  }
}

async function closeEditor() {
  await taskEditorRef.value?.flushPendingSave?.()
  issuePanelStore.closeWorkspace()
  router.push('/')
}

function closeComposer() {
  issuePanelStore.closeComposer()
}

function handleCreated(taskId: string) {
  store.fetchTasks()
  issuePanelStore.openWorkspace(taskId)
  router.push(buildTaskRoute(taskId, projectStore.activeProjectId))
}

function createStatusDefault(groupKey: string): Status | undefined {
  if (viewModeStore.groupBy !== 'status') return undefined
  if (groupKey === 'todo' || groupKey === 'in_progress' || groupKey === 'done') {
    return groupKey
  }
  return undefined
}

function onWorkspaceKeydown(event: KeyboardEvent) {
  if (viewType.value !== 'list') return
  if (event.defaultPrevented) return
  const target = event.target
  if (target instanceof Element && target.closest('button, input, select, textarea, [role="slider"], [contenteditable="true"]')) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    issuePanelStore.moveSelection(flatTaskIds.value, 1)
    const taskId = issuePanelStore.selectedTaskId
    if (taskId) void listRef.value?.locateTask(taskId)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    issuePanelStore.moveSelection(flatTaskIds.value, -1)
    const taskId = issuePanelStore.selectedTaskId
    if (taskId) void listRef.value?.locateTask(taskId)
    return
  }
  if (event.key === 'Enter' && issuePanelStore.selectedTaskId) {
    event.preventDefault()
    openEditEditor({ id: issuePanelStore.selectedTaskId })
  }
}

async function navigateWorkspace(taskId: string) {
  await taskEditorRef.value?.flushPendingSave?.()
  openEditEditor({ id: taskId })
}

watch(
  isEditorOpen,
  (open) => {
    if (open) {
      overlayStore.push(DRAWER_OVERLAY_ID, closeEditor)
    } else {
      overlayStore.remove(DRAWER_OVERLAY_ID)
    }
  },
  { immediate: true }
)
watch(
  () => issuePanelStore.isComposerOpen,
  (open) => {
    if (open) {
      overlayStore.push(COMPOSER_OVERLAY_ID, closeComposer)
    } else {
      overlayStore.remove(COMPOSER_OVERLAY_ID)
    }
  },
  { immediate: true }
)
watch(
  flatTaskIds,
  (taskIds, previousTaskIds) => {
    const selected = issuePanelStore.selectedTaskId
    const update = store.lastStatusUpdate
    const selectedStatusChanged = update?.task.id === selected &&
      update?.task.projectId === projectStore.activeProjectId
    if (selected && selectedStatusChanged && previousTaskIds && !taskIds.includes(selected)) {
      const index = previousTaskIds.indexOf(selected)
      if (index >= 0) {
        issuePanelStore.setSelectedTask(taskIds[Math.min(index, taskIds.length - 1)] ?? null)
        return
      }
    }
    issuePanelStore.syncSelection(taskIds)
  },
  { immediate: true }
)

function clearFilters() {
  store.searchQuery = ''
  store.clearIssueFilters()
  viewModeStore.setScope('all')
}

onUnmounted(() => {
  dismissStatusFeedback()
  overlayStore.remove(DRAWER_OVERLAY_ID)
  overlayStore.remove(COMPOSER_OVERLAY_ID)
})
</script>

<template>
  <main
    class="board-content"
    :class="{ 'board-content--list': viewType === 'list', 'board-content--inline-editor': isEditorOpen }"
  >
    <div v-if="store.error" class="error-state">
      <p>{{ store.error }}</p>
      <button class="btn-retry" @click="store.fetchTasks()">{{ t('common.retry') }}</button>
    </div>

    <!-- 详情路由优先于 loading/empty：刷新或切项目时任务列表短暂清空，不应把已打开详情盖掉 -->
    <div v-else-if="isEditorOpen" class="workspace-inline-editor">
      <div v-if="editorMode === 'edit' && !store.currentTask" class="loading-state">
        <p>{{ t('boardView.loadingTasks') }}</p>
      </div>
      <TaskEditor
        v-else
        ref="taskEditorRef"
        variant="inline"
        :mode="editorMode"
        :task="store.currentTask"
        :previous-task-id="adjacentTaskIds.previousTaskId"
        :next-task-id="adjacentTaskIds.nextTaskId"
        :position="adjacentTaskIds.position"
        :total="adjacentTaskIds.total"
        @close="closeEditor"
        @navigate="navigateWorkspace"
      />
    </div>

    <div v-else-if="store.isLoading && store.tasks.length === 0" class="loading-state">
      <p>{{ t('boardView.loadingTasks') }}</p>
    </div>

    <div v-else-if="store.isEmpty" class="empty-state">
      <p>{{ t('boardView.noTasks') }}</p>
      <button class="btn-create" @click="() => openCreateEditor()">{{ t('boardView.createFirstTask') }}</button>
    </div>
    <div v-else-if="store.isFilterEmpty || (viewType === 'list' && !taskGroups.some(group => group.tasks.length > 0))" class="empty-state">
      <p>{{ emptyFilterHint }}</p>
      <button type="button" class="btn-text" @click="clearFilters">
        {{ t('boardView.clearFilters') }}
      </button>
    </div>
    <div v-else class="workspace-shell" :class="{ 'workspace-shell--list': viewType === 'list' }">
      <section class="workspace-primary" tabindex="0" @keydown="onWorkspaceKeydown">
        <div v-if="viewType === 'board'" class="board-columns">
          <div
            v-for="(group, idx) in localizedTaskGroups"
            :key="group.key"
            class="column"
            :class="{ 'column-first': idx === 0 }"
          >
            <div class="column-header">
              <h3>{{ group.label }} <span>{{ group.tasks.length }}</span></h3>
              <button
                type="button"
                class="column-add-btn"
                :title="t('boardView.addIssue')"
                :aria-label="t('boardView.addIssueToColumn')"
                @click.stop="() => openCreateEditor(createStatusDefault(group.key))"
              >
                +
              </button>
            </div>
            <div class="column-list">
              <TaskCard
                v-for="task in group.tasks"
                :key="task.id"
                :task="task"
                :visible-properties="viewModeStore.visibleProperties"
                :selected="issuePanelStore.selectedTaskId === task.id"
                :opening="openingTaskId === task.id"
                @click="openEditEditor"
                @transition="(id, status) => store.transitionTask(id, status)"
              />
            </div>
          </div>
        </div>
        <div v-else-if="viewType === 'list'" class="list-wrap">
          <TaskListView
            ref="listRef"
            v-model:subtask-expanded="listSubtaskExpanded"
            :project-id="projectStore.activeProjectId"
            :groups="localizedTaskGroups"
            :grouped="viewModeStore.groupBy !== 'none'"
            :expand-groups-on-entry="viewModeStore.viewConfig.scope === 'active'"
            :visible-properties="viewModeStore.visibleProperties"
            :selected-task-id="issuePanelStore.selectedTaskId"
            :opening-task-id="openingTaskId"
            @row-click="openEditEditor"
            @create-in-status="openCreateEditor"
            @add-sub-issue="(task) => openCreateEditor(undefined, task.numericId)"
            @bulk-status-change="changeBulkStatus"
          />
        </div>
        <div v-else-if="viewType === 'gantt'" class="gantt-wrap">
          <GanttChart />
        </div>
      </section>
    </div>
    <div
      v-if="statusFeedback && !isEditorOpen && viewType === 'list'"
      class="status-feedback"
      :class="{ 'status-feedback--error': statusFeedback.outcome === 'failed' }"
      :role="statusFeedback.outcome === 'failed' ? 'alert' : 'status'"
      :aria-busy="statusFeedback.outcome === 'saving'"
    >
      <span>{{ statusFeedbackMessage }}</span>
      <button type="button" @click="openEditEditor(statusFeedback.task)">{{ t('taskList.openTask') }}</button>
      <button type="button" :aria-label="t('common.close')" @click="dismissStatusFeedback"><X :size="14" /></button>
    </div>
  </main>

  <IssueComposer
    :open="issuePanelStore.isComposerOpen"
    :default-status="issuePanelStore.composerDefaults.status"
    :parent-numeric-id="issuePanelStore.composerDefaults.parentNumericId"
    @close="closeComposer"
    @created="handleCreated"
  />
</template>

<style scoped>
.status-feedback {
  --feedback-inset: 12px;
  position: absolute;
  inset-inline: var(--feedback-inset);
  bottom: var(--feedback-inset);
  z-index: 5;
  display: flex;
  align-items: center;
  gap: var(--feedback-inset);
  width: fit-content;
  max-width: calc(100% - var(--feedback-inset) * 2);
  margin-inline: auto;
  padding: 10px var(--feedback-inset);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  box-shadow: var(--shadow-popover);
  font-size: var(--font-size-caption);
}
.status-feedback span { min-width: 0; overflow-wrap: anywhere; }
.status-feedback--error { color: var(--color-danger); }
.status-feedback button {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  padding: var(--control-padding-y);
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-primary);
  font: inherit;
  cursor: pointer;
}
.status-feedback button:hover { background: var(--color-bg-hover); }
.status-feedback button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.board-content {
  flex: 1;
  min-height: 0;
  padding: 8px 12px 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
}
.board-content--list {
  padding: 0;
}
/* 内联任务编辑器：避免多层 overflow:hidden 裁切 BlockNote 块侧栏（+ / 拖拽） */
.board-content--inline-editor {
  overflow: visible;
}
.workspace-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  background: var(--color-bg-base);
}
.workspace-shell--list {
  border: none;
  border-radius: 0;
}
.workspace-inline-editor {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow-x: hidden;
  overflow-y: auto;
  background: var(--color-bg-base);
}
.workspace-primary {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.list-wrap {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}
.gantt-wrap {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
}
.board-columns {
  display: flex;
  gap: 0;
  height: 100%;
  background: transparent;
}
.column {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 0 12px;
  border-left: 1px solid var(--color-border-subtle);
  transition: background-color var(--transition-fast);
}
.column-first {
  border-left: none;
}
.column:hover {
  background: var(--color-bg-hover);
}
.column-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border-subtle);
  margin-bottom: 8px;
}
.column-header h3 {
  margin: 0;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.column-add-btn {
  padding: 2px 6px;
  font-size: var(--font-size-subhead);
  line-height: 1;
  color: var(--color-text-muted);
  background: transparent;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.column-add-btn:hover {
  color: var(--color-accent);
  background: var(--color-bg-hover);
}
.column-header span {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  background: var(--color-bg-muted);
  padding: 2px 5px;
  border-radius: var(--radius-xs);
}
.column-list {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  transition: scrollbar-color var(--transition-fast);
}
.column:hover .column-list {
  scrollbar-color: var(--color-border-subtle) transparent;
}
.column-list::-webkit-scrollbar {
  width: 6px;
}
.column-list::-webkit-scrollbar-track {
  background: transparent;
}
.column-list::-webkit-scrollbar-thumb {
  border-radius: var(--radius-xs);
  background: transparent;
  transition: background var(--transition-fast);
}
.column:hover .column-list::-webkit-scrollbar-thumb {
  background: var(--color-border-subtle);
}
.column:hover .column-list::-webkit-scrollbar-thumb:hover {
  background: var(--color-text-muted);
}
.empty-state,
.error-state,
.loading-state {
  flex: 1 1 auto;
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  gap: 16px;
}
.btn-text {
  color: var(--color-accent);
}
.btn-text:hover {
  text-decoration: underline;
}
.btn-retry {
  border: 1px solid var(--color-border-subtle);
  padding: var(--control-padding-y) var(--control-padding-x);
  border-radius: var(--radius-sm);
}
.btn-retry:hover {
  background: var(--color-bg-hover);
}
.btn-create {
  background: var(--color-accent);
  color: white;
  padding: 4px 10px;
  min-height: 26px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-semibold);
  transition: background var(--transition-fast);
}
.btn-create:hover {
  background: var(--color-accent-hover);
}
@media (max-width: 1100px) {
  .board-content {
    padding: 12px 16px;
  }
  .workspace-shell {
    flex-direction: column;
  }
}
</style>
