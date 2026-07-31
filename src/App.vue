<script setup lang="ts">
import { computed, watch, onMounted, onUnmounted, ref, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './store/authStore'
import { useProjectStore } from './store/projectStore'
import { useTaskStore } from './store/taskStore'
import { useFavoriteStore } from './store/favoriteStore'
import { useOverlayStore } from './store/overlayStore'
import { useViewModeStore } from './store/viewModeStore'
import NotificationCenter from './components/NotificationCenter.vue'
import SidebarNavigation from './components/SidebarNavigation.vue'
import CreateProjectModal from './components/CreateProjectModal.vue'
import CommandPalette from './components/CommandPalette.vue'
import GlobalSearchModal from './components/GlobalSearchModal.vue'
import type { CommandItem } from './components/CommandPalette.vue'
import type { ProjectContentSearchResult } from './types/search'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from './store/localeStore'
import { useNotificationStore } from './store/notificationStore'
import { useIssuePanelStore } from './store/issuePanelStore'
import { buildTaskRoute, getRouteTaskId } from './utils/taskRoute'
import { preloadTaskDetail } from './utils/taskDetailPreload'
import {
  Plus,
  LayoutGrid,
  List,
  Settings,
  Search,
  PanelLeft
} from 'lucide-vue-next'

const SIDEBAR_HIDDEN_KEY = 'linear-lite.sidebarHidden'
const SIDEBAR_COLLAPSED_KEY = 'linear-lite.sidebarCollapsed'

function readSidebarCollapsed(): { favorites: boolean; projects: boolean } {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return { favorites: false, projects: false }
  }
  try {
    const raw = window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY)
    if (!raw) return { favorites: false, projects: false }
    return JSON.parse(raw)
  } catch {
    return { favorites: false, projects: false }
  }
}

function persistSidebarCollapsed(collapsed: { favorites: boolean; projects: boolean }) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return
  window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, JSON.stringify(collapsed))
}

function readSidebarHidden(): boolean {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return false
  return window.localStorage.getItem(SIDEBAR_HIDDEN_KEY) === '1'
}

function persistSidebarHidden(hidden: boolean) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return
  window.localStorage.setItem(SIDEBAR_HIDDEN_KEY, hidden ? '1' : '0')
}

const route = useRoute()
/** 深链任务页：主列需允许块编辑器左侧 chrome 溢出，不再用 overflow:hidden 裁切 */
const isTaskWorkspaceRoute = computed(
  () => getRouteTaskId(route) != null
)
const routeTaskId = computed(() => getRouteTaskId(route))
const router = useRouter()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const favoriteStore = useFavoriteStore()
const overlayStore = useOverlayStore()
const viewModeStore = useViewModeStore()
const localeStore = useLocaleStore()
useNotificationStore()
const issuePanelStore = useIssuePanelStore()
const { t } = useI18n()

const createProjectOpen = ref(false)
const commandPaletteOpen = ref(false)
const globalSearchOpen = ref(false)
const sidebarHidden = ref(false)
const sidebarCollapsed = ref(readSidebarCollapsed())

const userInitial = computed(() => {
  const name = authStore.currentUser?.username
  if (!name) return '?'
  return name.charAt(0).toUpperCase()
})

function toggleSidebarHidden() {
  sidebarHidden.value = !sidebarHidden.value
}

function toggleFavoritesCollapsed() {
  sidebarCollapsed.value = { ...sidebarCollapsed.value, favorites: !sidebarCollapsed.value.favorites }
  persistSidebarCollapsed(sidebarCollapsed.value)
}

function toggleProjectsCollapsed() {
  sidebarCollapsed.value = { ...sidebarCollapsed.value, projects: !sidebarCollapsed.value.projects }
  persistSidebarCollapsed(sidebarCollapsed.value)
}

function openProjectSettings(projectId: number) {
  const project = projectStore.projects.find((item) => item.id === projectId)
  if (project) void router.push(`/projects/${project.id}/settings`)
}

function onLogout() {
  authStore.logout()
  router.push('/login')
}

function openActiveProjectSettings() {
  const id = projectStore.activeProjectId
  if (id == null) return
  const p = projectStore.projects.find((x) => x.id === id)
  if (p) void router.push(`/projects/${p.id}/settings`)
}

function triggerNewTask() {
  window.dispatchEvent(new CustomEvent('command-palette:new-task'))
}

function triggerFocusSearch() {
  commandPaletteOpen.value = false
  globalSearchOpen.value = true
}

async function openGlobalSearchResult(result: ProjectContentSearchResult) {
  globalSearchOpen.value = false
  projectStore.setActiveProject(result.projectId)
  if (result.contentType === 'document') {
    await router.push(`/projects/${result.projectId}/documents/${result.resourceId}`)
    return
  }
  await taskStore.fetchTasks()
  await router.push(buildTaskRoute(result.resourceId, result.projectId))
}

const paletteCommands = computed<CommandItem[]>(() => [
  {
    id: 'new-task',
    label: t('command.newTask'),
    keywords: ['new', 'task', 'issue', 'create'],
    icon: Plus,
    run: () => {
      commandPaletteOpen.value = false
      triggerNewTask()
    }
  },
  {
    id: 'view-board',
    label: t('command.viewBoard'),
    keywords: ['board', 'view', 'kanban'],
    icon: LayoutGrid,
    run: () => viewModeStore.setView('board')
  },
  {
    id: 'view-list',
    label: t('command.viewList'),
    keywords: ['list', 'view'],
    icon: List,
    run: () => viewModeStore.setView('list')
  },
  {
    id: 'project-settings',
    label: t('command.projectSettings'),
    keywords: ['project', 'settings', 'open'],
    icon: Settings,
    run: openActiveProjectSettings
  },
  {
    id: 'focus-search',
    label: t('command.focusSearch'),
    keywords: ['search', 'focus', 'filter'],
    icon: Search,
    run: () => {
      triggerFocusSearch()
    }
  },
  {
    id: 'toggle-sidebar',
    label: t('command.toggleSidebar'),
    keywords: ['sidebar', 'hide', 'show', 'panel', 'navigation'],
    icon: PanelLeft,
    run: () => {
      commandPaletteOpen.value = false
      toggleSidebarHidden()
    }
  }
])

const isLoginRoute = computed(() => route.path === '/login')

function ensureProjects() {
  if (authStore.isLoggedIn && !isLoginRoute.value) {
    projectStore.fetchProjects()
    favoriteStore.fetchFavorites()
  }
}

onMounted(ensureProjects)
watch([() => route.path, () => authStore.isLoggedIn], ensureProjects, { immediate: true })

/** 进入登录页时清空主布局浮层状态；Pinia/App ref 在路由切换后仍保留，否则会随重新登录再次打开。 */
watch(
  () => route.path,
  (path) => {
    if (path !== '/login') return
    createProjectOpen.value = false
    commandPaletteOpen.value = false
    globalSearchOpen.value = false
    issuePanelStore.closeComposer()
    issuePanelStore.closeWorkspace()
    issuePanelStore.setSelectedTask(null)
  }
)

function selectProject(id: number) {
  projectStore.setActiveProject(id)
  taskStore.fetchTasks()
  if (route.path !== '/') {
    router.push('/')
  }
}

function openProjectDocuments(id: number) {
  projectStore.setActiveProject(id)
  void router.push(`/projects/${id}/documents`)
}

async function reorderProjects(projectIds: number[]) {
  try {
    await projectStore.reorderProjects(projectIds)
  } catch (error) {
    console.error('Failed to reorder projects:', error)
  }
}

async function openFavoriteTask(taskId: string, projectId?: number) {
  const targetProjectId = projectId ?? projectStore.activeProjectId
  const task = taskStore.tasks.find((item) => item.id === taskId)
    ?? await taskStore.fetchTaskByKey(taskId)
  try {
    await preloadTaskDetail(task, (parentNumericId) => taskStore.fetchSubIssues(parentNumericId, targetProjectId))
  } catch (error) {
    console.error(`Failed to preload favorite task detail ${taskId}:`, error)
  }
  taskStore.currentTaskId = taskId
  await router.push(buildTaskRoute(taskId, targetProjectId))
  // Switch the project only after the detail surface has replaced the large task list.
  await nextTick()
  if (targetProjectId != null && projectStore.activeProjectId !== targetProjectId) {
    projectStore.setActiveProject(targetProjectId)
  }
}

const showEmptyProjects = computed(
  () =>
    !isLoginRoute.value &&
    authStore.isLoggedIn &&
    projectStore.projects.length === 0
)

// 有项目但未选中任一：右侧显示「请选择项目」，避免空白
const showSelectProject = computed(
  () =>
    !isLoginRoute.value &&
    authStore.isLoggedIn &&
    projectStore.projects.length > 0 &&
    projectStore.activeProjectId == null
)

// 有项目列表时若当前未选中任一，自动选中第一项，避免主内容区长期处于「请选择项目」
watch(
  () =>
    projectStore.projects.length > 0 && projectStore.activeProjectId == null,
  (needsSelection) => {
    if (needsSelection && projectStore.projects[0]) {
      projectStore.setActiveProject(projectStore.projects[0].id)
      taskStore.fetchTasks()
    }
  },
  { immediate: true }
)
watch(globalSearchOpen, (open) => {
  if (open) {
    overlayStore.push('global-search-modal', () => {
      globalSearchOpen.value = false
    })
  } else {
    overlayStore.remove('global-search-modal')
  }
})

// P4-7.4: 浮层注册，供 Esc 关闭
watch(createProjectOpen, (open) => {
  if (open) {
    overlayStore.push('create-project-modal', () => {
      createProjectOpen.value = false
    })
  } else {
    overlayStore.remove('create-project-modal')
  }
})

// P4-7.1 / P4-7.3 / P4-7.4: 全局快捷键 ⌘/Ctrl+K、C、Esc
function isInputElement(el: EventTarget | null): boolean {
  if (!el || !(el instanceof HTMLElement)) return false
  const tag = el.tagName.toLowerCase()
  return tag === 'input' || tag === 'textarea' || (el as HTMLElement).isContentEditable
}

function onGlobalKeydown(e: KeyboardEvent) {
  if (isLoginRoute.value) return
  if (e.key === 'Escape') {
    overlayStore.popAndClose()
    return
  }
  if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    // 搜索属于 App 壳层能力；快捷键始终执行“打开”，连续触发不能反向关闭搜索。
    triggerFocusSearch()
    return
  }
  if (e.key === 'c' && !e.metaKey && !e.ctrlKey && !e.altKey && !isInputElement(e.target)) {
    e.preventDefault()
    if (commandPaletteOpen.value) commandPaletteOpen.value = false
    triggerNewTask()
  }
}

onMounted(() => {
  sidebarHidden.value = readSidebarHidden()
  document.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('global-search:open', triggerFocusSearch)
})
watch(sidebarHidden, persistSidebarHidden)
onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('global-search:open', triggerFocusSearch)
})
</script>

<template>
  <template v-if="isLoginRoute">
    <router-view />
  </template>
  <div v-else class="app-layout" :class="{ 'app-layout--task-workspace': isTaskWorkspaceRoute }">
    <SidebarNavigation
      :hidden="sidebarHidden"
      :user-name="authStore.currentUser?.username"
      :user-initial="userInitial"
      :locale="localeStore.locale"
      :favorites-collapsed="sidebarCollapsed.favorites"
      :projects-collapsed="sidebarCollapsed.projects"
      :favorites="favoriteStore.favorites"
      :projects="projectStore.projects"
      :route-path="route.path"
      :route-task-id="routeTaskId"
      :active-project-id="projectStore.activeProjectId"
      @show-sidebar="sidebarHidden = false"
      @hide-sidebar="sidebarHidden = true"
      @focus-search="triggerFocusSearch"
      @set-locale="localeStore.setLocale"
      @logout="onLogout"
      @toggle-favorites-collapsed="toggleFavoritesCollapsed"
      @open-favorite-task="openFavoriteTask"
      @open-analytics="router.push('/analytics')"
      @toggle-projects-collapsed="toggleProjectsCollapsed"
      @reorder-projects="reorderProjects"
      @create-project="createProjectOpen = true"
      @select-project="selectProject"
      @open-project-documents="openProjectDocuments"
      @open-project-settings="openProjectSettings"
    >
      <template #notification>
          <NotificationCenter />
      </template>
    </SidebarNavigation>
    <CreateProjectModal
      :open="createProjectOpen"
      @close="createProjectOpen = false"
      @created="() => {}"
    />
    <CommandPalette
      :open="commandPaletteOpen"
      :commands="paletteCommands"
      @close="commandPaletteOpen = false"
    />
    <GlobalSearchModal
      :open="globalSearchOpen"
      @close="globalSearchOpen = false"
      @select="openGlobalSearchResult"
    />
    <main class="main" :class="{ 'main--task-workspace': isTaskWorkspaceRoute }">
      <div v-if="showEmptyProjects" class="empty-projects">
        <p>{{ t('emptyState.noProjects') }}</p>
      </div>
      <div v-else-if="showSelectProject" class="empty-projects">
        <p>{{ t('emptyState.selectProject') }}</p>
      </div>
      <router-view v-else />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
/* 与 main--task-workspace 配套：深链任务页允许主列内块编辑器 chrome 溢出到根壳外缘 */
.app-layout--task-workspace {
  overflow: visible;
}
.main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.main--task-workspace {
  overflow: visible;
}
.empty-projects {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  font-size: 14px;
}
</style>

<!-- P4-6.2: 视图切换（Board/List）150ms 动效，仅样式不改业务 -->
<style>
.view-toggle button {
  transition: background-color 150ms ease, color 150ms ease, border-color 150ms ease;
}
</style>
