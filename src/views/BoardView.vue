<script setup lang="ts">
import { onMounted, onUnmounted, computed, watch, ref, defineAsyncComponent, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import CustomSelect from '../components/ui/CustomSelect.vue'
import type { CustomSelectOption } from '../components/ui/CustomSelect.vue'
import { userApi } from '../services/api/user'
import type { User } from '../types/domain'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from '../components/icons/PriorityIcons'
import {
  ArrowDownWideNarrow,
  ArrowUpWideNarrow,
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
  Download,
  User as UserIcon
} from 'lucide-vue-next'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import type { CompletedVisibility, GroupBy, OrderBy, VisibleProperty } from '../utils/viewPreference'
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
const displayPopoverRef = ref<HTMLElement | null>(null)
const isImportOpen = ref(false)

// 命令栏在任务详情（右侧抽屉）打开时隐藏
const isEditorOpen = computed(() => !!route.params.taskId)

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
const filterAssigneeModel = computed({
  get(): string | number | null {
    return store.filterAssignee as string | number | null
  },
  set(val: string | number | null) {
    if (val === 'unassigned') {
      store.filterAssignee = 'unassigned'
      store.filterAssigneeUsernameNorm = null
    } else if (val === null) {
      store.filterAssignee = null
      store.filterAssigneeUsernameNorm = null
    } else if (typeof val === 'number' && Number.isFinite(val)) {
      store.filterAssignee = val
      syncAssigneeFilterMeta()
    } else if (typeof val === 'string') {
      const n = Number(val)
      store.filterAssignee = Number.isFinite(n) ? n : null
      syncAssigneeFilterMeta()
    } else {
      store.filterAssignee = null
      store.filterAssigneeUsernameNorm = null
    }
  }
})

const applyingBoardPrefs = ref(false)
let boardPrefsSaveTimer: ReturnType<typeof setTimeout> | null = null

function buildBoardSnapshot(): ProjectBoardSnapshot {
  const vc = viewModeStore.viewConfig
  return {
    filters: {
      searchQuery: store.searchQuery,
      filterStatus: store.filterStatus,
      filterPriority: store.filterPriority,
      filterAssignee: store.filterAssignee,
      filterAssigneeUsernameNorm: store.filterAssigneeUsernameNorm
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
    store.filterStatus = snap.filters.filterStatus
    store.filterPriority = snap.filters.filterPriority
    store.filterAssignee = snap.filters.filterAssignee
    store.filterAssigneeUsernameNorm = snap.filters.filterAssigneeUsernameNorm
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
  const fa = store.filterAssignee
  if (typeof fa === 'number') {
    const u = users.value.find((x) => x.id === fa)
    store.filterAssigneeUsernameNorm = u?.username?.trim().toLowerCase() ?? null
  } else {
    store.filterAssigneeUsernameNorm = null
  }
}

const activeFilterCount = computed(() => {
  let n = 0
  if (store.filterStatus != null) n++
  if (store.filterPriority != null) n++
  if (store.filterAssignee != null) n++
  return n
})

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

const filterStatusOptions = computed<CustomSelectOption[]>(() => [
  { value: null, label: t('boardView.allStatus') },
  { value: 'backlog', label: getStatusLabel('backlog'), icon: CircleDashed },
  { value: 'todo', label: getStatusLabel('todo'), icon: Circle },
  { value: 'in_progress', label: getStatusLabel('in_progress'), icon: Loader2 },
  { value: 'in_review', label: getStatusLabel('in_review'), icon: Eye },
  { value: 'done', label: getStatusLabel('done'), icon: CheckCircle },
  { value: 'canceled', label: getStatusLabel('canceled'), icon: CircleX },
  { value: 'duplicate', label: getStatusLabel('duplicate'), icon: Copy }
])
const filterPriorityOptions = computed<CustomSelectOption[]>(() => [
  { value: null, label: t('boardView.allPriorities') },
  { value: 'urgent', label: getPriorityLabel('urgent'), icon: PriorityUrgentIcon },
  { value: 'high', label: getPriorityLabel('high'), icon: PriorityHighIcon },
  { value: 'medium', label: getPriorityLabel('medium'), icon: PriorityMediumIcon },
  { value: 'low', label: getPriorityLabel('low'), icon: PriorityLowIcon }
])
const filterAssigneeOptions = computed<CustomSelectOption[]>(() => {
  const sorted = [...users.value].sort((a, b) =>
    a.username.localeCompare(b.username, undefined, { sensitivity: 'base' })
  )
  return [
    { value: null, label: t('boardView.allAssignees') },
    { value: 'unassigned', label: t('common.unassigned'), icon: UserIcon },
    ...sorted.map((u) => ({ value: u.id, label: u.username }))
  ]
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
    fs: store.filterStatus,
    fp: store.filterPriority,
    fa: store.filterAssignee,
    fan: store.filterAssigneeUsernameNorm,
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
    filterPopoverRef.value?.querySelector<HTMLButtonElement>('.custom-select-trigger')?.focus()
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
            <h3 class="popover-section-title">{{ t('boardView.filterSectionTitle') }}</h3>
            <div class="popover-section">
              <label class="popover-label">{{ t('common.status') }}</label>
              <CustomSelect
                v-model="filterStatus"
                :options="filterStatusOptions"
                :placeholder="t('boardView.allStatus')"
                :aria-label="t('boardView.filterByStatus')"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">{{ t('common.priority') }}</label>
              <CustomSelect
                v-model="filterPriority"
                :options="filterPriorityOptions"
                :placeholder="t('boardView.allPriorities')"
                :aria-label="t('boardView.filterByPriority')"
                trigger-class="popover-select"
              />
            </div>
            <div class="popover-section">
              <label class="popover-label">{{ t('common.assignee') }}</label>
              <CustomSelect
                v-model="filterAssigneeModel"
                :options="filterAssigneeOptions"
                :placeholder="t('boardView.allAssignees')"
                :aria-label="t('boardView.filterByAssignee')"
                filterable
                trigger-class="popover-select"
              />
              <p class="popover-hint">{{ t('boardView.assigneeFilterHint') }}</p>
            </div>
            <div class="popover-section">
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
  width: min(248px, calc(100vw - 24px));
  box-sizing: border-box;
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
