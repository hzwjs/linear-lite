<!--
THESIS: Mobile is the current project's focused task-management surface, not a compressed board and not a daily planner.
OWN-WORLD: Bright workspace, white grouped surfaces, slate actions, hairlines, system type, and the PC priority glyphs unchanged.
STORY: Select project, scan own tasks by status, widen to all tasks or filter, then create or open a task and update it full-screen.
FIRST VIEWPORT: Project switcher and account lead; search and one filter summary follow; real status groups start immediately. No redundant page title.
FORM: User-approved composition I, explicitly refined by removing its title/count module and preserving PC priority semantics; user brief supersedes prior seeds.
-->
<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, Check, ListTodo, LogOut, Plus, Search } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '../services/api/project'
import { useAuthStore } from '../store/authStore'
import { useFavoriteStore } from '../store/favoriteStore'
import { useNotificationStore } from '../store/notificationStore'
import { useProjectStore } from '../store/projectStore'
import { useTaskStore } from '../store/taskStore'
import type { Task, User } from '../types/domain'
import { buildTaskRoute, getRouteTaskId } from '../utils/taskRoute'
import MobileBottomSheet from './components/MobileBottomSheet.vue'
import MobileHomeView from './views/MobileHomeView.vue'
import MobileNotificationsView from './views/MobileNotificationsView.vue'
import './mobile.css'

const MobileCreateTaskView = defineAsyncComponent(() => import('./views/MobileCreateTaskView.vue'))
const MobileTaskDetailView = defineAsyncComponent(() => import('./views/MobileTaskDetailView.vue'))

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const favoriteStore = useFavoriteStore()
const notificationStore = useNotificationStore()
const activeTab = ref<'tasks' | 'notifications'>('tasks')
const createOpen = ref(false)
const projectSheetOpen = ref(false)
const accountSheetOpen = ref(false)
const focusSearchToken = ref(0)
const users = ref<User[]>([])
const initialized = ref(false)
const createDirty = ref(false)

const isLogin = computed(() => route.path === '/login')
const routeTaskId = computed(() => getRouteTaskId(route))

async function loadProjectContext() {
  if (projectStore.activeProjectId == null) {
    users.value = []
    return
  }
  await Promise.all([
    taskStore.fetchTasks(),
    projectApi.listMembers(projectStore.activeProjectId).then((list) => { users.value = list }).catch(() => { users.value = [] })
  ])
}

async function initialize() {
  if (!authStore.isLoggedIn || isLogin.value || initialized.value) return
  initialized.value = true
  await projectStore.fetchProjects().catch(() => [])
  await Promise.allSettled([loadProjectContext(), favoriteStore.fetchFavorites(), notificationStore.refreshUnread()])
}

function openTask(task: Task) {
  router.push(buildTaskRoute(task.id, task.projectId ?? projectStore.activeProjectId))
}

function openTaskKey(taskKey: string) {
  router.push(buildTaskRoute(taskKey, projectStore.activeProjectId))
}

function closeDetail() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

function selectProject(projectId: number) {
  if (projectStore.activeProjectId !== projectId) projectStore.setActiveProject(projectId)
  projectSheetOpen.value = false
  if (route.path !== '/') router.push('/')
}

function activateSearch() {
  activeTab.value = 'tasks'
  focusSearchToken.value += 1
}

function openCreate() {
  if (createOpen.value) return
  createDirty.value = false
  createOpen.value = true
  window.history.pushState({ ...window.history.state, mobileCreate: true }, '')
}

function closeCreate() {
  if (createDirty.value && !window.confirm('放弃当前编辑内容？')) return
  createDirty.value = false
  if (window.history.state?.mobileCreate) window.history.back()
  else createOpen.value = false
}

function onPopState() {
  if (!createOpen.value) return
  if (createDirty.value && !window.confirm('放弃当前编辑内容？')) {
    window.history.pushState({ ...window.history.state, mobileCreate: true }, '')
    return
  }
  createDirty.value = false
  createOpen.value = false
}

function onCreated(task: Task) {
  const historyState = { ...(window.history.state || {}) }
  delete historyState.mobileCreate
  window.history.replaceState(historyState, '')
  createDirty.value = false
  createOpen.value = false
  openTask(task)
}

function logout() {
  accountSheetOpen.value = false
  authStore.logout()
  router.push('/login')
}

watch(() => projectStore.activeProjectId, async (next, previous) => {
  if (!initialized.value || next == null || next === previous) return
  await loadProjectContext()
})

watch(() => route.path, (path) => {
  if (path === '/analytics') router.replace('/')
})

onMounted(() => {
  window.addEventListener('popstate', onPopState)
  void initialize()
})
onBeforeUnmount(() => window.removeEventListener('popstate', onPopState))
</script>

<template>
  <router-view v-if="isLogin" />
  <div v-else class="mobile-app">
    <div class="mobile-shell">
      <Suspense>
        <MobileTaskDetailView
          v-if="routeTaskId"
          :task-id="routeTaskId"
          @back="closeDetail"
          @open-task="openTask"
        />
        <MobileCreateTaskView v-else-if="createOpen" @close="closeCreate" @created="onCreated" @dirty="createDirty = $event" />
        <template v-else>
          <MobileHomeView
            v-if="activeTab === 'tasks'"
            :users="users"
            :focus-search-token="focusSearchToken"
            @open-task="openTask"
            @open-projects="projectSheetOpen = true"
            @open-account="accountSheetOpen = true"
            @create="openCreate"
          />
          <MobileNotificationsView v-else @open="openTaskKey" />

          <nav class="mobile-tabbar" aria-label="主要导航">
            <button type="button" :class="{ active: activeTab === 'tasks' }" @click="activeTab = 'tasks'">
              <ListTodo :size="21" /><span>任务</span>
            </button>
            <button type="button" @click="activateSearch"><Search :size="21" /><span>搜索</span></button>
            <button type="button" class="mobile-create-tab" aria-label="新建任务" @click="openCreate"><Plus :size="27" /></button>
            <button type="button" :class="{ active: activeTab === 'notifications' }" @click="activeTab = 'notifications'">
              <span class="mobile-tab-icon"><Bell :size="21" /><i v-if="notificationStore.unreadCount">{{ notificationStore.unreadCount > 9 ? '9+' : notificationStore.unreadCount }}</i></span>
              <span>通知</span>
            </button>
          </nav>
        </template>
      </Suspense>
    </div>

    <MobileBottomSheet v-model="projectSheetOpen" title="切换项目">
      <div class="mobile-project-list">
        <button v-for="project in projectStore.projects" :key="project.id" type="button" @click="selectProject(project.id)">
          <span class="mobile-project-mark">{{ project.identifier.slice(0, 2) }}</span>
          <span><strong>{{ project.name }}</strong><small>{{ project.identifier }}</small></span>
          <Check v-if="project.id === projectStore.activeProjectId" :size="19" />
        </button>
      </div>
    </MobileBottomSheet>

    <MobileBottomSheet v-model="accountSheetOpen" title="账户">
      <div class="mobile-account-summary"><strong>{{ authStore.currentUser?.username }}</strong><span>Linear Lite</span></div>
      <button type="button" class="mobile-danger-row" @click="logout"><LogOut :size="19" />退出登录</button>
    </MobileBottomSheet>
  </div>
</template>
