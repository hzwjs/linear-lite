<script setup lang="ts">
import { computed, watch, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './store/authStore'
import { useProjectStore } from './store/projectStore'
import { useTaskStore } from './store/taskStore'
import { useOverlayStore } from './store/overlayStore'
import { useViewModeStore } from './store/viewModeStore'
import CreateProjectModal from './components/CreateProjectModal.vue'
import ProjectSettingsModal from './components/ProjectSettingsModal.vue'
import CommandPalette from './components/CommandPalette.vue'
import type { CommandItem } from './components/CommandPalette.vue'
import type { Project } from './types/domain'
import { Plus, LayoutGrid, List, Settings, Search } from 'lucide-vue-next'

const route = useRoute()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()
const overlayStore = useOverlayStore()
const viewModeStore = useViewModeStore()

const createProjectOpen = ref(false)
const settingsProject = ref<Project | null>(null)
const commandPaletteOpen = ref(false)

function openProjectSettings(e: Event, p: Project) {
  e.stopPropagation()
  settingsProject.value = p
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
  }
}

onMounted(ensureProjects)
watch([() => route.path, () => authStore.isLoggedIn], ensureProjects, { immediate: true })

function selectProject(id: number) {
  projectStore.setActiveProject(id)
  taskStore.fetchTasks()
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
      <div class="sidebar-header">
        <span class="sidebar-title">Projects</span>
        <button
          type="button"
          class="sidebar-btn-new"
          title="New project"
          @click="createProjectOpen = true"
        >
          New
        </button>
      </div>
      <nav class="sidebar-nav">
        <button
          v-for="p in projectStore.projects"
          :key="p.id"
          type="button"
          class="sidebar-item"
          :class="{ active: projectStore.activeProjectId === p.id }"
          @click="selectProject(p.id)"
        >
          <span class="sidebar-item-name">{{ p.name }}</span>
          <span class="sidebar-item-id">{{ p.identifier }}</span>
          <button
            type="button"
            class="sidebar-item-settings"
            title="Project settings"
            @click="openProjectSettings($event, p)"
          >
            ⚙
          </button>
        </button>
      </nav>
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
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}
.sidebar-brand {
  padding: 14px 16px 10px;
  border-bottom: 1px solid var(--color-border);
}
.sidebar-brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
  letter-spacing: -0.01em;
}
.sidebar-header {
  padding: 10px 16px 12px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.sidebar-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.sidebar-btn-new {
  padding: 4px 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
}
.sidebar-btn-new:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}
/* P4-6.2: Sidebar 激活项 150ms 动效 */
.sidebar-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 16px;
  text-align: left;
  color: var(--color-text-primary);
  font-size: 14px;
  transition: background 150ms ease, color 150ms ease;
}
.sidebar-item:hover {
  background: var(--color-bg-hover);
}
.sidebar-item.active {
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.sidebar-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sidebar-item-id {
  margin-left: 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
  flex-shrink: 0;
}
.sidebar-item-settings {
  margin-left: 4px;
  padding: 2px 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  opacity: 0.7;
}
.sidebar-item-settings:hover {
  opacity: 1;
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
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
