<script setup lang="ts">
import { onMounted, onUnmounted, computed, watch, ref, defineAsyncComponent, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import AddIssueFilterMenu from '../components/issue-filters/AddIssueFilterMenu.vue'
import CustomSelect from '../components/ui/CustomSelect.vue'
import type { CustomSelectOption } from '../components/ui/CustomSelect.vue'
import { projectApi } from '../services/api/project'
import type { User } from '../types/domain'
import {
  ArrowDownWideNarrow,
  ArrowUpWideNarrow,
  BarChart3,
  Circle,
  Filter,
  X,
  LayoutList,
  Plus,
  Download,
  Tag,
  User as UserIcon
} from 'lucide-vue-next'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import type { CompletedVisibility, GroupBy, OrderBy, ViewType, VisibleProperty } from '../utils/viewPreference'
import {
  readProjectBoard,
  writeProjectBoard,
  type ProjectBoardSnapshot
} from '../utils/projectBoardPreferences'

const BoardViewContent = defineAsyncComponent(() => import('./BoardViewContent.vue'))

const store = useTaskStore()
const projectStore = useProjectStore()
const viewModeStore = useViewModeStore()
const issuePanelStore = useIssuePanelStore()
const route = useRoute()
const { t } = useI18n()
const users = ref<User[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)
const filterPopoverOpen = ref(false)
const displayPopoverOpen = ref(false)
const filterTriggerRef = ref<HTMLElement | null>(null)
const displayTriggerRef = ref<HTMLElement | null>(null)
const filterPopoverRef = ref<HTMLElement | null>(null)
const addIssueFilterMenuRef = ref<InstanceType<typeof AddIssueFilterMenu> | null>(null)
const displayPopoverRef = ref<HTMLElement | null>(null)
const isImportOpen = ref(false)

// 命令栏在任务详情（右侧抽屉）打开时隐藏
const isEditorOpen = computed(() => !!route.params.taskId)

// Sync store properties for v-model
const searchQuery = computed({
  get: () => store.searchQuery,
  set: (val) => { store.searchQuery = val }
})

const applyingBoardPrefs = ref(false)
let boardPrefsSaveTimer: ReturnType<typeof setTimeout> | null = null

function buildBoardSnapshot(): ProjectBoardSnapshot {
  const vc = viewModeStore.viewConfig
  const normMapObj: Record<number, string> = {}
  store.filterAssigneeUsernameNormMap.forEach((v, k) => {
    normMapObj[k] = v
  })
  return {
    filters: {
      searchQuery: store.searchQuery,
      filterStatusList: [...store.filterStatusList],
      filterPriorityList: [...store.filterPriorityList],
      filterAssigneeList: [...store.filterAssigneeList],
      filterAssigneeUsernameNormMap: normMapObj,
      filterLabelIds: [...store.filterLabelIds]
    },
    view: {
      ...vc,
      visibleProperties: [...vc.visibleProperties]
    }
  }
}

function applyBoardSnapshot(snap: ProjectBoardSnapshot) {
  applyingBoardPrefs.value = true
  try {
    store.searchQuery = snap.filters.searchQuery
    store.filterStatusList = [...snap.filters.filterStatusList]
    store.filterPriorityList = [...snap.filters.filterPriorityList]
    store.filterAssigneeList = [...snap.filters.filterAssigneeList]
    store.filterAssigneeUsernameNormMap = new Map(
      Object.entries(snap.filters.filterAssigneeUsernameNormMap).map(([k, v]) => [Number(k), v])
    )
    store.filterLabelIds = [...snap.filters.filterLabelIds]
    viewModeStore.hydrateViewConfig(snap.view)
  } finally {
    applyingBoardPrefs.value = false
  }
}

function resetBoardFiltersForProject() {
  applyingBoardPrefs.value = true
  try {
    store.searchQuery = ''
    store.clearIssueFilters()
  } finally {
    applyingBoardPrefs.value = false
  }
}

function flushSaveProjectBoard(projectId: number | null | undefined) {
  if (projectId == null) return
  writeProjectBoard(projectId, buildBoardSnapshot())
}

function scheduleSaveBoardPrefs() {
  const pid = projectStore.activeProjectId
  if (pid == null || applyingBoardPrefs.value) return
  if (boardPrefsSaveTimer != null) clearTimeout(boardPrefsSaveTimer)
  boardPrefsSaveTimer = setTimeout(() => {
    boardPrefsSaveTimer = null
    flushSaveProjectBoard(pid)
  }, 280)
}

function syncAssigneeFilterMeta() {
  const list = store.filterAssigneeList
  const newMap = new Map<number, string>()
  for (const item of list) {
    if (typeof item === 'number') {
      const u = users.value.find((x) => x.id === item)
      if (u) newMap.set(item, u.username.trim().toLowerCase())
    }
  }
  store.filterAssigneeUsernameNormMap = newMap
}

const activeFilterCount = computed(() => {
  let n = 0
  if (store.filterStatusList.length > 0) n++
  if (store.filterPriorityList.length > 0) n++
  if (store.filterAssigneeList.length > 0) n++
  if (store.filterLabelIds.length > 0) n++
  return n
})

const hasActiveFilters = computed(
  () =>
    store.filterStatusList.length > 0 ||
    store.filterPriorityList.length > 0 ||
    store.filterAssigneeList.length > 0 ||
    store.filterLabelIds.length > 0
)

const LABEL_COLORS = [
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#14b8a6',
  '#3b82f6',
  '#8b5cf6',
  '#ec4899'
]
function labelColor(id: number): string {
  return LABEL_COLORS[id % LABEL_COLORS.length] ?? '#6366f1'
}

function clearAllFilters() {
  store.clearIssueFilters()
}

const statusFilterLabel = computed(() => {
  const list = store.filterStatusList
  if (list.length === 1 && list[0]) return getStatusLabel(list[0])
  return `${list.length} ${t('boardView.statusCount')}`
})

const priorityFilterLabel = computed(() => {
  const list = store.filterPriorityList
  if (list.length === 1 && list[0]) return getPriorityLabel(list[0])
  return `${list.length} ${t('boardView.priorityCount')}`
})

const assigneeFilterLabel = computed(() => {
  const list = store.filterAssigneeList
  if (list.length === 1) {
    const item = list[0]
    if (item === 'unassigned') return t('common.unassigned')
    return users.value.find((u) => u.id === item)?.username ?? String(item)
  }
  return `${list.length} ${t('boardView.assigneeCount')}`
})

function clearAssigneeFilter() {
  store.filterAssigneeList = []
  store.filterAssigneeUsernameNormMap = new Map()
}

const filterButtonAria = computed(() =>
  activeFilterCount.value > 0
    ? t('boardView.filterButtonAriaActive', { n: activeFilterCount.value })
    : t('common.filter')
)

function clearIssueFiltersPanel() {
  store.clearIssueFilters()
}

function onFilterPanelKeydownCapture(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  const t = e.target as HTMLElement | null
  if (t?.closest('.custom-select-list')) return
  e.preventDefault()
  closeFilterPopover()
  nextTick(() => filterTriggerRef.value?.querySelector<HTMLButtonElement>('button')?.focus())
}

function onDisplayPanelKeydownCapture(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.preventDefault()
  closeDisplayPopover()
  nextTick(() => displayTriggerRef.value?.querySelector<HTMLButtonElement>('button')?.focus())
}

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

const groupingOptions = computed<CustomSelectOption[]>(() => [
  { value: 'status', label: t('common.status') },
  { value: 'priority', label: t('common.priority') },
  { value: 'assignee', label: t('common.assignee') },
  { value: 'project', label: t('common.project') },
  { value: 'none', label: t('common.none') }
])
const orderOptions = computed<CustomSelectOption[]>(() => [
  { value: 'updatedAt', label: t('common.updated') },
  { value: 'createdAt', label: t('common.created') },
  { value: 'priority', label: t('common.priority') },
  { value: 'dueDate', label: t('common.dueDate') },
  { value: 'title', label: t('common.title') }
])
const completedOptions = computed<CustomSelectOption[]>(() => [
  { value: 'all', label: t('common.all') },
  { value: 'open_only', label: t('boardView.openOnly') }
])
const visiblePropertyOptions = computed<Array<{ value: VisibleProperty; label: string }>>(() => [
  { value: 'id', label: t('boardView.id') },
  { value: 'status', label: t('common.status') },
  { value: 'priority', label: t('common.priority') },
  { value: 'assignee', label: t('common.assignee') },
  { value: 'project', label: t('common.project') },
  { value: 'labels', label: t('common.labels') },
  { value: 'dueDate', label: t('common.dueDate') },
  { value: 'plannedStart', label: t('boardView.plannedStart') },
  { value: 'progress', label: t('boardView.progress') },
  { value: 'updatedAt', label: t('common.updated') }
])

// Deep link: 打开 /tasks/:id 时同步 store 与 issuePanelStore，内容区由 BoardViewContent 根据 route 渲染编辑器
watch(() => route.params.taskId, (newId) => {
  if (newId) {
    store.currentTaskId = newId as string
    issuePanelStore.openWorkspace(newId as string)
  } else {
    store.currentTaskId = null
    issuePanelStore.closeWorkspace()
  }
}, { immediate: true })

async function loadProjectMembers(projectId: number | null) {
  if (projectId == null) {
    users.value = []
    return
  }
  try {
    users.value = await projectApi.listMembers(projectId)
  } catch (e) {
    console.error('Failed to load project members:', e)
    users.value = []
  }
}

onMounted(async () => {
  store.fetchTasks()
  await loadProjectMembers(projectStore.activeProjectId)
})

watch(
  () => projectStore.activeProjectId,
  (id) => {
    if (id != null) store.fetchTasks()
    loadProjectMembers(id)
  }
)

watch(
  () => viewModeStore.viewConfig.showSubIssues,
  () => {
    if (projectStore.activeProjectId != null) store.fetchTasks()
  }
)

watch(
  () => projectStore.activeProjectId,
  (id, prev) => {
    if (prev != null && prev !== id) flushSaveProjectBoard(prev)
    if (id == null) {
      resetBoardFiltersForProject()
      return
    }
    const snap = readProjectBoard(id)
    if (snap) applyBoardSnapshot(snap)
    else if (prev != null) resetBoardFiltersForProject()
  },
  { immediate: true }
)

watch(
  () => ({
    q: store.searchQuery,
    fsl: store.filterStatusList,
    fpl: store.filterPriorityList,
    fal: store.filterAssigneeList,
    fanm: store.filterAssigneeUsernameNormMap,
    fl: store.filterLabelIds,
    vc: viewModeStore.viewConfig
  }),
  () => {
    if (applyingBoardPrefs.value) return
    scheduleSaveBoardPrefs()
  },
  { deep: true }
)

watch(users, () => syncAssigneeFilterMeta(), { deep: true })

watch(filterPopoverOpen, (open) => {
  if (!open) return
  nextTick(() => {
    addIssueFilterMenuRef.value?.focusMenuSearch()
  })
})

watch(displayPopoverOpen, (open) => {
  if (!open) return
  nextTick(() => {
    const pop = displayPopoverRef.value
    if (!pop) return
    const input = pop.querySelector<HTMLInputElement>('input')
    if (input) input.focus()
    else pop.querySelector<HTMLElement>('.popover-display-option')?.focus()
  })
})

/** P4-6.5: defaultStatus 用于列头 + 新建时指定默认状态；parentNumericId 用于列表行「Add sub-issue」 */
function openCreateEditor(defaultStatus?: import('../types/domain').Status, parentNumericId?: number) {
  issuePanelStore.openComposer({
    status: defaultStatus,
    projectId: projectStore.activeProjectId ?? undefined,
    parentNumericId
  })
}

function openImportModal() {
  isImportOpen.value = true
}

// 浮层（Drawer / Composer / Import）由 BoardViewContent 注册

const viewType = computed(() => viewModeStore.viewType)

function setView(v: ViewType) {
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
  if (boardPrefsSaveTimer != null) {
    clearTimeout(boardPrefsSaveTimer)
    boardPrefsSaveTimer = null
  }
  flushSaveProjectBoard(projectStore.activeProjectId)
  window.removeEventListener('command-palette:new-task', onNewTaskCommand)
  window.removeEventListener('command-palette:focus-search', onFocusSearchCommand)
  window.removeEventListener('click', onClickOutsideFilter, true)
  window.removeEventListener('click', onClickOutsideDisplay, true)
})

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
    if (store.filterStatusList.length === 1 && store.filterStatusList[0] === 'backlog') return 'backlog'
    return 'all'
  },
  set(tab: ScopeTab) {
    if (tab === 'active') {
      store.filterStatusList = []
      viewModeStore.setCompletedVisibility('open_only')
    } else if (tab === 'backlog') {
      store.filterStatusList = ['backlog']
      viewModeStore.setCompletedVisibility('all')
    } else {
      store.filterStatusList = []
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
  const target = event.target as HTMLElement | null
  if (el?.contains(target) || trigger?.contains(target)) return
  if (target?.closest('[data-filter-submenu]')) return
  closeFilterPopover()
}
function onClickOutsideDisplay(event: MouseEvent) {
  const el = displayPopoverRef.value
  const trigger = displayTriggerRef.value
  if (el?.contains(event.target as Node) || trigger?.contains(event.target as Node)) return
  closeDisplayPopover()
}

</script>

<template>
  <div class="board-view">
    <header class="app-header">
      <div class="header-left">
        <button class="btn-create" @click="() => openCreateEditor()">{{ t('boardView.newIssue') }}</button>
        <button class="btn-import" @click="openImportModal">
          <Download class="icon-14" />
          <span>{{ t('common.import') }}</span>
        </button>
        <div class="view-toggle">
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewType === 'board' }"
            @click="setView('board')"
          >
            {{ t('common.board') }}
          </button>
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewType === 'list' }"
            @click="setView('list')"
          >
            {{ t('common.list') }}
          </button>
          <button
            type="button"
            class="toggle-btn"
            :class="{ active: viewType === 'gantt' }"
            @click="setView('gantt')"
          >
            {{ t('common.gantt') }}
          </button>
        </div>
        <input
          ref="searchInputRef"
          v-model="searchQuery"
          :placeholder="t('boardView.searchIssues')"
          class="search-input"
          :aria-label="t('boardView.searchIssues')"
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
            {{ t('boardView.allIssues') }}
          </button>
          <button
            type="button"
            class="scope-tab"
            :class="{ active: scopeTab === 'active' }"
            @click="setScopeTab('active')"
          >
            {{ t('boardView.active') }}
          </button>
          <button
            type="button"
            class="scope-tab"
            :class="{ active: scopeTab === 'backlog' }"
            @click="setScopeTab('backlog')"
          >
            {{ t('boardView.backlog') }}
          </button>
        </div>
        <button
          type="button"
          class="command-bar-add"
          :aria-label="t('boardView.newIssue')"
          @click="() => openCreateEditor()"
        >
          <Plus class="icon-14" />
        </button>
      </div>
      <div class="command-bar-right">
        <div ref="filterTriggerRef" class="popover-anchor popover-anchor-right">
          <button
            type="button"
            class="command-btn command-btn-filter"
            :class="{ active: filterPopoverOpen, 'has-active-filters': activeFilterCount > 0 }"
            :aria-label="filterButtonAria"
            aria-haspopup="true"
            :aria-expanded="filterPopoverOpen"
            @click="filterPopoverOpen = !filterPopoverOpen"
          >
            <Filter class="icon-14" />
            <span>{{ t('common.filter') }}</span>
            <span v-if="activeFilterCount > 0" class="filter-active-badge" aria-hidden="true">
              {{ t('boardView.filterBadge', { n: activeFilterCount }) }}
            </span>
          </button>
          <div
            v-show="filterPopoverOpen"
            ref="filterPopoverRef"
            class="popover popover-filter"
            role="dialog"
            :aria-label="t('boardView.filterOptions')"
            @keydown.capture="onFilterPanelKeydownCapture"
          >
            <AddIssueFilterMenu
              ref="addIssueFilterMenuRef"
              :project-id="projectStore.activeProjectId"
              :users="users"
            />
            <div v-if="activeFilterCount > 0" class="popover-section popover-section-clear">
              <button type="button" class="btn-clear-issue-filters" @click="clearIssueFiltersPanel">
                {{ t('boardView.clearIssueFilters') }}
              </button>
            </div>
            <div class="popover-divider" role="separator" />
            <h3 class="popover-section-title">{{ t('boardView.viewSectionTitle') }}</h3>
            <div class="popover-section">
              <label class="popover-label">{{ t('boardView.groupBy') }}</label>
              <CustomSelect
                v-model="groupBy"
                :options="groupingOptions"
                :placeholder="t('boardView.group')"
                :aria-label="t('boardView.groupTasks')"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">{{ t('boardView.sort') }}</label>
              <CustomSelect
                v-model="orderBy"
                :options="orderOptions"
                :placeholder="t('boardView.sort')"
                :aria-label="t('boardView.orderTasks')"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">{{ t('boardView.completed') }}</label>
              <CustomSelect
                v-model="completedVisibility"
                :options="completedOptions"
                :placeholder="t('boardView.completed')"
                :aria-label="t('boardView.completedVisibility')"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section popover-section-row">
              <button
                type="button"
                class="command-btn small order-dir-btn"
                :title="
                  viewModeStore.viewConfig.orderDirection === 'asc'
                    ? t('boardView.orderAscTitle')
                    : t('boardView.orderDescTitle')
                "
                @click="toggleOrderDirection"
              >
                <ArrowUpWideNarrow
                  v-if="viewModeStore.viewConfig.orderDirection === 'asc'"
                  class="icon-14"
                  aria-hidden="true"
                />
                <ArrowDownWideNarrow v-else class="icon-14" aria-hidden="true" />
                <span>{{
                  viewModeStore.viewConfig.orderDirection === 'asc'
                    ? t('boardView.orderAsc')
                    : t('boardView.orderDesc')
                }}</span>
              </button>
              <label class="filter-check">
                <input v-model="showEmptyGroups" type="checkbox" />
                <span>{{ t('boardView.emptyGroups') }}</span>
              </label>
            </div>
          </div>
        </div>
        <div ref="displayTriggerRef" class="popover-anchor popover-anchor-right">
          <button
            type="button"
            class="command-btn"
            :class="{ active: displayPopoverOpen }"
            :aria-label="t('common.display')"
            aria-haspopup="true"
            :aria-expanded="displayPopoverOpen"
            @click="displayPopoverOpen = !displayPopoverOpen"
          >
            <LayoutList class="icon-14" />
            <span>{{ t('common.display') }}</span>
          </button>
          <div
            v-show="displayPopoverOpen"
            ref="displayPopoverRef"
            class="popover popover-display"
            role="dialog"
            :aria-label="t('boardView.displayOptions')"
            @keydown.capture="onDisplayPanelKeydownCapture"
          >
            <div class="popover-section">
              <span class="popover-label">{{ t('boardView.showOnIssue') }}</span>
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
              <span class="popover-label">{{ t('boardView.subIssues') }}</span>
              <label class="popover-display-option">
                <input
                  type="checkbox"
                  :checked="viewModeStore.viewConfig.showSubIssues"
                  @change="viewModeStore.setShowSubIssues(!viewModeStore.viewConfig.showSubIssues)"
                />
                <span>{{ t('boardView.showSubIssues') }}</span>
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
                <span>{{ t('boardView.nestedSubIssues') }}</span>
              </label>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="hasActiveFilters" class="filter-bar">
      <div class="filter-bar-conditions">
        <div v-if="store.filterStatusList.length > 0" class="filter-condition">
          <Circle class="filter-condition-icon" />
          <span class="filter-condition-dim">{{ t('common.status') }}</span>
          <span class="filter-condition-op">{{ t('boardView.filterOp.includeAnyOf') }}</span>
          <span class="filter-condition-value">{{ statusFilterLabel }}</span>
          <button type="button" class="filter-condition-remove" @click="store.filterStatusList = []">
            <X class="icon-12" />
          </button>
        </div>
        <div v-if="store.filterPriorityList.length > 0" class="filter-condition">
          <BarChart3 class="filter-condition-icon" />
          <span class="filter-condition-dim">{{ t('common.priority') }}</span>
          <span class="filter-condition-op">{{ t('boardView.filterOp.includeAnyOf') }}</span>
          <span class="filter-condition-value">{{ priorityFilterLabel }}</span>
          <button type="button" class="filter-condition-remove" @click="store.filterPriorityList = []">
            <X class="icon-12" />
          </button>
        </div>
        <div v-if="store.filterAssigneeList.length > 0" class="filter-condition">
          <UserIcon class="filter-condition-icon" />
          <span class="filter-condition-dim">{{ t('common.assignee') }}</span>
          <span class="filter-condition-op">{{ t('boardView.filterOp.includeAnyOf') }}</span>
          <span class="filter-condition-value">{{ assigneeFilterLabel }}</span>
          <button type="button" class="filter-condition-remove" @click="clearAssigneeFilter">
            <X class="icon-12" />
          </button>
        </div>
        <div v-if="store.filterLabelIds.length > 0" class="filter-condition">
          <Tag class="filter-condition-icon" />
          <span class="filter-condition-dim">{{ t('boardView.labels') }}</span>
          <span class="filter-condition-op">{{ t('boardView.filterOp.includeAnyOf') }}</span>
          <span class="filter-condition-value filter-condition-labels">
            <span
              v-for="lid in store.filterLabelIds.slice(0, 3)"
              :key="lid"
              class="filter-label-dot"
              :style="{ background: labelColor(lid) }"
            />
            <span>{{ store.filterLabelIds.length }} {{ t('boardView.labelsCount') }}</span>
          </span>
          <button type="button" class="filter-condition-remove" @click="store.filterLabelIds = []">
            <X class="icon-12" />
          </button>
        </div>
        <button type="button" class="filter-add-more" @click="filterPopoverOpen = true">
          <Plus class="icon-12" />
        </button>
      </div>
      <div class="filter-bar-actions">
        <button type="button" class="filter-bar-clear" @click="clearAllFilters">
          {{ t('boardView.clearFilters') }}
        </button>
      </div>
    </div>

    <Suspense>
      <BoardViewContent
        :users="users"
        :import-open="isImportOpen"
        @close-import="isImportOpen = false"
      />
      <template #fallback>
        <div class="board-content board-content--loading">
          <p class="loading-placeholder">{{ t('boardView.loadingTasks') }}</p>
        </div>
      </template>
    </Suspense>
  </div>
</template>

<style scoped>
/* P6-2: 顶部工具栏与页面层级 — 命令带化、内容优先 */
.board-view {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
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
  min-width: 0;
}

.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--color-bg-subtle);
  border-bottom: 1px solid var(--color-border-subtle);
}
.filter-bar-conditions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-condition {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px 4px 10px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: 6px;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
}
.filter-condition-icon {
  width: 14px;
  height: 14px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.filter-condition-dim {
  color: var(--color-text-muted);
}
.filter-condition-op {
  color: var(--color-text-muted);
  font-size: 11px;
}
.filter-condition-value {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.filter-condition-labels {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.filter-label-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.filter-condition-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-left: 2px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  border-radius: 4px;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.filter-condition-remove:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.filter-add-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px dashed var(--color-border-subtle);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  border-radius: 6px;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}
.filter-add-more:hover {
  border-color: var(--color-border-medium);
  color: var(--color-text-secondary);
}
.filter-bar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-bar-clear {
  padding: 4px 8px;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  cursor: pointer;
  border-radius: 4px;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.filter-bar-clear:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.filter-active-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  max-width: min(420px, 55vw);
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 140px;
  padding: 2px 6px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.filter-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.filter-chip:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.icon-12 {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
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
  padding: 10px 12px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  z-index: 50;
}
.popover-filter {
  width: min(210px, calc(100vw - 24px));
  box-sizing: border-box;
  padding: 0;
}
.popover-filter .popover-section-clear {
  padding: 8px 12px;
}
.popover-filter .popover-divider {
  margin: 0;
}
.popover-filter .popover-section-title {
  padding: 8px 12px 4px;
  margin: 0;
}
.popover-filter .popover-section {
  padding: 0 12px 8px;
  margin: 0;
}
.popover-filter .popover-section-row {
  padding: 4px 12px 8px;
}
.popover-filter .popover-section {
  min-width: 0;
}
.popover-filter :deep(.custom-select) {
  display: block;
  width: 100%;
}
.popover-filter :deep(.custom-select-trigger) {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
.popover-filter :deep(.trigger-label) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.popover-section-title {
  margin: 0 0 6px;
  font-size: var(--font-size-xs);
  font-weight: 600;
  color: var(--color-text-secondary);
  letter-spacing: 0.02em;
}
.popover-filter .popover-section-title:not(:first-child) {
  margin-top: 2px;
}
.popover-divider {
  height: 1px;
  margin: 10px 0 12px;
  background: var(--color-border-subtle);
}
.popover-hint {
  margin: 6px 0 0;
  font-size: 10px;
  line-height: 1.45;
  color: var(--color-text-muted);
}
.btn-clear-issue-filters {
  width: 100%;
  padding: 6px 10px;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.btn-clear-issue-filters:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.command-btn-filter.has-active-filters {
  color: var(--color-accent);
}
.filter-active-badge {
  margin-left: 4px;
  padding: 0 6px;
  font-size: 10px;
  font-weight: 600;
  color: var(--color-accent);
  background: var(--color-accent-muted);
  border-radius: 999px;
  line-height: 1.4;
}
.order-dir-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
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
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  width: 100%;
  box-sizing: border-box;
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

/* Suspense fallback 与内容区占位 */
.board-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.board-content--loading {
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
}
.loading-placeholder {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
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
