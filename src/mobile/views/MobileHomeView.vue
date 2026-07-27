<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ChevronDown, ChevronRight, Search, SlidersHorizontal } from 'lucide-vue-next'
import { useAuthStore } from '../../store/authStore'
import { useProjectStore } from '../../store/projectStore'
import { useTaskStore } from '../../store/taskStore'
import type { Status, Task, User } from '../../types/domain'
import { getAvatarColorByUsername, getInitials } from '../../utils/avatar'
import MobileBottomSheet from '../components/MobileBottomSheet.vue'
import MobileEmptyState from '../components/MobileEmptyState.vue'
import MobileStatusGlyph from '../components/MobileStatusGlyph.vue'
import MobileTaskRow from '../components/MobileTaskRow.vue'

const props = defineProps<{ users: User[]; focusSearchToken: number }>()
const emit = defineEmits<{
  'open-task': [task: Task]
  'open-projects': []
  'open-account': []
  create: []
}>()

const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const searchInput = ref<HTMLInputElement | null>(null)
const query = ref('')
const filterOpen = ref(false)
const scope = ref<'mine' | 'all'>('mine')
const selectedStatuses = ref<Status[]>([])
const collapsed = ref<Record<string, boolean>>({})

const activeProject = computed(() => projectStore.projects.find((item) => item.id === projectStore.activeProjectId))
const username = computed(() => authStore.currentUser?.username || '')
const avatarColor = computed(() => getAvatarColorByUsername(username.value))

const statusOrder: Status[] = ['in_progress', 'in_review', 'todo', 'backlog', 'done', 'canceled', 'duplicate']
const statusLabels: Record<Status, string> = {
  in_progress: '进行中',
  in_review: '待评审',
  todo: '待处理',
  backlog: '待规划',
  done: '已完成',
  canceled: '已取消',
  duplicate: '重复'
}

const visibleTasks = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const currentUserId = authStore.currentUser?.id
  const currentUsername = username.value.trim().toLowerCase()
  return taskStore.tasks.filter((task) => {
    if (task.parentId != null) return false
    if (scope.value === 'mine') {
      const assignedById = currentUserId != null && Number(task.assigneeId) === Number(currentUserId)
      const assignedByName = task.assigneeId == null && task.assigneeDisplayName?.trim().toLowerCase() === currentUsername
      if (!assignedById && !assignedByName) return false
    }
    if (selectedStatuses.value.length && !selectedStatuses.value.includes(task.status)) return false
    if (needle && !`${task.title} ${task.id}`.toLowerCase().includes(needle)) return false
    return true
  })
})

const groups = computed(() => statusOrder
  .map((status) => ({ status, label: statusLabels[status], tasks: visibleTasks.value.filter((task) => task.status === status) }))
  .filter((group) => group.tasks.length > 0))

const filterSummary = computed(() => {
  const owner = scope.value === 'mine' ? '我的任务' : '全部任务'
  if (!selectedStatuses.value.length) return `${owner} · 全部状态`
  if (selectedStatuses.value.length === 1) return `${owner} · ${statusLabels[selectedStatuses.value[0]!]}`
  return `${owner} · ${selectedStatuses.value.length} 个状态`
})

function toggleStatus(status: Status) {
  selectedStatuses.value = selectedStatuses.value.includes(status)
    ? selectedStatuses.value.filter((item) => item !== status)
    : [...selectedStatuses.value, status]
}

function clearFilters() {
  scope.value = 'mine'
  selectedStatuses.value = []
}

watch(() => props.focusSearchToken, async () => {
  await nextTick()
  searchInput.value?.focus()
})
</script>

<template>
  <main class="mobile-home">
    <header class="mobile-topbar">
      <button type="button" class="mobile-project-switch" @click="emit('open-projects')">
        <span>{{ activeProject?.name || '选择项目' }}</span><ChevronDown :size="17" aria-hidden="true" />
      </button>
      <button type="button" class="mobile-avatar-button" aria-label="账户" @click="emit('open-account')">
        <span class="mobile-avatar" :style="avatarColor">{{ getInitials(username || '?') }}</span>
      </button>
    </header>

    <section class="mobile-home-controls" aria-label="任务查找与筛选">
      <label class="mobile-search-field">
        <Search :size="19" aria-hidden="true" />
        <input ref="searchInput" v-model="query" type="search" placeholder="搜索任务" aria-label="搜索任务">
      </label>
      <button type="button" class="mobile-filter-summary" @click="filterOpen = true">
        <SlidersHorizontal :size="18" aria-hidden="true" />
        <span>{{ filterSummary }}</span>
        <ChevronRight :size="18" aria-hidden="true" />
      </button>
    </section>

    <div v-if="taskStore.isLoading && taskStore.tasks.length === 0" class="mobile-loading-list" aria-label="任务加载中">
      <div v-for="n in 5" :key="n" class="mobile-loading-row" />
    </div>

    <MobileEmptyState
      v-else-if="taskStore.error"
      title="任务加载失败"
      description="检查网络连接后重试。"
      action="重新加载"
      @action="taskStore.fetchTasks()"
    />

    <MobileEmptyState
      v-else-if="visibleTasks.length === 0"
      :title="query || selectedStatuses.length ? '没有符合条件的任务' : scope === 'mine' ? '当前项目没有分配给你的任务' : '当前项目还没有任务'"
      :description="query || selectedStatuses.length ? '调整搜索或筛选条件后再试。' : scope === 'mine' ? '可以切换到全部任务，或创建一个新任务。' : '创建任务后，它会出现在这里。'"
      :action="query || selectedStatuses.length ? '清除筛选' : '新建任务'"
      @action="query || selectedStatuses.length ? clearFilters() : emit('create')"
    />

    <section v-else class="mobile-task-groups" aria-label="项目任务">
      <article v-for="group in groups" :key="group.status" class="mobile-task-group">
        <button
          type="button"
          class="mobile-task-group-header"
          :aria-expanded="!collapsed[group.status]"
          @click="collapsed[group.status] = !collapsed[group.status]"
        >
          <span class="mobile-group-title">
            <MobileStatusGlyph :status="group.status" :size="20" />
            <strong>{{ group.label }}</strong><span>{{ group.tasks.length }}</span>
          </span>
          <ChevronDown :size="18" :class="{ 'is-collapsed': collapsed[group.status] }" aria-hidden="true" />
        </button>
        <div v-if="!collapsed[group.status]" class="mobile-task-group-list">
          <MobileTaskRow
            v-for="task in group.tasks"
            :key="task.id"
            :task="task"
            :users="users"
            @open="emit('open-task', $event)"
          />
        </div>
      </article>
    </section>

    <MobileBottomSheet v-model="filterOpen" title="筛选任务">
      <section class="mobile-sheet-section">
        <h3>负责人</h3>
        <div class="mobile-segmented">
          <button type="button" :class="{ active: scope === 'mine' }" @click="scope = 'mine'">我的任务</button>
          <button type="button" :class="{ active: scope === 'all' }" @click="scope = 'all'">全部任务</button>
        </div>
      </section>
      <section class="mobile-sheet-section">
        <h3>状态</h3>
        <div class="mobile-choice-list">
          <button
            v-for="status in statusOrder"
            :key="status"
            type="button"
            :class="{ active: selectedStatuses.includes(status) }"
            @click="toggleStatus(status)"
          >
            <MobileStatusGlyph :status="status" :size="19" />
            <span>{{ statusLabels[status] }}</span>
            <span class="mobile-choice-check" aria-hidden="true">✓</span>
          </button>
        </div>
      </section>
      <button type="button" class="mobile-secondary-button mobile-sheet-reset" @click="clearFilters">恢复默认筛选</button>
    </MobileBottomSheet>
  </main>
</template>
