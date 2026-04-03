<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  BarChart3,
  ChevronRight,
  CircleDashed,
  Tag,
  User as UserIcon
} from 'lucide-vue-next'
import CustomSelect from '../ui/CustomSelect.vue'
import type { CustomSelectOption } from '../ui/CustomSelect.vue'
import type { User } from '../../types/domain'
import { useTaskStore } from '../../store/taskStore'
import { projectApi } from '../../services/api/project'
import type { Priority, Status } from '../../types/domain'

type SubKey = 'status' | 'priority' | 'assignee' | 'labels'

const props = defineProps<{
  projectId: number | null
  users: User[]
  filterStatusOptions: CustomSelectOption[]
  filterPriorityOptions: CustomSelectOption[]
  filterAssigneeOptions: CustomSelectOption[]
}>()

const emit = defineEmits<{
  clear: []
}>()

const store = useTaskStore()
const { t } = useI18n()

const menuSearch = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)

const activeSub = ref<SubKey | null>(null)
let leaveTimer: ReturnType<typeof setTimeout> | null = null

function cancelLeaveTimer() {
  if (leaveTimer != null) {
    clearTimeout(leaveTimer)
    leaveTimer = null
  }
}

function scheduleCloseSub() {
  cancelLeaveTimer()
  leaveTimer = setTimeout(() => {
    activeSub.value = null
  }, 200)
}

function onClusterEnter() {
  cancelLeaveTimer()
}

function onClusterLeave() {
  scheduleCloseSub()
}

function onRowEnter(key: SubKey) {
  cancelLeaveTimer()
  activeSub.value = key
}

function onRowClick(key: SubKey) {
  cancelLeaveTimer()
  activeSub.value = activeSub.value === key ? null : key
}

const filterStatus = computed({
  get: () => store.filterStatus,
  set: (val: Status | null) => {
    store.filterStatus = val
  }
})

const filterPriority = computed({
  get: () => store.filterPriority,
  set: (val: Priority | null) => {
    store.filterPriority = val
  }
})

function syncAssigneeFilterMeta() {
  const fa = store.filterAssignee
  if (typeof fa === 'number') {
    const u = props.users.find((x) => x.id === fa)
    store.filterAssigneeUsernameNorm = u?.username?.trim().toLowerCase() ?? null
  } else {
    store.filterAssigneeUsernameNorm = null
  }
}

const filterAssigneeModel = computed({
  get: () => store.filterAssignee as string | number | null,
  set: (val: string | number | null) => {
    if (val === 'unassigned') {
      store.filterAssignee = 'unassigned'
      store.filterAssigneeUsernameNorm = null
    } else if (val === null || val === '') {
      store.filterAssignee = null
      store.filterAssigneeUsernameNorm = null
    } else if (typeof val === 'number') {
      store.filterAssignee = Number.isFinite(val) ? val : null
      syncAssigneeFilterMeta()
    } else {
      const n = Number(val)
      store.filterAssignee = Number.isFinite(n) ? n : null
      syncAssigneeFilterMeta()
    }
  }
})

watch(
  () => props.users,
  () => syncAssigneeFilterMeta(),
  { deep: true }
)

type DimRow = { key: SubKey; label: string; icon: typeof CircleDashed }

const allDimensions = computed<DimRow[]>(() => [
  { key: 'status', label: t('common.status'), icon: CircleDashed },
  { key: 'priority', label: t('common.priority'), icon: BarChart3 },
  { key: 'assignee', label: t('common.assignee'), icon: UserIcon },
  { key: 'labels', label: t('common.labels'), icon: Tag }
])

const visibleDimensions = computed(() => {
  const q = menuSearch.value.trim().toLowerCase()
  if (!q) return allDimensions.value
  return allDimensions.value.filter((d) => d.label.toLowerCase().includes(q))
})

const labelsSearch = ref('')
const labelsLoading = ref(false)
const labelsError = ref<string | null>(null)
const labelRows = ref<{ id: number; name: string }[]>([])
let labelsSearchTimer: ReturnType<typeof setTimeout> | null = null
const labelsCache = new Map<string, { id: number; name: string }[]>()

function cacheKey(query: string) {
  return `${props.projectId ?? 'none'}:${query.trim().toLowerCase()}`
}

async function loadLabels(query: string) {
  const pid = props.projectId
  if (pid == null) {
    labelRows.value = []
    return
  }
  const key = cacheKey(query)
  const hit = labelsCache.get(key)
  if (hit) {
    labelRows.value = hit
    labelsError.value = null
    return
  }
  labelsLoading.value = true
  labelsError.value = null
  try {
    const list = await projectApi.listLabels(pid, query.trim() || undefined)
    labelsCache.set(key, list)
    labelRows.value = list
  } catch {
    labelsError.value = t('boardView.labelsLoadFailed')
    labelRows.value = []
  } finally {
    labelsLoading.value = false
  }
}

function scheduleLabelsLoad(query: string) {
  if (labelsSearchTimer != null) clearTimeout(labelsSearchTimer)
  labelsSearchTimer = setTimeout(() => loadLabels(query), 250)
}

watch(activeSub, (k) => {
  if (k === 'labels') {
    labelsSearch.value = ''
    loadLabels('')
  }
})

watch(labelsSearch, (q) => {
  if (activeSub.value !== 'labels') return
  scheduleLabelsLoad(q)
})

watch(
  () => props.projectId,
  (pid, prev) => {
    if (pid !== prev) {
      labelsCache.clear()
      if (activeSub.value === 'labels' && pid != null) loadLabels(labelsSearch.value)
    }
  }
)

onUnmounted(() => {
  cancelLeaveTimer()
  if (labelsSearchTimer != null) clearTimeout(labelsSearchTimer)
})

function isLabelChecked(id: number) {
  return store.filterLabelIds.includes(id)
}

defineExpose({
  focusMenuSearch: () => searchInputRef.value?.focus()
})
</script>

<template>
  <div class="add-issue-filter">
    <h3 class="add-issue-filter-title">{{ t('boardView.filterSectionTitle') }}</h3>
    <input
      ref="searchInputRef"
      v-model="menuSearch"
      type="search"
      class="add-issue-filter-search"
      :placeholder="t('boardView.addFilterPlaceholder')"
      :aria-label="t('boardView.addFilterPlaceholder')"
      autocomplete="off"
    />
    <div
      class="add-issue-filter-cluster"
      @mouseenter="onClusterEnter"
      @mouseleave="onClusterLeave"
    >
      <div
        v-show="activeSub != null"
        class="add-issue-filter-sub"
        role="region"
        :aria-label="t('boardView.filterSubmenuAria')"
      >
        <template v-if="activeSub === 'status'">
          <label class="add-issue-filter-sub-label">{{ t('common.status') }}</label>
          <CustomSelect
            v-model="filterStatus"
            :options="filterStatusOptions"
            :placeholder="t('boardView.allStatus')"
            :aria-label="t('boardView.filterByStatus')"
            trigger-class="popover-select"
          />
        </template>
        <template v-else-if="activeSub === 'priority'">
          <label class="add-issue-filter-sub-label">{{ t('common.priority') }}</label>
          <CustomSelect
            v-model="filterPriority"
            :options="filterPriorityOptions"
            :placeholder="t('boardView.allPriorities')"
            :aria-label="t('boardView.filterByPriority')"
            trigger-class="popover-select"
          />
        </template>
        <template v-else-if="activeSub === 'assignee'">
          <label class="add-issue-filter-sub-label">{{ t('common.assignee') }}</label>
          <CustomSelect
            v-model="filterAssigneeModel"
            :options="filterAssigneeOptions"
            :placeholder="t('boardView.allAssignees')"
            :aria-label="t('boardView.filterByAssignee')"
            filterable
            trigger-class="popover-select"
          />
          <p class="add-issue-filter-hint">{{ t('boardView.assigneeFilterHint') }}</p>
        </template>
        <template v-else-if="activeSub === 'labels'">
          <label class="add-issue-filter-sub-label">{{ t('common.labels') }}</label>
          <input
            v-model="labelsSearch"
            type="search"
            class="add-issue-filter-search add-issue-filter-search--nested"
            :placeholder="t('boardView.searchLabels')"
            :aria-label="t('boardView.searchLabels')"
            autocomplete="off"
            :disabled="projectId == null"
          />
          <p v-if="projectId == null" class="add-issue-filter-hint">{{ t('boardView.labelsNeedProject') }}</p>
          <p v-else-if="labelsError" class="add-issue-filter-error">{{ labelsError }}</p>
          <p v-else-if="labelsLoading" class="add-issue-filter-hint">{{ t('boardView.labelsLoading') }}</p>
          <ul v-else class="add-issue-filter-label-list" role="listbox" :aria-multiselectable="true">
            <li v-for="row in labelRows" :key="row.id" class="add-issue-filter-label-row">
              <label class="add-issue-filter-label-check">
                <input
                  type="checkbox"
                  :checked="isLabelChecked(row.id)"
                  @change="store.toggleFilterLabelId(row.id)"
                />
                <span>{{ row.name }}</span>
              </label>
            </li>
          </ul>
          <p
            v-if="projectId != null && !labelsLoading && !labelsError && labelRows.length === 0"
            class="add-issue-filter-hint"
          >
            {{ t('boardView.noLabelsMatch') }}
          </p>
        </template>
      </div>
      <div class="add-issue-filter-main">
        <ul class="add-issue-filter-dim-list" role="menu">
          <li
            v-for="dim in visibleDimensions"
            :key="dim.key"
            role="none"
            class="add-issue-filter-dim"
            :class="{ active: activeSub === dim.key }"
            @mouseenter="onRowEnter(dim.key)"
            @click="onRowClick(dim.key)"
          >
            <button type="button" class="add-issue-filter-dim-btn" role="menuitem">
              <component :is="dim.icon" class="icon-14 dim-icon" aria-hidden="true" />
              <span class="dim-label">{{ dim.label }}</span>
              <ChevronRight class="icon-14 chevron" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <button type="button" class="btn-clear-issue-filters" @click="emit('clear')">
          {{ t('boardView.clearIssueFilters') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.add-issue-filter {
  min-width: 0;
}
.add-issue-filter-title {
  margin: 0 0 6px;
  font-size: var(--font-size-xs);
  font-weight: 600;
  color: var(--color-text-secondary);
  letter-spacing: 0.02em;
}
.add-issue-filter-search {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 8px;
  padding: 6px 8px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  background: var(--color-bg-subtle);
  color: var(--color-text-primary);
}
.add-issue-filter-search:focus {
  outline: none;
  border-color: var(--color-border-strong);
  background: var(--color-bg-base);
}
.add-issue-filter-search--nested {
  margin-top: 6px;
  margin-bottom: 6px;
}
.add-issue-filter-cluster {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.add-issue-filter-sub {
  order: -1;
  flex: 0 0 min(220px, 42vw);
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  background: var(--color-bg-subtle);
  box-sizing: border-box;
}
.add-issue-filter-sub-label {
  display: block;
  margin-bottom: 4px;
  font-size: var(--font-size-xs);
  font-weight: 600;
  color: var(--color-text-secondary);
}
.add-issue-filter-main {
  flex: 1;
  min-width: 0;
}
.add-issue-filter-dim-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.add-issue-filter-dim {
  margin-bottom: 2px;
  border-radius: var(--radius-sm);
}
.add-issue-filter-dim.active {
  background: var(--color-bg-hover);
}
.add-issue-filter-dim-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  cursor: pointer;
  text-align: left;
}
.add-issue-filter-dim-btn:hover {
  background: var(--color-bg-hover);
}
.dim-icon {
  flex-shrink: 0;
  color: var(--color-text-muted);
}
.dim-label {
  flex: 1;
  min-width: 0;
}
.chevron {
  flex-shrink: 0;
  color: var(--color-text-muted);
  opacity: 0.7;
}
.add-issue-filter-hint {
  margin: 6px 0 0;
  font-size: 10px;
  line-height: 1.45;
  color: var(--color-text-muted);
}
.add-issue-filter-error {
  margin: 6px 0 0;
  font-size: var(--font-size-caption);
  color: var(--color-danger, #c00);
}
.add-issue-filter-label-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 200px;
  overflow: auto;
}
.add-issue-filter-label-row {
  margin: 0;
}
.add-issue-filter-label-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: var(--font-size-caption);
  cursor: pointer;
  color: var(--color-text-primary);
}
.btn-clear-issue-filters {
  width: 100%;
  margin-top: 8px;
  padding: 6px 10px;
  font-size: var(--font-size-caption);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  cursor: pointer;
}
.btn-clear-issue-filters:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
:deep(.custom-select) {
  display: block;
  width: 100%;
}
:deep(.custom-select-trigger) {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
:deep(.trigger-label) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
