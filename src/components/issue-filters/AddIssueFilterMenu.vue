<script setup lang="ts">
import { computed, onUnmounted, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  BarChart3,
  Check,
  ChevronRight,
  CircleDashed,
  Tag,
  User as UserIcon
} from 'lucide-vue-next'
import type { User, Status, Priority } from '../../types/domain'
import { useTaskStore } from '../../store/taskStore'
import { projectApi } from '../../services/api/project'
import { getStatusLabel, getPriorityLabel } from '../../utils/enumLabels'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from '../icons/PriorityIcons'
import {
  Circle,
  CircleX,
  CheckCircle,
  Copy,
  Eye,
  Loader2
} from 'lucide-vue-next'

type SubKey = 'status' | 'priority' | 'assignee' | 'labels'

const props = defineProps<{
  projectId: number | null
  users: User[]
}>()

defineEmits<{ clear: [] }>()

const store = useTaskStore()
const { t } = useI18n()

const menuSearch = ref('')
const searchInputRef = ref<HTMLInputElement | null>(null)
const activeSub = ref<SubKey | null>(null)
const menuRef = ref<HTMLElement | null>(null)
const itemRefs = ref<Map<SubKey, HTMLElement>>(new Map())
let leaveTimer: ReturnType<typeof setTimeout> | null = null

function setItemRef(el: HTMLElement | null, key: SubKey) {
  if (el) itemRefs.value.set(key, el)
  else itemRefs.value.delete(key)
}

const subMenuStyle = computed(() => {
  if (!activeSub.value) return { display: 'none' }
  const itemEl = itemRefs.value.get(activeSub.value)
  const menuEl = menuRef.value
  if (!itemEl || !menuEl) return { display: 'none' }
  const menuRect = menuEl.getBoundingClientRect()
  const itemRect = itemEl.getBoundingClientRect()
  const subWidth = 180
  let left = menuRect.left - subWidth - 4
  if (left < 8) left = menuRect.right + 4
  return {
    top: `${Math.max(8, itemRect.top - 4)}px`,
    left: `${left}px`
  }
})

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
  }, 150)
}

function onRowEnter(key: SubKey) {
  cancelLeaveTimer()
  activeSub.value = key
}

function onRowLeave() {
  scheduleCloseSub()
}

function onSubEnter() {
  cancelLeaveTimer()
}

function onSubLeave() {
  scheduleCloseSub()
}

type DimRow = { key: SubKey; label: string; icon: Component }

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

const statusOptions: { value: Status; icon: Component }[] = [
  { value: 'backlog', icon: CircleDashed },
  { value: 'todo', icon: Circle },
  { value: 'in_progress', icon: Loader2 },
  { value: 'in_review', icon: Eye },
  { value: 'done', icon: CheckCircle },
  { value: 'canceled', icon: CircleX },
  { value: 'duplicate', icon: Copy }
]

const priorityOptions: { value: Priority; icon: Component }[] = [
  { value: 'urgent', icon: PriorityUrgentIcon as Component },
  { value: 'high', icon: PriorityHighIcon as Component },
  { value: 'medium', icon: PriorityMediumIcon as Component },
  { value: 'low', icon: PriorityLowIcon as Component }
]

function selectStatus(val: Status) {
  store.toggleFilterStatus(val)
}

function selectPriority(val: Priority) {
  store.toggleFilterPriority(val)
}

function selectAssignee(val: number | 'unassigned') {
  if (val === 'unassigned') {
    store.toggleFilterAssignee('unassigned')
  } else {
    const u = props.users.find((x) => x.id === val)
    const norm = u?.username?.trim().toLowerCase() ?? undefined
    store.toggleFilterAssignee(val, norm)
  }
}

function isStatusChecked(val: Status): boolean {
  return store.filterStatusList.includes(val)
}

function isPriorityChecked(val: Priority): boolean {
  return store.filterPriorityList.includes(val)
}

function isAssigneeChecked(val: number | 'unassigned'): boolean {
  return store.filterAssigneeList.includes(val)
}

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

const LABEL_COLORS = [
  '#e53935', '#d81b60', '#8e24aa', '#5e35b1',
  '#3949ab', '#1e88e5', '#039be5', '#00acc1',
  '#00897b', '#43a047', '#7cb342', '#c0ca33',
  '#fdd835', '#ffb300', '#fb8c00', '#f4511e'
]

function labelColor(id: number): string {
  return LABEL_COLORS[id % LABEL_COLORS.length] ?? '#6366f1'
}

defineExpose({
  focusMenuSearch: () => searchInputRef.value?.focus()
})
</script>

<template>
  <div ref="menuRef" class="filter-menu">
    <div class="filter-menu-search-wrap">
      <input
        ref="searchInputRef"
        v-model="menuSearch"
        type="text"
        class="filter-menu-search"
        :placeholder="t('boardView.addFilterPlaceholder')"
        autocomplete="off"
      />
      <kbd class="filter-menu-kbd">F</kbd>
    </div>

    <ul class="filter-menu-list">
      <li
        v-for="dim in visibleDimensions"
        :key="dim.key"
        :ref="(el) => setItemRef(el as HTMLElement, dim.key)"
        class="filter-menu-item"
        :class="{ active: activeSub === dim.key }"
        @mouseenter="onRowEnter(dim.key)"
        @mouseleave="onRowLeave"
      >
        <component :is="dim.icon" class="filter-menu-icon" />
        <span class="filter-menu-label">{{ dim.label }}</span>
        <ChevronRight class="filter-menu-arrow" />
      </li>
    </ul>

    <Teleport to="body">
      <div
        v-if="activeSub != null"
        class="filter-submenu"
        data-filter-submenu
        :style="subMenuStyle"
        @mouseenter="onSubEnter"
        @mouseleave="onSubLeave"
      >
        <template v-if="activeSub === 'status'">
          <ul class="filter-submenu-list">
            <li
              v-for="opt in statusOptions"
              :key="opt.value"
              class="filter-submenu-item"
              :class="{ selected: isStatusChecked(opt.value) }"
              @click="selectStatus(opt.value)"
            >
              <component :is="opt.icon" class="filter-submenu-icon" />
              <span class="filter-submenu-label">{{ getStatusLabel(opt.value) }}</span>
              <Check v-if="isStatusChecked(opt.value)" class="filter-submenu-check" />
            </li>
          </ul>
        </template>

        <template v-else-if="activeSub === 'priority'">
          <ul class="filter-submenu-list">
            <li
              v-for="opt in priorityOptions"
              :key="opt.value"
              class="filter-submenu-item"
              :class="{ selected: isPriorityChecked(opt.value) }"
              @click="selectPriority(opt.value)"
            >
              <component :is="opt.icon" class="filter-submenu-icon" />
              <span class="filter-submenu-label">{{ getPriorityLabel(opt.value) }}</span>
              <Check v-if="isPriorityChecked(opt.value)" class="filter-submenu-check" />
            </li>
          </ul>
        </template>

        <template v-else-if="activeSub === 'assignee'">
          <ul class="filter-submenu-list">
            <li
              class="filter-submenu-item"
              :class="{ selected: isAssigneeChecked('unassigned') }"
              @click="selectAssignee('unassigned')"
            >
              <UserIcon class="filter-submenu-icon" />
              <span class="filter-submenu-label">{{ t('common.unassigned') }}</span>
              <Check v-if="isAssigneeChecked('unassigned')" class="filter-submenu-check" />
            </li>
            <li
              v-for="user in users"
              :key="user.id"
              class="filter-submenu-item"
              :class="{ selected: isAssigneeChecked(user.id) }"
              @click="selectAssignee(user.id)"
            >
              <span class="filter-submenu-avatar">{{ user.username.charAt(0).toUpperCase() }}</span>
              <span class="filter-submenu-label">{{ user.username }}</span>
              <Check v-if="isAssigneeChecked(user.id)" class="filter-submenu-check" />
            </li>
          </ul>
        </template>

        <template v-else-if="activeSub === 'labels'">
          <div v-if="projectId == null" class="filter-submenu-empty">
            {{ t('boardView.labelsNeedProject') }}
          </div>
          <div v-else-if="labelsLoading" class="filter-submenu-empty">
            {{ t('boardView.labelsLoading') }}
          </div>
          <div v-else-if="labelsError" class="filter-submenu-error">
            {{ labelsError }}
          </div>
          <template v-else>
            <div v-if="labelRows.length === 0" class="filter-submenu-empty">
              {{ t('boardView.noLabelsMatch') }}
            </div>
            <ul v-else class="filter-submenu-list">
              <li
                v-for="row in labelRows"
                :key="row.id"
                class="filter-submenu-item"
                :class="{ selected: isLabelChecked(row.id) }"
                @click="store.toggleFilterLabelId(row.id)"
              >
                <span class="filter-submenu-dot" :style="{ background: labelColor(row.id) }" />
                <span class="filter-submenu-label">{{ row.name }}</span>
                <Check v-if="isLabelChecked(row.id)" class="filter-submenu-check" />
              </li>
            </ul>
          </template>
        </template>
      </div>
    </Teleport>
  </div>
</template>


<style scoped>
.filter-menu {
  width: 190px;
}

.filter-menu-search-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border-subtle);
}

.filter-menu-search {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--color-text-primary);
  outline: none;
}

.filter-menu-search::placeholder {
  color: var(--color-text-muted);
}

.filter-menu-kbd {
  flex-shrink: 0;
  padding: 1px 5px;
  font-size: 11px;
  font-family: inherit;
  color: var(--color-text-muted);
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border-subtle);
  border-radius: 4px;
  line-height: 1.4;
}

.filter-menu-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.filter-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 12px;
  cursor: pointer;
  transition: background 0.08s;
}

.filter-menu-item:hover,
.filter-menu-item.active {
  background: var(--color-bg-hover);
}

.filter-menu-icon {
  width: 15px;
  height: 15px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}

.filter-menu-label {
  flex: 1;
  font-size: 13px;
  color: var(--color-text-primary);
}

.filter-menu-arrow {
  width: 14px;
  height: 14px;
  color: var(--color-text-muted);
  opacity: 0.5;
}

.filter-submenu {
  position: fixed;
  width: 180px;
  max-height: 280px;
  overflow-y: auto;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12), 0 1px 3px rgba(0, 0, 0, 0.08);
  z-index: 9999;
}

.filter-submenu-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.filter-submenu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  cursor: pointer;
  transition: background 0.08s;
}

.filter-submenu-item:hover {
  background: var(--color-bg-hover);
}

.filter-submenu-item.selected {
  background: rgba(99, 102, 241, 0.08);
}

.filter-submenu-icon {
  width: 15px;
  height: 15px;
  color: var(--color-text-muted);
  flex-shrink: 0;
}

.filter-submenu-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.filter-submenu-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.filter-submenu-label {
  flex: 1;
  font-size: 13px;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.filter-submenu-check {
  width: 14px;
  height: 14px;
  color: var(--color-accent);
  flex-shrink: 0;
}

.filter-submenu-empty,
.filter-submenu-error {
  padding: 16px 12px;
  font-size: 12px;
  color: var(--color-text-muted);
  text-align: center;
}

.filter-submenu-error {
  color: var(--color-danger, #dc2626);
}
</style>
