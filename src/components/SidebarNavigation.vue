<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Project, Task } from '../types/domain'
import {
  BarChart3,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  Circle,
  CircleDashed,
  CircleX,
  Copy,
  Eye,
  Folder,
  Loader2,
  LogOut,
  MoreVertical,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Search
} from 'lucide-vue-next'

const props = defineProps<{
  hidden: boolean
  userName?: string | null
  userInitial: string
  locale: 'zh-CN' | 'en'
  favoritesCollapsed: boolean
  projectsCollapsed: boolean
  favorites: Task[]
  projects: Project[]
  routePath: string
  routeTaskId: string | null
  activeProjectId: number | null
}>()

const emit = defineEmits<{
  'show-sidebar': []
  'hide-sidebar': []
  'focus-search': []
  'set-locale': [locale: 'zh-CN' | 'en']
  logout: []
  'toggle-favorites-collapsed': []
  'open-favorite-task': [taskId: string, projectId?: number]
  'open-analytics': []
  'toggle-projects-collapsed': []
  'reorder-projects': [projectIds: number[]]
  'create-project': []
  'select-project': [projectId: number]
  'open-project-settings': [projectId: number]
}>()

const { t } = useI18n()
const userMenuOpen = ref(false)
const userMenuRef = ref<HTMLElement | null>(null)

const hasFavorites = computed(() => props.favorites.length > 0)
const draggedProjectId = ref<number | null>(null)
const dragOverProjectId = ref<number | null>(null)
const dragOverPlacement = ref<'before' | 'after'>('before')

const statusIcons: Record<Task['status'], typeof Circle> = {
  backlog: CircleDashed,
  todo: Circle,
  in_progress: Loader2,
  in_review: Eye,
  done: CheckCircle,
  canceled: CircleX,
  duplicate: Copy
}

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
}

function closeUserMenu() {
  userMenuOpen.value = false
}

function onClickOutsideUserMenu(event: MouseEvent) {
  const el = userMenuRef.value
  if (!el || el.contains(event.target as Node)) return
  closeUserMenu()
}

function handleOpenProjectSettings(event: Event, projectId: number) {
  event.stopPropagation()
  emit('open-project-settings', projectId)
}

function resetProjectDragState() {
  draggedProjectId.value = null
  dragOverProjectId.value = null
  dragOverPlacement.value = 'before'
}

function onProjectDragStart(event: DragEvent, projectId: number) {
  draggedProjectId.value = projectId
  dragOverProjectId.value = null
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(projectId))
  }
}

function onProjectDragOver(event: DragEvent, projectId: number) {
  if (draggedProjectId.value == null || draggedProjectId.value === projectId) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dragOverProjectId.value = projectId
  const target = event.currentTarget as HTMLElement | null
  const bounds = target?.getBoundingClientRect()
  dragOverPlacement.value = bounds && event.clientY > bounds.top + bounds.height / 2 ? 'after' : 'before'
}

function onProjectDrop(event: DragEvent, targetProjectId: number) {
  event.preventDefault()
  const sourceProjectId = draggedProjectId.value
  const placement = dragOverPlacement.value
  resetProjectDragState()
  if (sourceProjectId == null || sourceProjectId === targetProjectId) return

  const sourceIndex = props.projects.findIndex((project) => project.id === sourceProjectId)
  const targetIndex = props.projects.findIndex((project) => project.id === targetProjectId)
  if (sourceIndex < 0 || targetIndex < 0) return

  const nextProjects = [...props.projects]
  const [movedProject] = nextProjects.splice(sourceIndex, 1)
  const rawInsertionIndex = placement === 'after' ? targetIndex + 1 : targetIndex
  const insertionIndex = sourceIndex < rawInsertionIndex ? rawInsertionIndex - 1 : rawInsertionIndex
  nextProjects.splice(insertionIndex, 0, movedProject!)
  emit('reorder-projects', nextProjects.map((project) => project.id))
}

watch(
  () => props.hidden,
  (hidden) => {
    if (hidden) closeUserMenu()
  }
)

onMounted(() => {
  document.addEventListener('click', onClickOutsideUserMenu, true)
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutsideUserMenu, true)
})
</script>

<template>
  <button
    v-if="hidden"
    type="button"
    class="sidebar-nav__reopen"
    :title="t('sidebar.showSidebar')"
    :aria-label="t('sidebar.showSidebar')"
    @click="emit('show-sidebar')"
  >
    <PanelLeftOpen class="sidebar-nav__reopen-icon" />
  </button>

  <aside v-else class="sidebar-nav" aria-label="Workspace navigation">
    <div ref="userMenuRef" class="sidebar-nav__header">
      <button
        type="button"
        class="sidebar-nav__identity"
        :class="{ 'sidebar-nav__identity--active': userMenuOpen }"
        :title="userName ?? undefined"
        :aria-expanded="userMenuOpen"
        @click="toggleUserMenu"
      >
        <span class="sidebar-nav__avatar">{{ userInitial }}</span>
        <span class="sidebar-nav__brand">
          <span class="sidebar-nav__brand-name">{{ t('app.name') }}</span>
          <ChevronDown class="sidebar-nav__identity-chevron" />
        </span>
      </button>

      <div class="sidebar-nav__header-actions">
        <slot name="notification" />
        <button
          type="button"
          class="sidebar-nav__icon-button"
          :title="t('sidebar.search')"
          :aria-label="t('sidebar.search')"
          @click="emit('focus-search')"
        >
          <Search class="sidebar-nav__icon sidebar-nav__icon--sm" />
        </button>
        <button
          type="button"
          class="sidebar-nav__icon-button"
          :title="t('sidebar.hideSidebar')"
          :aria-label="t('sidebar.hideSidebar')"
          @click="emit('hide-sidebar')"
        >
          <PanelLeftClose class="sidebar-nav__icon sidebar-nav__icon--sm" />
        </button>
      </div>

      <div v-show="userMenuOpen" class="sidebar-nav__menu">
        <div class="sidebar-nav__menu-header">
          <span class="sidebar-nav__menu-name">{{ userName ?? '—' }}</span>
        </div>
        <div class="sidebar-nav__menu-divider" />
        <div class="sidebar-nav__menu-row">
          <span class="sidebar-nav__menu-label">{{ t('common.language') }}</span>
          <div class="sidebar-nav__locale-switcher">
            <button
              type="button"
              class="sidebar-nav__locale-pill"
              :class="{ 'sidebar-nav__locale-pill--active': locale === 'zh-CN' }"
              @click="emit('set-locale', 'zh-CN')"
            >
              ZH
            </button>
            <button
              type="button"
              class="sidebar-nav__locale-pill"
              :class="{ 'sidebar-nav__locale-pill--active': locale === 'en' }"
              @click="emit('set-locale', 'en')"
            >
              EN
            </button>
          </div>
        </div>
        <div class="sidebar-nav__menu-divider" />
        <button type="button" class="sidebar-nav__menu-item" @click="emit('logout')">
          <LogOut class="sidebar-nav__icon sidebar-nav__icon--xs" />
          <span>{{ t('sidebar.signOut') }}</span>
        </button>
      </div>
    </div>

    <div class="sidebar-nav__content">
      <section v-if="hasFavorites" class="sidebar-nav__section">
        <div class="sidebar-nav__section-header">
          <button
            type="button"
            class="sidebar-nav__section-trigger"
            :aria-expanded="!favoritesCollapsed"
            data-testid="sidebar-favorites-toggle"
            @click="emit('toggle-favorites-collapsed')"
          >
            <span class="sidebar-nav__section-label">{{ t('sidebar.favorites') }}</span>
            <ChevronDown
              v-if="!favoritesCollapsed"
              class="sidebar-nav__icon sidebar-nav__chevron"
            />
            <ChevronRight
              v-else
              class="sidebar-nav__icon sidebar-nav__chevron"
            />
          </button>
        </div>

        <nav v-show="!favoritesCollapsed" class="sidebar-nav__list">
          <button
            v-for="task in favorites"
            :key="task.id"
            type="button"
            class="sidebar-nav__item"
            :class="{ 'sidebar-nav__item--active': routeTaskId === task.id }"
            data-item-kind="favorite"
            :data-testid="`sidebar-favorite-${task.id}`"
            @click="emit('open-favorite-task', task.id, task.projectId)"
          >
            <component
              :is="statusIcons[task.status]"
              class="sidebar-nav__icon sidebar-nav__item-icon sidebar-nav__item-icon--favorite"
              :class="`sidebar-nav__item-icon--status-${task.status}`"
              :title="t(`status.${task.status}`)"
              aria-hidden="true"
            />
            <span class="sidebar-nav__item-label">{{ task.title }}</span>
          </button>
        </nav>
      </section>

      <section class="sidebar-nav__section">
        <div class="sidebar-nav__section-header sidebar-nav__section-header--static">
          <span class="sidebar-nav__section-label">{{ t('sidebar.workspace') }}</span>
        </div>

        <nav class="sidebar-nav__list">
          <button
            type="button"
            class="sidebar-nav__item"
            :class="{ 'sidebar-nav__item--active': routePath === '/analytics' }"
            data-item-kind="analytics"
            data-testid="sidebar-analytics"
            @click="emit('open-analytics')"
          >
            <BarChart3 class="sidebar-nav__icon sidebar-nav__item-icon" />
            <span class="sidebar-nav__item-label">{{ t('sidebar.analytics') }}</span>
          </button>
        </nav>
      </section>

      <section class="sidebar-nav__section sidebar-nav__section--fill">
        <div class="sidebar-nav__section-header">
          <button
            type="button"
            class="sidebar-nav__section-trigger"
            :aria-expanded="!projectsCollapsed"
            data-testid="sidebar-projects-toggle"
            @click="emit('toggle-projects-collapsed')"
          >
            <span class="sidebar-nav__section-label">{{ t('sidebar.projects') }}</span>
            <ChevronDown
              v-if="!projectsCollapsed"
              class="sidebar-nav__icon sidebar-nav__chevron"
            />
            <ChevronRight
              v-else
              class="sidebar-nav__icon sidebar-nav__chevron"
            />
          </button>

          <button
            type="button"
            class="sidebar-nav__icon-button sidebar-nav__icon-button--ghost"
            :title="t('sidebar.newProjectTitle')"
            :aria-label="t('sidebar.newProjectTitle')"
            data-testid="sidebar-create-project"
            @click="emit('create-project')"
          >
            <Plus class="sidebar-nav__icon sidebar-nav__icon--xs" />
          </button>
        </div>

        <nav v-show="!projectsCollapsed" class="sidebar-nav__list sidebar-nav__list--projects">
          <div
            v-for="project in projects"
            :key="project.id"
            class="sidebar-nav__item sidebar-nav__item--project"
            :class="{
              'sidebar-nav__item--active': activeProjectId === project.id,
              'sidebar-nav__item--dragging': draggedProjectId === project.id,
              'sidebar-nav__item--drag-over': dragOverProjectId === project.id,
              'sidebar-nav__item--drag-over-after':
                dragOverProjectId === project.id && dragOverPlacement === 'after'
            }"
            :data-testid="`sidebar-project-${project.id}`"
            data-item-kind="project"
            :title="project.identifier"
            draggable="true"
            :aria-grabbed="draggedProjectId === project.id"
            @dragstart="onProjectDragStart($event, project.id)"
            @dragover="onProjectDragOver($event, project.id)"
            @dragleave="dragOverProjectId === project.id && (dragOverProjectId = null)"
            @drop="onProjectDrop($event, project.id)"
            @dragend="resetProjectDragState"
          >
            <button
              type="button"
              class="sidebar-nav__item-main"
              @click="emit('select-project', project.id)"
            >
              <Folder class="sidebar-nav__icon sidebar-nav__item-icon" />
              <span class="sidebar-nav__item-label">{{ project.name }}</span>
            </button>

            <button
              type="button"
              class="sidebar-nav__item-action"
              :title="t('sidebar.projectSettings')"
              :aria-label="t('sidebar.projectSettings')"
              :data-testid="`sidebar-project-settings-${project.id}`"
              @click="handleOpenProjectSettings($event, project.id)"
            >
              <MoreVertical class="sidebar-nav__icon sidebar-nav__icon--xs" />
            </button>
          </div>
        </nav>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.sidebar-nav {
  --sidebar-ease-out: cubic-bezier(0.23, 1, 0.32, 1);
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
}

.sidebar-nav__reopen {
  flex-shrink: 0;
  align-self: stretch;
  width: 40px;
  min-height: 100%;
  margin: 0;
  padding: 15px 0 0;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  background: var(--sidebar-bg);
  border: none;
  border-right: 1px solid var(--sidebar-border);
  color: var(--sidebar-muted);
  transition: color var(--transition-fast), background var(--transition-fast);
}

.sidebar-nav__reopen:active {
  background: var(--sidebar-item-hover-strong);
}

.sidebar-nav__reopen-icon {
  width: 18px;
  height: 18px;
}

.sidebar-nav__header {
  position: relative;
  min-height: 48px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sidebar-nav__identity {
  min-width: 0;
  min-height: 32px;
  padding: 0 4px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sidebar-text);
  border-radius: 6px;
  text-align: left;
  transition: background 120ms ease, transform 120ms var(--sidebar-ease-out);
}

.sidebar-nav__avatar {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  color: #ffffff;
  background: var(--sidebar-accent);
  border-radius: 7px;
}

.sidebar-nav__identity:active {
  transform: scale(0.98);
}

.sidebar-nav__identity--active {
  background: var(--sidebar-item-hover);
}

.sidebar-nav__brand {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.sidebar-nav__brand-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--sidebar-text);
}

.sidebar-nav__identity-chevron {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  color: var(--sidebar-muted);
  stroke-width: 2;
}

.sidebar-nav__header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.sidebar-nav__icon-button {
  width: 26px;
  height: 26px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--sidebar-muted);
  background: transparent;
  border-radius: 6px;
  transition: color var(--transition-fast), background var(--transition-fast), transform 120ms var(--sidebar-ease-out);
}

.sidebar-nav__icon-button:active {
  transform: scale(0.94);
}

.sidebar-nav__icon-button--ghost {
  width: 24px;
  height: 24px;
  border-radius: 6px;
}

.sidebar-nav__menu {
  position: absolute;
  left: 10px;
  top: 44px;
  width: 204px;
  padding: 6px;
  background: var(--sidebar-popover-bg);
  border: 1px solid var(--sidebar-border);
  border-radius: 8px;
  box-shadow: var(--shadow-popover);
  z-index: 100;
}

.sidebar-nav__menu-header,
.sidebar-nav__menu-row,
.sidebar-nav__menu-item {
  padding: 8px 10px;
}

.sidebar-nav__menu-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--sidebar-text);
}

.sidebar-nav__menu-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--sidebar-border-muted);
}

.sidebar-nav__menu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sidebar-nav__menu-label {
  font-size: 12px;
  color: var(--sidebar-muted);
}

.sidebar-nav__locale-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.sidebar-nav__locale-pill {
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--sidebar-border);
  border-radius: 6px;
  background: var(--sidebar-popover-bg);
  color: var(--sidebar-subtle-text);
  font-size: 11px;
  font-weight: 500;
  transition: border-color var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__locale-pill:hover {
  color: var(--sidebar-text);
  border-color: var(--sidebar-accent-border);
}

.sidebar-nav__locale-pill--active {
  color: var(--sidebar-accent);
  background: var(--sidebar-item-active-bg);
  border-color: var(--sidebar-accent-border);
}

.sidebar-nav__menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 8px;
  color: var(--sidebar-subtle-text);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__menu-item:hover {
  background: var(--sidebar-item-hover);
  color: var(--sidebar-text);
}

.sidebar-nav__content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 8px 14px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.sidebar-nav__content:hover {
  scrollbar-color: var(--sidebar-border) transparent;
}

.sidebar-nav__section {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.sidebar-nav__section--fill {
  flex: 1;
  min-height: 0;
}

.sidebar-nav__section-header {
  min-height: 24px;
  padding: 0 4px 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sidebar-nav__section-header--static {
  padding-right: 6px;
  color: var(--sidebar-muted);
}

.sidebar-nav__section-trigger {
  width: auto;
  min-height: 24px;
  padding: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  border-radius: 4px;
  color: var(--sidebar-muted);
  transition: color var(--transition-fast);
}

.sidebar-nav__section-label {
  font-size: 12px;
  font-weight: 500;
  line-height: 1.2;
  letter-spacing: -0.01em;
}

.sidebar-nav__list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.sidebar-nav__list--projects {
  min-height: 0;
}

.sidebar-nav__item {
  width: 100%;
  min-height: 30px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--sidebar-subtle-text);
  border-radius: 6px;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__item--active {
  color: var(--sidebar-text);
  background: var(--sidebar-item-active-bg);
  font-weight: 500;
}

.sidebar-nav__item--project {
  position: relative;
  padding-right: 4px;
  cursor: grab;
}

.sidebar-nav__item--project:active {
  cursor: grabbing;
}

.sidebar-nav__item--dragging {
  opacity: 0.45;
}

.sidebar-nav__item--drag-over::before {
  content: '';
  position: absolute;
  top: -2px;
  right: 8px;
  left: 8px;
  height: 2px;
  border-radius: 999px;
  background: var(--sidebar-accent);
  pointer-events: none;
}

.sidebar-nav__item--drag-over-after::before {
  top: auto;
  bottom: -2px;
}

.sidebar-nav__item-main {
  min-width: 0;
  flex: 1;
  min-height: 28px;
  padding: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  color: inherit;
  background: transparent;
  text-align: left;
}

.sidebar-nav__item-action {
  width: 22px;
  height: 22px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--sidebar-muted);
  border-radius: 6px;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast), transform 120ms var(--sidebar-ease-out);
}

.sidebar-nav__item:hover .sidebar-nav__item-action,
.sidebar-nav__item:focus-within .sidebar-nav__item-action {
  opacity: 1;
}

.sidebar-nav__item-action:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover-strong);
}

.sidebar-nav__item-action:active {
  transform: scale(0.92);
}

.sidebar-nav__item-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.25;
}

.sidebar-nav__icon {
  flex-shrink: 0;
}

.sidebar-nav__icon--sm {
  width: 16px;
  height: 16px;
}

.sidebar-nav__icon--xs {
  width: 14px;
  height: 14px;
}

.sidebar-nav__item-icon {
  width: 16px;
  height: 16px;
  color: var(--sidebar-muted);
  stroke-width: 1.8;
}

.sidebar-nav__item--active .sidebar-nav__item-icon {
  color: var(--sidebar-text);
}

.sidebar-nav__item-icon--favorite {
  width: 15px;
  height: 15px;
  stroke-width: 2;
}

.sidebar-nav__item-icon--status-backlog,
.sidebar-nav__item-icon--status-todo {
  color: var(--color-text-secondary);
}

.sidebar-nav__item-icon--status-in_progress,
.sidebar-nav__item-icon--status-in_review {
  color: var(--color-status-in-progress);
}

.sidebar-nav__item-icon--status-done {
  color: var(--color-status-done);
}

.sidebar-nav__item-icon--status-canceled,
.sidebar-nav__item-icon--status-duplicate {
  color: var(--color-text-muted);
}

.sidebar-nav__chevron {
  width: 12px;
  height: 12px;
  color: var(--sidebar-muted);
}

.sidebar-nav__identity:focus-visible,
.sidebar-nav__icon-button:focus-visible,
.sidebar-nav__section-trigger:focus-visible,
.sidebar-nav__item:focus-visible,
.sidebar-nav__item-main:focus-visible,
.sidebar-nav__item-action:focus-visible,
.sidebar-nav__reopen:focus-visible {
  outline: 2px solid var(--sidebar-accent-border);
  outline-offset: 1px;
}

:deep(.notification-bell) {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  color: var(--sidebar-muted);
  transition: color var(--transition-fast), background var(--transition-fast), transform 120ms var(--sidebar-ease-out);
}

:deep(.notification-bell-icon) {
  width: 16px;
  height: 16px;
}

:deep(.notification-bell:active) {
  transform: scale(0.94);
}

@media (hover: hover) and (pointer: fine) {
  .sidebar-nav__reopen:hover,
  .sidebar-nav__identity:hover,
  .sidebar-nav__icon-button:hover,
  .sidebar-nav__menu-item:hover,
  .sidebar-nav__item:hover {
    color: var(--sidebar-text);
    background: var(--sidebar-item-hover);
  }

  .sidebar-nav__section-trigger:hover {
    color: var(--sidebar-text);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-nav__identity,
  .sidebar-nav__icon-button,
  .sidebar-nav__item-action,
  :deep(.notification-bell) {
    transition-duration: 0ms;
  }
}
</style>
