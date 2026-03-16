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
import TaskImportModal from '../components/TaskImportModal.vue'
import CustomSelect from '../components/ui/CustomSelect.vue'
import type { CustomSelectOption } from '../components/ui/CustomSelect.vue'
import { userApi } from '../services/api/user'
import type { User } from '../types/domain'
import type { Status } from '../types/domain'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from '../components/icons/PriorityIcons'
import {
  Circle,
  CircleDashed,
  CircleX,
  CheckCircle,
  Copy,
  Eye,
  Filter,
  LayoutList,
  Loader2,
  Plus,
  Download
} from 'lucide-vue-next'
import { buildTaskGroups, getAdjacentTaskIds } from '../utils/taskView'
import type { CompletedVisibility, GroupBy, OrderBy, VisibleProperty } from '../utils/viewPreference'

const filterStatusOptions: CustomSelectOption[] = [
  { value: null, label: 'All Status' },
  { value: 'backlog', label: 'Backlog', icon: CircleDashed },
  { value: 'todo', label: 'Todo', icon: Circle },
  { value: 'in_progress', label: 'In Progress', icon: Loader2 },
  { value: 'in_review', label: 'In Review', icon: Eye },
  { value: 'done', label: 'Done', icon: CheckCircle },
  { value: 'canceled', label: 'Canceled', icon: CircleX },
  { value: 'duplicate', label: 'Duplicate', icon: Copy }
]
const filterPriorityOptions: CustomSelectOption[] = [
  { value: null, label: 'All Priorities' },
  { value: 'urgent', label: 'Urgent', icon: PriorityUrgentIcon },
  { value: 'high', label: 'High', icon: PriorityHighIcon },
  { value: 'medium', label: 'Medium', icon: PriorityMediumIcon },
  { value: 'low', label: 'Low', icon: PriorityLowIcon }
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
const filterPopoverOpen = ref(false)
const displayPopoverOpen = ref(false)
const filterTriggerRef = ref<HTMLElement | null>(null)
const displayTriggerRef = ref<HTMLElement | null>(null)
const filterPopoverRef = ref<HTMLElement | null>(null)
const displayPopoverRef = ref<HTMLElement | null>(null)
const DRAWER_OVERLAY_ID = 'task-editor-drawer'
const COMPOSER_OVERLAY_ID = 'issue-composer'
const IMPORT_OVERLAY_ID = 'task-import'

// UI state for the editor
const isEditorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('edit')
const isImportOpen = ref(false)

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

watch(
  () => viewModeStore.viewConfig.showSubIssues,
  () => {
    if (projectStore.activeProjectId != null) store.fetchTasks()
  }
)

/** P4-6.5: defaultStatus 用于列头 + 新建时指定默认状态；parentNumericId 用于列表行「Add sub-issue」 */
function openCreateEditor(defaultStatus?: import('../types/domain').Status, parentNumericId?: number) {
  issuePanelStore.openComposer({
    status: defaultStatus,
    projectId: projectStore.activeProjectId ?? undefined,
    parentNumericId
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

function openImportModal() {
  isImportOpen.value = true
}

function closeImportModal() {
  isImportOpen.value = false
}

function handleCreated(taskId: string) {
  store.fetchTasks()
  issuePanelStore.openWorkspace(taskId)
  router.push(`/tasks/${taskId}`)
}

function handleImported() {
  store.fetchTasks()
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

watch(
  isImportOpen,
  (open) => {
    if (open) {
      overlayStore.push(IMPORT_OVERLAY_ID, closeImportModal)
    } else {
      overlayStore.remove(IMPORT_OVERLAY_ID)
    }
  },
  { immediate: true }
)

const viewType = computed(() => viewModeStore.viewType)
const taskGroups = computed(() =>
  buildTaskGroups(store.filteredTasks, viewModeStore.viewConfig, users.value)
)
const flatTaskIds = computed(() =>
  taskGroups.value.flatMap((group) => {
    const rows = group.rows ?? group.tasks.map((t) => ({ task: t, depth: 0 }))
    return rows.map((r) => r.task.id)
  })
)
const adjacentTaskIds = computed(() =>
  getAdjacentTaskIds(flatTaskIds.value, store.currentTaskId)
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
  window.addEventListener('click', onClickOutsideFilter, true)
  window.addEventListener('click', onClickOutsideDisplay, true)
})
onUnmounted(() => {
  window.removeEventListener('command-palette:new-task', onNewTaskCommand)
  window.removeEventListener('command-palette:focus-search', onFocusSearchCommand)
  window.removeEventListener('click', onClickOutsideFilter, true)
  window.removeEventListener('click', onClickOutsideDisplay, true)
  overlayStore.remove(DRAWER_OVERLAY_ID)
  overlayStore.remove(IMPORT_OVERLAY_ID)
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

// Scope tab: All issues / Active / Backlog (Linear command bar)
type ScopeTab = 'all' | 'active' | 'backlog'
const scopeTab = computed({
  get(): ScopeTab {
    if (viewModeStore.viewConfig.completedVisibility === 'open_only') return 'active'
    if (store.filterStatus === 'todo') return 'backlog'
    return 'all'
  },
  set(tab: ScopeTab) {
    if (tab === 'active') {
      store.filterStatus = null
      viewModeStore.setCompletedVisibility('open_only')
    } else if (tab === 'backlog') {
      store.filterStatus = 'todo'
      viewModeStore.setCompletedVisibility('all')
    } else {
      store.filterStatus = null
      viewModeStore.setCompletedVisibility('all')
    }
  }
})

function setScopeTab(tab: ScopeTab) {
  scopeTab.value = tab
}

function closeFilterPopover() {
  filterPopoverOpen.value = false
}
function closeDisplayPopover() {
  displayPopoverOpen.value = false
}

function onClickOutsideFilter(event: MouseEvent) {
  const el = filterPopoverRef.value
  const trigger = filterTriggerRef.value
  if (el?.contains(event.target as Node) || trigger?.contains(event.target as Node)) return
  closeFilterPopover()
}
function onClickOutsideDisplay(event: MouseEvent) {
  const el = displayPopoverRef.value
  const trigger = displayTriggerRef.value
  if (el?.contains(event.target as Node) || trigger?.contains(event.target as Node)) return
  closeDisplayPopover()
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
        <button class="btn-create" @click="() => openCreateEditor()">New issue</button>
        <button class="btn-import" @click="openImportModal">
          <Download class="icon-14" />
          <span>Import</span>
        </button>
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
          aria-label="Search issues"
        />
      </div>
    </header>

    <div v-if="!isEditorOpen" class="command-bar">
      <div class="command-bar-left">
        <div class="scope-tabs">
          <button
            type="button"
            class="scope-tab"
            :class="{ active: scopeTab === 'all' }"
            @click="setScopeTab('all')"
          >
            All issues
          </button>
          <button
            type="button"
            class="scope-tab"
            :class="{ active: scopeTab === 'active' }"
            @click="setScopeTab('active')"
          >
            Active
          </button>
          <button
            type="button"
            class="scope-tab"
            :class="{ active: scopeTab === 'backlog' }"
            @click="setScopeTab('backlog')"
          >
            Backlog
          </button>
        </div>
        <button
          type="button"
          class="command-bar-add"
          aria-label="New issue"
          @click="() => openCreateEditor()"
        >
          <Plus class="icon-14" />
        </button>
      </div>
      <div class="command-bar-right">
        <div ref="filterTriggerRef" class="popover-anchor popover-anchor-right">
          <button
            type="button"
            class="command-btn"
            :class="{ active: filterPopoverOpen }"
            aria-label="Filter"
            aria-haspopup="true"
            :aria-expanded="filterPopoverOpen"
            @click="filterPopoverOpen = !filterPopoverOpen"
          >
            <Filter class="icon-14" />
            <span>Filter</span>
          </button>
          <div
            v-show="filterPopoverOpen"
            ref="filterPopoverRef"
            class="popover popover-filter"
            role="dialog"
            aria-label="Filter options"
          >
            <div class="popover-section">
              <label class="popover-label">Status</label>
              <CustomSelect
                v-model="filterStatus"
                :options="filterStatusOptions"
                placeholder="All Status"
                aria-label="Filter by status"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">Priority</label>
              <CustomSelect
                v-model="filterPriority"
                :options="filterPriorityOptions"
                placeholder="All Priorities"
                aria-label="Filter by priority"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">Group by</label>
              <CustomSelect
                v-model="groupBy"
                :options="groupingOptions"
                placeholder="Group"
                aria-label="Group tasks"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">Sort</label>
              <CustomSelect
                v-model="orderBy"
                :options="orderOptions"
                placeholder="Sort"
                aria-label="Order tasks"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">Completed</label>
              <CustomSelect
                v-model="completedVisibility"
                :options="completedOptions"
                placeholder="Completed"
                aria-label="Completed visibility"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section popover-section-row">
              <button type="button" class="command-btn small" @click="toggleOrderDirection">
                {{ viewModeStore.viewConfig.orderDirection === 'asc' ? '↑ Asc' : '↓ Desc' }}
              </button>
              <label class="filter-check">
                <input v-model="showEmptyGroups" type="checkbox" />
                <span>Empty groups</span>
              </label>
            </div>
          </div>
        </div>
        <div ref="displayTriggerRef" class="popover-anchor popover-anchor-right">
          <button
            type="button"
            class="command-btn"
            :class="{ active: displayPopoverOpen }"
            aria-label="Display"
            aria-haspopup="true"
            :aria-expanded="displayPopoverOpen"
            @click="displayPopoverOpen = !displayPopoverOpen"
          >
            <LayoutList class="icon-14" />
            <span>Display</span>
          </button>
          <div
            v-show="displayPopoverOpen"
            ref="displayPopoverRef"
            class="popover popover-display"
            role="dialog"
            aria-label="Display options"
          >
            <div class="popover-section">
              <span class="popover-label">Show on issue</span>
            </div>
            <div class="popover-display-options">
              <label
                v-for="option in visiblePropertyOptions"
                :key="option.value"
                class="popover-display-option"
              >
                <input
                  type="checkbox"
                  :checked="isVisibleProperty(option.value)"
                  @change="toggleVisibleProperty(option.value)"
                />
                <span>{{ option.label }}</span>
              </label>
            </div>
            <div class="popover-section popover-section--sub">
              <span class="popover-label">Sub-issues</span>
              <label class="popover-display-option">
                <input
                  type="checkbox"
                  :checked="viewModeStore.viewConfig.showSubIssues"
                  @change="viewModeStore.setShowSubIssues(!viewModeStore.viewConfig.showSubIssues)"
                />
                <span>Show sub-issues</span>
              </label>
              <label
                v-if="viewModeStore.viewConfig.showSubIssues"
                class="popover-display-option"
              >
                <input
                  type="checkbox"
                  :checked="viewModeStore.viewConfig.nestedSubIssues"
                  @change="viewModeStore.setNestedSubIssues(!viewModeStore.viewConfig.nestedSubIssues)"
                />
                <span>Nested sub-issues</span>
              </label>
            </div>
          </div>
        </div>
      </div>
    </div>

    <main class="board-content" :class="{ 'board-content--list': viewType === 'list' }">
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
        <button class="btn-text" @click="searchQuery = ''; filterStatus = null; filterPriority = null; viewModeStore.setCompletedVisibility('all')">Clear filters</button>
      </div>
      <div v-else-if="isEditorOpen" class="workspace-inline-editor">
        <TaskEditor
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
              @add-sub-issue="(task) => openCreateEditor(undefined, task.numericId)"
            />
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
      :open="isImportOpen"
      :project-id="projectStore.activeProjectId"
      :users="users"
      @close="closeImportModal"
      @imported="handleImported"
    />
  </div>
</template>

<style scoped>
/* P6-2: 顶部工具栏与页面层级 — 命令带化、内容优先 */
.board-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
/* 顶栏：与命令带统一为一条浅带，主操作仅保留 New issue */
.app-header {
  min-height: 40px;
  border-bottom: 1px solid var(--color-border-subtle);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  flex-wrap: wrap;
  background: var(--color-bg-subtle);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
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
.btn-import {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 26px;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-subtle);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  transition: border-color var(--transition-fast), background var(--transition-fast);
}
.btn-import:hover {
  background: var(--color-bg-hover);
  border-color: var(--color-border);
}

.search-input {
  margin-left: auto;
  min-width: 180px;
  max-width: 240px;
  height: 26px;
  box-sizing: border-box;
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 0 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  transition: border-color var(--transition-fast), background var(--transition-fast);
}
.search-input::placeholder {
  color: var(--color-text-secondary);
}
.search-input:focus {
  outline: none;
  border-color: var(--color-border-strong);
  background: var(--color-bg-base);
}

/* 命令带：与顶栏同背景，Tab 弱化 */
.command-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 12px 6px;
  border-bottom: 1px solid var(--color-border-subtle);
  background: var(--color-bg-subtle);
  min-height: 32px;
}
.command-bar-left {
  display: flex;
  align-items: center;
  gap: 6px;
}
.scope-tabs {
  display: flex;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-bg-base);
}
.scope-tab {
  padding: 4px 10px;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.scope-tab:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
}
.scope-tab.active {
  color: var(--color-text-primary);
  background: var(--color-bg-muted);
  font-weight: var(--font-weight-medium);
}
.command-bar-add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.command-bar-add:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.icon-14 {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
.command-bar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}
.popover-anchor {
  position: relative;
}
.popover-anchor-right .popover {
  left: auto;
  right: 0;
}
.command-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  min-height: 26px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.command-btn:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
}
.command-btn.active {
  color: var(--color-accent);
  background: var(--color-accent-muted);
}
.command-btn.small {
  padding: 2px 6px;
  font-size: var(--font-size-xs);
}

.popover {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 4px;
  min-width: 200px;
  padding: 8px 10px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  z-index: 50;
}
.popover-filter {
  min-width: 220px;
}
.popover-display {
  min-width: 180px;
}
.popover-section {
  margin-bottom: 8px;
}
.popover-section:last-child {
  margin-bottom: 0;
}
.popover-section-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.popover-label {
  display: block;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}
.popover-select {
  width: 100%;
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border-subtle);
  color: var(--color-text-primary);
  padding: 4px 8px;
  min-height: 26px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
}
.popover-select:focus {
  outline: none;
  border-color: var(--color-border);
  background: var(--color-bg-base);
}
.popover-display-options {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.popover-display-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: var(--font-size-caption);
  color: var(--color-text-primary);
  cursor: pointer;
}
.popover-display-option input {
  margin: 0;
}
.filter-check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 2px;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  cursor: pointer;
}
.filter-check input {
  margin: 0;
}

.board-content {
  flex: 1;
  padding: 8px 12px 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
}
.board-content--list {
  padding: 0;
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
  overflow: hidden;
  background: var(--color-bg-base);
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
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-bg-base);
}
.toggle-btn {
  padding: var(--control-padding-y) var(--control-padding-x);
  min-height: var(--input-min-height);
  font-size: var(--font-size-caption);
  background: transparent;
  color: var(--color-text-secondary);
  border: none;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.toggle-btn:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.toggle-btn.active {
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.list-wrap {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-base);
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
  border: 1px solid var(--color-border-subtle);
  padding: var(--control-padding-y) var(--control-padding-x);
  border-radius: var(--radius-sm);
}
.btn-retry:hover {
  background: var(--color-bg-hover);
}

@media (max-width: 1100px) {
  .display-bar {
    padding: 6px 16px 8px;
    align-items: flex-start;
    flex-direction: column;
  }

  .board-content {
    padding: 12px 16px;
  }

  .workspace-shell {
    flex-direction: column;
  }

  .workspace-overlay {
    padding: 16px;
  }
}
</style>
