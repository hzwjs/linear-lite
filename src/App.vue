<script setup lang="ts">
import { computed, watch, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './store/authStore'
import { useProjectStore } from './store/projectStore'
import { useTaskStore } from './store/taskStore'
import CreateProjectModal from './components/CreateProjectModal.vue'
import ProjectSettingsModal from './components/ProjectSettingsModal.vue'
import type { Project } from './types/domain'

const route = useRoute()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const taskStore = useTaskStore()

const createProjectOpen = ref(false)
const settingsProject = ref<Project | null>(null)

function openProjectSettings(e: Event, p: Project) {
  e.stopPropagation()
  settingsProject.value = p
}

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
</script>

<template>
  <template v-if="isLoginRoute">
    <router-view />
  </template>
  <div v-else class="app-layout">
    <aside class="sidebar">
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
.sidebar-header {
  padding: 16px 16px 12px;
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
  color: var(--color-accent);
  background: transparent;
  border: 1px solid var(--color-accent);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
}
.sidebar-btn-new:hover {
  background: rgba(255, 255, 255, 0.06);
}
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}
.sidebar-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 16px;
  text-align: left;
  color: var(--color-text-primary);
  font-size: 14px;
  transition: background var(--transition-fast);
}
.sidebar-item:hover {
  background: var(--color-bg-hover);
}
.sidebar-item.active {
  background: var(--color-bg-elevated);
  color: var(--color-accent);
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
