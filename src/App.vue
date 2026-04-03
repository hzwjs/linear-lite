<script setup lang="ts">
import { computed, watch, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './store/authStore'
import { useProjectStore } from './store/projectStore'
import { useTaskStore } from './store/taskStore'
import { useFavoriteStore } from './store/favoriteStore'
import { useOverlayStore } from './store/overlayStore'
import { useViewModeStore } from './store/viewModeStore'
import CreateProjectModal from './components/CreateProjectModal.vue'
import ProjectSettingsModal from './components/ProjectSettingsModal.vue'
import CommandPalette from './components/CommandPalette.vue'
import type { CommandItem } from './components/CommandPalette.vue'
import type { Project } from './types/domain'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from './store/localeStore'
import {
  Plus,
  LayoutGrid,
  List,
  Settings,
  Search,
  MoreVertical,
  LogOut,
  Folder,
  Star,
  PanelLeft,
  PanelLeftClose,
  PanelLeftOpen,
  BarChart3,
  ChevronDown,
  ChevronRight
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
const router = useRouter()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const favoriteStore = useFavoriteStore()
const overlayStore = useOverlayStore()
const viewModeStore = useViewModeStore()
const localeStore = useLocaleStore()
const { t } = useI18n()

const createProjectOpen = ref(false)
const settingsProject = ref<Project | null>(null)
const commandPaletteOpen = ref(false)
const sidebarHidden = ref(false)
const sidebarCollapsed = ref(readSidebarCollapsed())
const userMenuOpen = ref(false)
const userMenuRef = ref<HTMLElement | null>(null)

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
}

function closeUserMenu() {
  userMenuOpen.value = false
}

function onClickOutsideUserMenu(event: MouseEvent) {
  const el = userMenuRef.value
  if (!el) return
  if (!el.contains(event.target as Node)) {
    closeUserMenu()
  }
}

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

function openProjectSettings(e: Event, p: Project) {
  e.stopPropagation()
  settingsProject.value = p
}

function handleProjectDeleted() {
  settingsProject.value = null
  favoriteStore.fetchFavorites()
  router.push('/')
}

function onLogout() {
  authStore.logout()
  router.push('/login')
}

function openActiveProjectSettings() {
  const id = projectStore.activeProjectId
  if (id == null) return
  const p = projectStore.projects.find((x) => x.id === id)
  if (p) settingsProject.value = p
}

function triggerNewTask() {
  window.dispatchEvent(new CustomEvent('command-palette:new-task'))
}

function triggerFocusSearch() {
  window.dispatchEvent(new CustomEvent('command-palette:focus-search'))
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

function selectProject(id: number) {
  projectStore.setActiveProject(id)
  taskStore.fetchTasks()
  if (route.path !== '/') {
    router.push('/')
  }
}

async function openFavoriteTask(taskId: string, projectId?: number) {
  if (projectId != null && projectStore.activeProjectId !== projectId) {
    projectStore.setActiveProject(projectId)
    await taskStore.fetchTasks()
  }
  router.push(`/tasks/${taskId}`)
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
watch(
  () => settingsProject.value != null,
  (open) => {
    if (open) {
      overlayStore.push('project-settings-modal', () => {
        settingsProject.value = null
      })
    } else {
      overlayStore.remove('project-settings-modal')
    }
  }
)

// P4-7.1 / P4-7.3 / P4-7.4: 全局快捷键 ⌘K、C、Esc
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
    commandPaletteOpen.value = !commandPaletteOpen.value
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
  document.addEventListener('click', onClickOutsideUserMenu, true)
})
watch(sidebarHidden, persistSidebarHidden)
onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('click', onClickOutsideUserMenu, true)
})
</script>

<template>
  <template v-if="isLoginRoute">
    <router-view />
  </template>
  <div v-else class="app-layout">
    <button
      v-if="sidebarHidden"
      type="button"
      class="sidebar-reopen"
      :title="t('sidebar.showSidebar')"
      :aria-label="t('sidebar.showSidebar')"
      @click="sidebarHidden = false"
    >
      <PanelLeftOpen class="sidebar-reopen-icon" />
    </button>
    <aside v-show="!sidebarHidden" class="sidebar">
      <div ref="userMenuRef" class="sidebar-brand">
        <button
          type="button"
          class="user-avatar-btn"
          :class="{ active: userMenuOpen }"
          :title="authStore.currentUser?.username"
          @click="toggleUserMenu"
        >
          {{ userInitial }}
        </button>
        <span class="sidebar-brand-name">{{ t('app.name') }}</span>
        <div class="sidebar-brand-actions">
          <button
            type="button"
            class="sidebar-icon-btn"
            :title="t('sidebar.search')"
            :aria-label="t('sidebar.search')"
            @click="triggerFocusSearch"
          >
            <Search class="sidebar-icon-btn-icon" />
          </button>
          <button
            type="button"
            class="sidebar-icon-btn"
            :title="t('sidebar.hideSidebar')"
            :aria-label="t('sidebar.hideSidebar')"
            @click="sidebarHidden = true"
          >
            <PanelLeftClose class="sidebar-icon-btn-icon" />
          </button>
        </div>
        <div v-show="userMenuOpen" class="user-menu">
          <div class="user-menu-header">
            <span class="user-menu-name">{{ authStore.currentUser?.username ?? '—' }}</span>
          </div>
          <div class="user-menu-divider" />
          <div class="user-menu-section">
            <span class="user-menu-label">{{ t('common.language') }}</span>
            <div class="locale-switcher">
              <button
                type="button"
                class="locale-pill"
                :class="{ 'locale-pill--active': localeStore.locale === 'zh-CN' }"
                @click="localeStore.setLocale('zh-CN')"
              >
                ZH
              </button>
              <button
                type="button"
                class="locale-pill"
                :class="{ 'locale-pill--active': localeStore.locale === 'en' }"
                @click="localeStore.setLocale('en')"
              >
                EN
              </button>
            </div>
          </div>
          <div class="user-menu-divider" />
          <button type="button" class="user-menu-item user-menu-item--danger" @click="onLogout">
            <LogOut class="user-menu-item-icon" />
            <span>{{ t('sidebar.signOut') }}</span>
          </button>
        </div>
      </div>

      <div class="sidebar-content">
        <section v-if="favoriteStore.favorites.length" class="sidebar-group">
          <button type="button" class="sidebar-group-header" @click="toggleFavoritesCollapsed">
            <ChevronDown v-if="!sidebarCollapsed.favorites" class="sidebar-group-chevron" />
            <ChevronRight v-else class="sidebar-group-chevron" />
            <span class="sidebar-group-title">{{ t('sidebar.favorites') }}</span>
          </button>
          <nav v-show="!sidebarCollapsed.favorites" class="sidebar-group-nav">
            <button
              v-for="task in favoriteStore.favorites"
              :key="task.id"
              type="button"
              class="sidebar-item"
              :class="{ active: route.params.taskId === task.id }"
              @click="openFavoriteTask(task.id, task.projectId)"
            >
              <Star class="sidebar-item-icon sidebar-item-icon--favorite" />
              <span class="sidebar-item-name">{{ task.title }}</span>
            </button>
          </nav>
        </section>

        <section class="sidebar-group">
          <div class="sidebar-group-header sidebar-group-header--static">
            <span class="sidebar-group-title">{{ t('sidebar.workspace') }}</span>
          </div>
          <nav class="sidebar-group-nav">
            <button
              type="button"
              class="sidebar-item"
              :class="{ active: route.path === '/analytics' }"
              data-testid="sidebar-analytics"
              @click="router.push('/analytics')"
            >
              <BarChart3 class="sidebar-item-icon" />
              <span class="sidebar-item-name">{{ t('sidebar.analytics') }}</span>
            </button>
          </nav>
        </section>

        <section class="sidebar-group sidebar-group--projects">
          <div
            role="button"
            tabindex="0"
            class="sidebar-group-header"
            @click="toggleProjectsCollapsed"
            @keydown.enter="toggleProjectsCollapsed"
            @keydown.space.prevent="toggleProjectsCollapsed"
          >
            <ChevronDown v-if="!sidebarCollapsed.projects" class="sidebar-group-chevron" />
            <ChevronRight v-else class="sidebar-group-chevron" />
            <span class="sidebar-group-title">{{ t('sidebar.projects') }}</span>
            <button
              type="button"
              class="sidebar-group-action"
              :title="t('sidebar.newProjectTitle')"
              :aria-label="t('sidebar.newProjectTitle')"
              @click.stop="createProjectOpen = true"
            >
              <Plus class="sidebar-group-action-icon" />
            </button>
          </div>
          <nav v-show="!sidebarCollapsed.projects" class="sidebar-group-nav sidebar-group-nav--nested">
            <div
              v-for="p in projectStore.projects"
              :key="p.id"
              role="button"
              tabindex="0"
              class="sidebar-item"
              :class="{ active: projectStore.activeProjectId === p.id }"
              :title="p.identifier"
              @click="selectProject(p.id)"
              @keydown.enter="selectProject(p.id)"
              @keydown.space.prevent="selectProject(p.id)"
            >
              <Folder class="sidebar-item-icon" />
              <span class="sidebar-item-name">{{ p.name }}</span>
              <button
                type="button"
                class="sidebar-item-menu"
                :title="t('sidebar.projectSettings')"
                @click="openProjectSettings($event, p)"
              >
                <MoreVertical class="icon-14" />
              </button>
            </div>
          </nav>
        </section>
      </div>
    </aside>
    <CreateProjectModal
      :open="createProjectOpen"
      @close="createProjectOpen = false"
      @created="() => {}"
    />
    <ProjectSettingsModal
      :open="settingsProject != null"
      :project="settingsProject"
      @close="settingsProject = null"
      @updated="() => {}"
      @deleted="handleProjectDeleted"
    />
    <CommandPalette
      :open="commandPaletteOpen"
      :commands="paletteCommands"
      @close="commandPaletteOpen = false"
    />
    <main class="main">
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
.sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--color-bg-secondary);
  display: flex;
  flex-direction: column;
}
.sidebar-brand {
  position: relative;
  padding: 12px 12px 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.sidebar-brand-name {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}
.sidebar-brand-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
.sidebar-icon-btn {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.sidebar-icon-btn:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.sidebar-icon-btn-icon {
  width: 16px;
  height: 16px;
}
.sidebar-reopen {
  flex-shrink: 0;
  align-self: stretch;
  width: 40px;
  min-height: 100%;
  margin: 0;
  padding: 14px 0 0;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  background: var(--color-bg-secondary);
  border: none;
  border-right: 1px solid var(--color-border-subtle);
  cursor: pointer;
  color: var(--color-text-muted);
  transition: color var(--transition-fast), background var(--transition-fast);
}
.sidebar-reopen:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.sidebar-reopen-icon {
  width: 20px;
  height: 20px;
}
.sidebar-content {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}
.sidebar-group {
  margin-bottom: 4px;
}
.sidebar-group--projects {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.sidebar-group-header {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 6px 8px;
  gap: 4px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
}
.sidebar-group-header:hover {
  background: var(--color-bg-hover);
}
.sidebar-group-header--static {
  cursor: default;
  padding: 10px 8px 6px;
}
.sidebar-group-header--static:hover {
  background: transparent;
}
.sidebar-group-chevron {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  color: var(--color-text-muted);
}
.sidebar-group-title {
  flex: 1;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-muted);
  text-align: left;
}
.sidebar-group-action {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.sidebar-group-header:hover .sidebar-group-action {
  opacity: 1;
}
.sidebar-group-action:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-active);
}
.sidebar-group-action-icon {
  width: 14px;
  height: 14px;
}
.sidebar-group-nav {
  padding: 2px 0 4px 18px;
}
.sidebar-group-nav--nested {
  padding-left: 26px;
}
.sidebar-item {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 5px 8px;
  gap: 8px;
  text-align: left;
  color: var(--color-text-secondary);
  font-size: 13px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.sidebar-item:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.sidebar-item.active {
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-weight: 500;
}
.sidebar-item-icon {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: var(--color-text-muted);
}
.sidebar-item.active .sidebar-item-icon {
  color: var(--color-text-secondary);
}
.sidebar-item-icon--favorite {
  color: #d4a106;
}
.sidebar-item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sidebar-item-menu {
  flex-shrink: 0;
  margin-left: auto;
  padding: 3px;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}
.sidebar-item:hover .sidebar-item-menu {
  opacity: 1;
}
.sidebar-item-menu:hover {
  background: var(--color-bg-active);
  color: var(--color-text-primary);
}
.sidebar-item .icon-14 {
  width: 14px;
  height: 14px;
}
.user-avatar-btn {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: #7c3aed;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: opacity var(--transition-fast);
}
.user-avatar-btn:hover,
.user-avatar-btn.active {
  opacity: 0.85;
}
.user-menu {
  position: absolute;
  left: 8px;
  top: 44px;
  width: 200px;
  padding: 6px;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12), 0 1px 3px rgba(0, 0, 0, 0.08);
  z-index: 100;
}
.user-menu-header {
  padding: 8px 10px;
}
.user-menu-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-primary);
}
.user-menu-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--color-border-subtle);
}
.user-menu-section {
  padding: 8px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.user-menu-label {
  font-size: 12px;
  color: var(--color-text-muted);
}
.locale-switcher {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.locale-pill {
  border: 1px solid var(--color-border);
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  border-radius: 4px;
  padding: 3px 8px;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
}
.locale-pill:hover {
  border-color: var(--color-border-strong);
  color: var(--color-text-primary);
}
.locale-pill--active {
  background: var(--color-bg-elevated);
  border-color: var(--color-accent);
  color: var(--color-text-primary);
}
.user-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.user-menu-item:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.user-menu-item--danger:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}
.user-menu-item-icon {
  width: 14px;
  height: 14px;
}
.main {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
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
