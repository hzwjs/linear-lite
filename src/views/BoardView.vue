<script setup lang="ts">
import { onMounted, onUnmounted, computed, watch, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useOverlayStore } from '../store/overlayStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import TaskCard from '../components/TaskCard.vue'
import TaskEditor from '../components/TaskEditor.vue'
import TaskListView from '../components/TaskListView.vue'
import IssueComposer from '../components/IssueComposer.vue'
import CustomSelect from '../components/ui/CustomSelect.vue'
import type { CustomSelectOption } from '../components/ui/CustomSelect.vue'
import { userApi } from '../services/api/user'
import type { User } from '../types/domain'
import type { Status } from '../types/domain'
import { Circle, Loader2, CheckCircle, ArrowDown, Minus, ArrowUp, Flame } from 'lucide-vue-next'
import { buildTaskGroups, getAdjacentTaskIds } from '../utils/taskView'
import { resolveWorkspacePresentation } from '../utils/workspacePresentation'
import type { CompletedVisibility, GroupBy, OrderBy, VisibleProperty } from '../utils/viewPreference'

const filterStatusOptions: CustomSelectOption[] = [
  { value: null, label: 'All Status' },
  { value: 'todo', label: 'Todo', icon: Circle },
  { value: 'in_progress', label: 'In Progress', icon: Loader2 },
  { value: 'done', label: 'Done', icon: CheckCircle }
]
const filterPriorityOptions: CustomSelectOption[] = [
  { value: null, label: 'All Priorities' },
  { value: 'urgent', label: 'Urgent', icon: Flame },
  { value: 'high', label: 'High', icon: ArrowUp },
  { value: 'medium', label: 'Medium', icon: Minus },
  { value: 'low', label: 'Low', icon: ArrowDown }
]
const groupingOptions: CustomSelectOption[] = [
  { value: 'status', label: 'Status' },
  { value: 'priority', label: 'Priority' },
  { value: 'assignee', label: 'Assignee' },
  { value: 'project', label: 'Project' },
  { value: 'none', label: 'None' }
]
const orderOptions: CustomSelectOption[] = [
  { value: 'updatedAt', label: 'Updated' },
  { value: 'createdAt', label: 'Created' },
  { value: 'priority', label: 'Priority' },
  { value: 'dueDate', label: 'Due date' },
  { value: 'title', label: 'Title' }
]
const completedOptions: CustomSelectOption[] = [
  { value: 'all', label: 'All' },
  { value: 'open_only', label: 'Open only' }
]
const visiblePropertyOptions: Array<{ value: VisibleProperty; label: string }> = [
  { value: 'id', label: 'ID' },
  { value: 'status', label: 'Status' },
  { value: 'priority', label: 'Priority' },
  { value: 'assignee', label: 'Assignee' },
  { value: 'project', label: 'Project' },
  { value: 'dueDate', label: 'Due date' },
  { value: 'updatedAt', label: 'Updated' }
]

const store = useTaskStore()
const projectStore = useProjectStore()
const viewModeStore = useViewModeStore()
const overlayStore = useOverlayStore()
const issuePanelStore = useIssuePanelStore()
const route = useRoute()
const router = useRouter()
const users = ref<User[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)
const DRAWER_OVERLAY_ID = 'task-editor-drawer'
const COMPOSER_OVERLAY_ID = 'issue-composer'

// UI state for the editor
const isEditorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('edit')

// Sync store properties for v-model
const searchQuery = computed({
  get: () => store.searchQuery,
  set: (val) => { store.searchQuery = val }
})
const filterStatus = computed({
  get: () => store.filterStatus,
  set: (val) => { store.filterStatus = val }
})
const filterPriority = computed({
  get: () => store.filterPriority,
  set: (val) => { store.filterPriority = val }
})
const groupBy = computed({
  get: () => viewModeStore.viewConfig.groupBy,
  set: (value) => viewModeStore.setGroupBy(value as GroupBy)
})
const orderBy = computed({
  get: () => viewModeStore.viewConfig.orderBy,
  set: (value) => viewModeStore.setOrderBy(value as OrderBy)
})
const completedVisibility = computed({
  get: () => viewModeStore.viewConfig.completedVisibility,
  set: (value) => viewModeStore.setCompletedVisibility(value as CompletedVisibility)
})
const showEmptyGroups = computed({
  get: () => viewModeStore.viewConfig.showEmptyGroups,
  set: (value) => viewModeStore.setShowEmptyGroups(value)
})

// Deep link handling
watch(() => route.params.taskId, (newId) => {
  if (newId) {
    store.currentTaskId = newId as string
    issuePanelStore.openWorkspace(newId as string)
    editorMode.value = 'edit'
    isEditorOpen.value = true
  } else {
    isEditorOpen.value = false
    store.currentTaskId = null
    issuePanelStore.closeWorkspace()
  }
}, { immediate: true })

onMounted(async () => {
  store.fetchTasks()
  try {
    users.value = await userApi.list()
  } catch (e) {
    console.error('Failed to load users:', e)
  }
})

watch(
  () => projectStore.activeProjectId,
  (id) => {
    if (id != null) store.fetchTasks()
  }
)

/** P4-6.5: defaultStatus 用于列头 + 新建时指定默认状态 */
function openCreateEditor(defaultStatus?: import('../types/domain').Status) {
  issuePanelStore.openComposer({
    status: defaultStatus,
    projectId: projectStore.activeProjectId ?? undefined
  })
}

function openEditEditor(task: { id: string }) {
  router.push(`/tasks/${task.id}`)
}

const closeEditor = () => {
  router.push('/')
  isEditorOpen.value = false
}

function closeComposer() {
  issuePanelStore.closeComposer()
}

function handleCreated(taskId: string) {
  issuePanelStore.openWorkspace(taskId)
  router.push(`/tasks/${taskId}`)
}

// P4-7.4: Drawer 注册到浮层栈，Esc 可关闭
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

const viewType = computed(() => viewModeStore.viewType)
const taskGroups = computed(() =>
  buildTaskGroups(store.filteredTasks, viewModeStore.viewConfig, users.value)
)
const flatTaskIds = computed(() =>
  taskGroups.value.flatMap((group) => group.tasks.map((task) => task.id))
)
const adjacentTaskIds = computed(() =>
  getAdjacentTaskIds(flatTaskIds.value, store.currentTaskId)
)
const workspacePresentation = computed(() =>
  resolveWorkspacePresentation(store.currentTaskId)
)
function setView(v: 'board' | 'list') {
  viewModeStore.setView(v)
}

// P4-7.2: Command Palette / 快捷键 C 新建任务、聚焦搜索
function onNewTaskCommand() {
  openCreateEditor()
}
function onFocusSearchCommand() {
  searchInputRef.value?.focus()
}

onMounted(() => {
  window.addEventListener('command-palette:new-task', onNewTaskCommand)
  window.addEventListener('command-palette:focus-search', onFocusSearchCommand)
})
onUnmounted(() => {
  window.removeEventListener('command-palette:new-task', onNewTaskCommand)
  window.removeEventListener('command-palette:focus-search', onFocusSearchCommand)
  overlayStore.remove(DRAWER_OVERLAY_ID)
})

function createStatusDefault(groupKey: string): Status | undefined {
  if (viewModeStore.viewConfig.groupBy !== 'status') return undefined
  if (groupKey === 'todo' || groupKey === 'in_progress' || groupKey === 'done') {
    return groupKey
  }
  return undefined
}

function toggleVisibleProperty(property: VisibleProperty) {
  viewModeStore.toggleVisibleProperty(property)
}

function isVisibleProperty(property: VisibleProperty) {
  return viewModeStore.visibleProperties.includes(property)
}

function toggleOrderDirection() {
  viewModeStore.setOrderDirection(
    viewModeStore.viewConfig.orderDirection === 'asc' ? 'desc' : 'asc'
  )
}

watch(
  flatTaskIds,
  (taskIds) => {
    issuePanelStore.syncSelection(taskIds)
  },
  { immediate: true }
)

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

function navigateWorkspace(taskId: string) {
  openEditEditor({ id: taskId })
}
</script>

<template>
  <div class="board-view">
    <header class="app-header">
      <div class="header-left">
        <div class="header-title-block">
          <h1>Linear Lite</h1>
          <p class="header-subtitle">Issues</p>
        </div>
        <button class="btn-create" @click="() => openCreateEditor()">New issue</button>
      </div>
      
      <div class="header-controls">
        <div class="view-toggle">
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewType === 'board' }"
            @click="setView('board')"
          >
            Board
          </button>
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewType === 'list' }"
            @click="setView('list')"
          >
            List
          </button>
        </div>
        <input
          ref="searchInputRef"
          v-model="searchQuery"
          placeholder="Search issues..."
          class="search-input"
        />
        <CustomSelect
          v-model="filterStatus"
          :options="filterStatusOptions"
          placeholder="All Status"
          aria-label="Filter by status"
          trigger-class="filter-select"
        />
        <CustomSelect
          v-model="filterPriority"
          :options="filterPriorityOptions"
          placeholder="All Priorities"
          aria-label="Filter by priority"
          trigger-class="filter-select"
        />
        <CustomSelect
          v-model="groupBy"
          :options="groupingOptions"
          placeholder="Group"
          aria-label="Group tasks"
          trigger-class="filter-select"
        />
        <CustomSelect
          v-model="orderBy"
          :options="orderOptions"
          placeholder="Sort"
          aria-label="Order tasks"
          trigger-class="filter-select"
        />
        <CustomSelect
          v-model="completedVisibility"
          :options="completedOptions"
          placeholder="Completed"
          aria-label="Completed visibility"
          trigger-class="filter-select"
        />
        <button type="button" class="filter-btn" @click="toggleOrderDirection">
          {{ viewModeStore.viewConfig.orderDirection === 'asc' ? '↑' : '↓' }}
        </button>
        <label class="filter-check">
          <input v-model="showEmptyGroups" type="checkbox" />
          <span>Empty</span>
        </label>
      </div>
    </header>

    <div class="display-bar">
      <span class="display-label">Display</span>
      <div class="display-chips">
        <button
          v-for="option in visiblePropertyOptions"
          :key="option.value"
          type="button"
          class="display-chip"
          :class="{ active: isVisibleProperty(option.value) }"
          @click="toggleVisibleProperty(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <main class="board-content">
      <div v-if="store.error" class="error-state">
        <p>{{ store.error }}</p>
        <button class="btn-retry" @click="store.fetchTasks()">Retry</button>
      </div>
      
      <div v-else-if="store.isLoading && store.tasks.length === 0" class="loading-state">
        <p>Loading tasks...</p>
      </div>
      
      <div v-else-if="store.isEmpty" class="empty-state">
        <p>You don't have any tasks yet.</p>
        <button class="btn-create" @click="() => openCreateEditor()">Create your first task</button>
      </div>

      <div v-else-if="store.isFilterEmpty" class="empty-state">
        <p>No tasks match your filters.</p>
        <button class="btn-text" @click="searchQuery = ''; filterStatus = null; filterPriority = null">Clear filters</button>
      </div>
      <div v-else class="workspace-shell">
        <section class="workspace-primary" tabindex="0" @keydown="onWorkspaceKeydown">
          <div v-if="viewType === 'board'" class="board-columns">
            <div
              v-for="(group, idx) in taskGroups"
              :key="group.key"
              class="column"
              :class="{ 'column-first': idx === 0 }"
            >
              <div class="column-header">
                <h3>{{ group.label }} <span>{{ group.tasks.length }}</span></h3>
                <button
                  type="button"
                  class="column-add-btn"
                  title="Add issue"
                  aria-label="Add issue to this column"
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
          <div v-else class="list-wrap">
            <TaskListView
              :groups="taskGroups"
              :users="users"
              :visible-properties="viewModeStore.visibleProperties"
              :selected-task-id="issuePanelStore.selectedTaskId"
              @row-click="openEditEditor"
              @create-in-status="openCreateEditor"
            />
          </div>
        </section>
      </div>
    </main>

    <div
      v-if="workspacePresentation.workspaceMode === 'overlay' && isEditorOpen"
      class="workspace-overlay"
      @click.self="closeEditor"
    >
      <TaskEditor
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

    <IssueComposer
      :open="issuePanelStore.isComposerOpen"
      :default-status="issuePanelStore.composerDefaults.status"
      @close="closeComposer"
      @created="handleCreated"
    />
  </div>
</template>

<style scoped>
.board-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.app-header {
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
  padding: 12px 18px 10px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}
.header-title-block {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.header-left h1 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
  letter-spacing: -0.01em;
}
.header-subtitle {
  margin: 0;
  font-size: 11px;
  color: var(--color-text-secondary);
}
.btn-create {
  background: var(--color-accent);
  color: white;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  transition: background var(--transition-fast);
}
.btn-create:hover { background: var(--color-accent-hover); }

.header-controls {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.search-input, .filter-select {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 6px 9px;
  border-radius: 8px;
  font-size: 11px;
}
.filter-btn {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  padding: 6px 8px;
  border-radius: 8px;
  font-size: 11px;
  min-width: 30px;
}
.filter-btn:hover {
  color: var(--color-text-primary);
}
.filter-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 2px;
  color: var(--color-text-secondary);
  font-size: 11px;
}

.board-content {
  flex: 1;
  padding: 14px 18px 18px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
}
.display-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 18px 0;
}
.display-label {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}
.display-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.display-chip {
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 10px;
  line-height: 1;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}
.display-chip:hover {
  color: var(--color-text-primary);
  background: var(--color-hover);
}
.display-chip.active {
  background: rgba(94, 106, 210, 0.1);
  border-color: rgba(94, 106, 210, 0.35);
  color: var(--color-status-done);
}
.workspace-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(247, 248, 249, 0.75));
}
.workspace-primary {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.workspace-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: stretch;
  justify-content: center;
  padding: 28px 24px 24px 280px;
}

.view-toggle {
  display: flex;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
}
.toggle-btn {
  padding: 6px 10px;
  font-size: 11px;
  background: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  border: none;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.toggle-btn:hover {
  color: var(--color-text-primary);
}
.toggle-btn.active {
  background: var(--color-accent);
  color: white;
}
.list-wrap {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-main);
}
/* P4-6.4: 线分栏，主区统一画布；列间 1px 竖线，去掉独立圆角卡片 */
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
  padding: 0 14px;
  border-left: 1px solid var(--color-border);
  transition: background-color var(--transition-fast);
}
.column-first {
  border-left: none;
}
.column:hover {
  background: var(--color-hover);
}
.column-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 10px;
}
.column-header h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.column-add-btn {
  padding: 3px 8px;
  font-size: 14px;
  line-height: 1;
  color: var(--color-text-secondary);
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.column-add-btn:hover {
  color: var(--color-accent);
  background: var(--color-hover);
}
.column-header span {
  font-size: 11px;
  color: var(--color-text-secondary);
  background: var(--color-bg-elevated);
  padding: 2px 6px;
  border-radius: 12px;
}
/* P4-6.2: 列区域 150ms 过渡（卡片 hover 在 TaskCard 中已用 --transition-fast） */
.column-list {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  transition: scrollbar-color var(--transition-fast);
}
/* P4-6.3: 列区滚动条收敛，hover 列时显现 */
.column:hover .column-list {
  scrollbar-color: var(--color-border) transparent;
}
.column-list::-webkit-scrollbar {
  width: 6px;
}
.column-list::-webkit-scrollbar-track {
  background: transparent;
}
.column-list::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: transparent;
  transition: background var(--transition-fast);
}
.column:hover .column-list::-webkit-scrollbar-thumb {
  background: var(--color-border);
}
.column:hover .column-list::-webkit-scrollbar-thumb:hover {
  background: var(--color-text-secondary);
}

.empty-state, .error-state, .loading-state {
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
.btn-text:hover { text-decoration: underline; }
.btn-retry {
  border: 1px solid var(--color-border);
  padding: 6px 12px;
  border-radius: var(--border-radius-sm);
}
.btn-retry:hover { background: var(--color-bg-hover); }

@media (max-width: 1100px) {
  .display-bar {
    padding: 10px 16px 0;
    align-items: flex-start;
    flex-direction: column;
  }

  .board-content {
    padding: 16px;
  }

  .workspace-shell {
    flex-direction: column;
  }

  .workspace-overlay {
    padding: 16px;
  }
}
</style>
