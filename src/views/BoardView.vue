<script setup lang="ts">
import { onMounted, computed, watch, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import TaskCard from '../components/TaskCard.vue'
import TaskEditor from '../components/TaskEditor.vue'
import TaskListView from '../components/TaskListView.vue'
import { getStoredView, setStoredView } from '../utils/viewPreference'
import { userApi } from '../services/api/user'
import type { User } from '../types/domain'

const store = useTaskStore()
const projectStore = useProjectStore()
const route = useRoute()
const router = useRouter()
const users = ref<User[]>([])

// UI state for the editor
const isEditorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')

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

// Deep link handling
watch(() => route.params.taskId, (newId) => {
  if (newId) {
    store.currentTaskId = newId as string
    editorMode.value = 'edit'
    isEditorOpen.value = true
  } else {
    isEditorOpen.value = false
    store.currentTaskId = null
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

const openCreateEditor = () => {
  store.currentTaskId = null
  editorMode.value = 'create'
  router.push('/') // clear deep link
  isEditorOpen.value = true
}

const openEditEditor = (task: any) => {
  router.push(`/tasks/${task.id}`)
}

const closeEditor = () => {
  router.push('/')
  isEditorOpen.value = false
}

const viewType = ref<'board' | 'list'>(getStoredView())
function setView(v: 'board' | 'list') {
  viewType.value = v
  setStoredView(v)
}

// Columns definition for the board
const columns = [
  { id: 'todo', title: 'Todo' },
  { id: 'in_progress', title: 'In Progress' },
  { id: 'done', title: 'Done' }
] as const
</script>

<template>
  <div class="board-view">
    <header class="app-header">
      <div class="header-left">
        <h1>Linear Lite</h1>
        <button class="btn-create" @click="openCreateEditor">New Issue</button>
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
          v-model="searchQuery" 
          placeholder="Search issues..." 
          class="search-input"
        />
        <select v-model="filterStatus" class="filter-select">
          <option :value="null">All Status</option>
          <option value="todo">Todo</option>
          <option value="in_progress">In Progress</option>
          <option value="done">Done</option>
        </select>
        <select v-model="filterPriority" class="filter-select">
          <option :value="null">All Priorities</option>
          <option value="urgent">Urgent</option>
          <option value="high">High</option>
          <option value="medium">Medium</option>
          <option value="low">Low</option>
        </select>
      </div>
    </header>

    <main class="board-content">
      <!-- Error State -->
      <div v-if="store.error" class="error-state">
        <p>{{ store.error }}</p>
        <button class="btn-retry" @click="store.fetchTasks()">Retry</button>
      </div>
      
      <!-- Loading State -->
      <div v-else-if="store.isLoading && store.tasks.length === 0" class="loading-state">
        <p>Loading tasks...</p>
      </div>
      
      <!-- Empty State / No Tasks at all -->
      <div v-else-if="store.isEmpty" class="empty-state">
        <p>You don't have any tasks yet.</p>
        <button class="btn-create" @click="openCreateEditor">Create your first task</button>
      </div>

      <!-- Filter Empty State -->
      <div v-else-if="store.isFilterEmpty" class="empty-state">
        <p>No tasks match your filters.</p>
        <button class="btn-text" @click="searchQuery = ''; filterStatus = null; filterPriority = null">Clear filters</button>
      </div>
      
      <!-- Board Columns or List -->
      <div v-else-if="viewType === 'board'" class="board-columns">
        <div v-for="col in columns" :key="col.id" class="column">
          <div class="column-header">
            <h3>{{ col.title }} <span>{{ store.groupedTasks[col.id].length }}</span></h3>
          </div>
          <div class="column-list">
            <TaskCard 
              v-for="task in store.groupedTasks[col.id]" 
              :key="task.id" 
              :task="task" 
              :users="users"
              @click="openEditEditor"
              @transition="(id, status) => store.transitionTask(id, status)"
            />
          </div>
        </div>
      </div>
      <div v-else class="list-wrap">
        <TaskListView
          :tasks="store.filteredTasks"
          :users="users"
          @row-click="openEditEditor"
        />
      </div>
    </main>

    <!-- Drawer Editor -->
    <TaskEditor 
      v-if="isEditorOpen"
      :mode="editorMode"
      :task="store.currentTask"
      @close="closeEditor"
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
  height: var(--header-height);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 24px;
}
.header-left h1 {
  font-size: 16px;
  font-weight: 500;
  margin: 0;
}
.btn-create {
  background: var(--color-accent);
  color: white;
  padding: 6px 12px;
  border-radius: var(--border-radius-sm);
  font-size: 13px;
  font-weight: 500;
  transition: background var(--transition-fast);
}
.btn-create:hover { background: var(--color-accent-hover); }

.header-controls {
  display: flex;
  gap: 12px;
}
.search-input, .filter-select {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 6px 12px;
  border-radius: var(--border-radius-sm);
  font-size: 13px;
}

.board-content {
  flex: 1;
  padding: 24px 32px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.view-toggle {
  display: flex;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  overflow: hidden;
}
.toggle-btn {
  padding: 6px 12px;
  font-size: 13px;
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
}
.board-columns {
  display: flex;
  gap: 24px;
  height: 100%;
}
.column {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: rgba(255,255,255,0.02);
  border-radius: var(--border-radius-lg);
  padding: 16px;
}
.column-header {
  margin-bottom: 16px;
}
.column-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.column-header span {
  font-size: 12px;
  background: var(--color-bg-elevated);
  padding: 2px 6px;
  border-radius: 12px;
}
.column-list {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: none;
}
.column-list::-webkit-scrollbar { display: none; }

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
</style>
