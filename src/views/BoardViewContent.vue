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
  onUnmounted,
  defineAsyncComponent,
  type ComponentPublicInstance
} from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useOverlayStore } from '../store/overlayStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import TaskCard from '../components/TaskCard.vue'
import TaskListView from '../components/TaskListView.vue'
import IssueComposer from '../components/IssueComposer.vue'
import type { User } from '../types/domain'
import type { Status } from '../types/domain'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import { buildTaskGroups, filterVisibleTaskRows, getAdjacentTaskIds } from '../utils/taskView'

const TaskEditor = defineAsyncComponent(() => import('../components/TaskEditor.vue'))
const GanttChart = defineAsyncComponent(() => import('../components/GanttChart.vue'))
const TaskImportModal = defineAsyncComponent(() => import('../components/TaskImportModal.vue'))

const props = defineProps<{
  users: User[]
  importOpen: boolean
}>()
const emit = defineEmits<{
  closeImport: []
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
const taskEditorRef = ref<
  ComponentPublicInstance & { flushPendingSave?: () => Promise<void> }
 | null
>(null)

const DRAWER_OVERLAY_ID = 'task-editor-drawer'
const COMPOSER_OVERLAY_ID = 'issue-composer'
const IMPORT_OVERLAY_ID = 'task-import'

const isEditorOpen = computed(() => !!route.params.taskId)
const editorMode = computed<'create' | 'edit'>(() => (route.params.taskId ? 'edit' : 'create'))

const viewType = computed(() => viewModeStore.viewType)
const taskGroups = computed(() =>
  buildTaskGroups(store.filteredTasks, viewModeStore.viewConfig, props.users, {
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
    switch (viewModeStore.viewConfig.groupBy) {
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
        label = t('boardView.allIssues')
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

const emptyFilterHint = computed(() => {
  const hasSearch = store.searchQuery.trim().length > 0
  const hasIssueFilters =
    store.filterStatusList.length > 0 ||
    store.filterPriorityList.length > 0 ||
    store.filterAssigneeList.length > 0 ||
    store.filterLabelIds.length > 0
  if (hasSearch && hasIssueFilters) return t('boardView.noTasksMatchSearchAndFilters')
  if (hasSearch) return t('boardView.noTasksMatchSearchOnly')
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

function openEditEditor(task: { id: string }, sourceLabel?: string | null) {
  issuePanelStore.openWorkspace(task.id, sourceLabel ?? findWorkspaceSourceLabel(task.id))
  router.push(`/tasks/${task.id}`)
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
  router.push(`/tasks/${taskId}`)
}

function handleImported() {
  store.fetchTasks()
}

function createStatusDefault(groupKey: string): Status | undefined {
  if (viewModeStore.viewConfig.groupBy !== 'status') return undefined
  if (groupKey === 'todo' || groupKey === 'in_progress' || groupKey === 'done') {
    return groupKey
  }
  return undefined
}

function onWorkspaceKeydown(event: KeyboardEvent) {
  if (viewType.value !== 'list') return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    issuePanelStore.moveSelection(flatTaskIds.value, 1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    issuePanelStore.moveSelection(flatTaskIds.value, -1)
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
  () => props.importOpen,
  (open) => {
    if (open) {
      overlayStore.push(IMPORT_OVERLAY_ID, () => emit('closeImport'))
    } else {
      overlayStore.remove(IMPORT_OVERLAY_ID)
    }
  },
  { immediate: true }
)
watch(
  flatTaskIds,
  (taskIds) => {
    issuePanelStore.syncSelection(taskIds)
  },
  { immediate: true }
)

function clearFilters() {
  store.searchQuery = ''
  store.clearIssueFilters()
  viewModeStore.setCompletedVisibility('all')
}

onUnmounted(() => {
  overlayStore.remove(DRAWER_OVERLAY_ID)
  overlayStore.remove(COMPOSER_OVERLAY_ID)
  overlayStore.remove(IMPORT_OVERLAY_ID)
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

    <div v-else-if="store.isLoading && store.tasks.length === 0" class="loading-state">
      <p>{{ t('boardView.loadingTasks') }}</p>
    </div>

    <div v-else-if="store.isEmpty" class="empty-state">
      <p>{{ t('boardView.noTasks') }}</p>
      <button class="btn-create" @click="() => openCreateEditor()">{{ t('boardView.createFirstTask') }}</button>
    </div>

    <div v-else-if="store.isFilterEmpty" class="empty-state">
      <p>{{ emptyFilterHint }}</p>
      <button type="button" class="btn-text" @click="clearFilters">
        {{ t('boardView.clearFilters') }}
      </button>
    </div>
    <div v-else-if="isEditorOpen" class="workspace-inline-editor">
      <TaskEditor
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
                :users="users"
                :visible-properties="viewModeStore.visibleProperties"
                :selected="issuePanelStore.selectedTaskId === task.id"
                @click="openEditEditor"
                @transition="(id, status) => store.transitionTask(id, status)"
              />
            </div>
          </div>
        </div>
        <div v-else-if="viewType === 'list'" class="list-wrap">
          <TaskListView
            v-model:subtask-expanded="listSubtaskExpanded"
            :groups="localizedTaskGroups"
            :users="users"
            :visible-properties="viewModeStore.visibleProperties"
            :selected-task-id="issuePanelStore.selectedTaskId"
            @row-click="openEditEditor"
            @create-in-status="openCreateEditor"
            @add-sub-issue="(task) => openCreateEditor(undefined, task.numericId)"
          />
        </div>
        <div v-else-if="viewType === 'gantt'" class="gantt-wrap">
          <GanttChart />
        </div>
      </section>
    </div>
  </main>

  <IssueComposer
    :open="issuePanelStore.isComposerOpen"
    :default-status="issuePanelStore.composerDefaults.status"
    :parent-numeric-id="issuePanelStore.composerDefaults.parentNumericId"
    @close="closeComposer"
    @created="handleCreated"
  />
  <TaskImportModal
    :open="importOpen"
    :project-id="projectStore.activeProjectId"
    :users="users"
    @close="emit('closeImport')"
    @imported="handleImported"
  />
</template>

<style scoped>
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
  overflow: visible;
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
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
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
