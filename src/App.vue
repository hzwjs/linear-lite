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
import { Plus, LayoutGrid, List, Settings, Search, MoreVertical, LogOut, Folder, Star } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const favoriteStore = useFavoriteStore()
const overlayStore = useOverlayStore()
const viewModeStore = useViewModeStore()

const createProjectOpen = ref(false)
const settingsProject = ref<Project | null>(null)
const commandPaletteOpen = ref(false)

function openProjectSettings(e: Event, p: Project) {
  e.stopPropagation()
  settingsProject.value = p
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
    label: 'New task',
    keywords: ['new', 'task', 'issue', 'create'],
    icon: Plus,
    run: () => {
      commandPaletteOpen.value = false
      triggerNewTask()
    }
  },
  {
    id: 'view-board',
    label: 'Switch to Board view',
    keywords: ['board', 'view', 'kanban'],
    icon: LayoutGrid,
    run: () => viewModeStore.setView('board')
  },
  {
    id: 'view-list',
    label: 'Switch to List view',
    keywords: ['list', 'view'],
    icon: List,
    run: () => viewModeStore.setView('list')
  },
  {
    id: 'project-settings',
    label: 'Open project settings',
    keywords: ['project', 'settings', 'open'],
    icon: Settings,
    run: openActiveProjectSettings
  },
  {
    id: 'focus-search',
    label: 'Focus search',
    keywords: ['search', 'focus', 'filter'],
    icon: Search,
    run: () => {
      triggerFocusSearch()
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
  document.addEventListener('keydown', onGlobalKeydown)
})
onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <template v-if="isLoginRoute">
    <router-view />
  </template>
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="sidebar-brand-name">Linear Lite</span>
      </div>
      <section v-if="favoriteStore.favorites.length" class="sidebar-section">
        <div class="sidebar-header sidebar-header--static">
          <span class="sidebar-title">Favorites</span>
        </div>
        <nav class="sidebar-nav sidebar-nav--section">
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
      <div class="sidebar-header">
        <span class="sidebar-title">Projects</span>
        <button
          type="button"
          class="sidebar-btn-new"
          title="New project"
          aria-label="New project"
          @click="createProjectOpen = true"
        >
          <Plus class="sidebar-btn-new-icon" />
        </button>
      </div>
      <nav class="sidebar-nav sidebar-nav--projects">
        <button
          v-for="p in projectStore.projects"
          :key="p.id"
          type="button"
          class="sidebar-item"
          :class="{ active: projectStore.activeProjectId === p.id }"
          :title="p.identifier"
          @click="selectProject(p.id)"
        >
          <Folder class="sidebar-item-icon" />
          <span class="sidebar-item-name">{{ p.name }}</span>
          <button
            type="button"
            class="sidebar-item-menu"
            title="Project settings"
            @click="openProjectSettings($event, p)"
          >
            <MoreVertical class="icon-14" />
          </button>
        </button>
      </nav>
      <div class="sidebar-footer">
        <span class="sidebar-user" :title="authStore.currentUser?.username">
          {{ authStore.currentUser?.username ?? '—' }}
        </span>
        <button
          type="button"
          class="sidebar-logout"
          title="Sign out"
          @click="onLogout"
        >
          <LogOut class="icon-14" />
        </button>
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
    />
    <CommandPalette
      :open="commandPaletteOpen"
      :commands="paletteCommands"
      @close="commandPaletteOpen = false"
    />
    <main class="main">
      <div v-if="showEmptyProjects" class="empty-projects">
        <p>No projects yet</p>
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
  padding: 16px 16px 20px;
}
.sidebar-brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}
.sidebar-header {
  padding: 0 16px 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.sidebar-header--static {
  padding-top: 0;
}
.sidebar-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.sidebar-section {
  padding-bottom: 8px;
}
.sidebar-btn-new {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.sidebar-btn-new:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.sidebar-btn-new-icon {
  width: 16px;
  height: 16px;
}
.sidebar-nav {
  overflow-y: auto;
  padding: 0 16px 12px 28px;
}
.sidebar-nav--section {
  padding-bottom: 8px;
}
.sidebar-nav--projects {
  flex: 1;
}
.sidebar-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 6px 0;
  text-align: left;
  color: var(--color-text-primary);
  font-size: 13px;
  transition: background 150ms ease, color 150ms ease;
  gap: 8px;
  border-radius: var(--radius-sm);
}
.sidebar-item:hover {
  background: var(--color-bg-hover);
}
.sidebar-item.active {
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
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
  margin-left: 4px;
  padding: 4px;
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
.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 16px 18px;
  margin-top: auto;
  background: var(--color-bg-subtle);
}
.sidebar-user {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sidebar-logout {
  flex-shrink: 0;
  padding: 4px;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.sidebar-logout:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.sidebar-logout .icon-14 {
  width: 14px;
  height: 14px;
}
.main {
  flex: 1;
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
