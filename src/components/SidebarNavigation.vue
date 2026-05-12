<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Project, Task } from '../types/domain'
import {
  BarChart3,
  ChevronDown,
  ChevronRight,
  Folder,
  LogOut,
  MoreVertical,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Search,
  Star
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
  'create-project': []
  'select-project': [projectId: number]
  'open-project-settings': [projectId: number]
}>()

const { t } = useI18n()
const userMenuOpen = ref(false)
const userMenuRef = ref<HTMLElement | null>(null)

const hasFavorites = computed(() => props.favorites.length > 0)

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
      <div class="sidebar-nav__identity">
        <button
          type="button"
          class="sidebar-nav__avatar"
          :class="{ 'sidebar-nav__avatar--active': userMenuOpen }"
          :title="userName ?? undefined"
          @click="toggleUserMenu"
        >
          {{ userInitial }}
        </button>
        <div class="sidebar-nav__brand">
          <span class="sidebar-nav__brand-name">{{ t('app.name') }}</span>
          <span class="sidebar-nav__brand-meta">{{ t('common.workspace') }}</span>
        </div>
      </div>

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
        <button
          type="button"
          class="sidebar-nav__section-trigger"
          data-testid="sidebar-favorites-toggle"
          @click="emit('toggle-favorites-collapsed')"
        >
          <ChevronDown
            v-if="!favoritesCollapsed"
            class="sidebar-nav__icon sidebar-nav__icon--xs sidebar-nav__chevron"
          />
          <ChevronRight
            v-else
            class="sidebar-nav__icon sidebar-nav__icon--xs sidebar-nav__chevron"
          />
          <span class="sidebar-nav__section-label">{{ t('sidebar.favorites') }}</span>
        </button>

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
            <Star class="sidebar-nav__icon sidebar-nav__item-icon sidebar-nav__item-icon--favorite" />
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
            class="sidebar-nav__section-trigger sidebar-nav__section-trigger--inline"
            data-testid="sidebar-projects-toggle"
            @click="emit('toggle-projects-collapsed')"
          >
            <ChevronDown
              v-if="!projectsCollapsed"
              class="sidebar-nav__icon sidebar-nav__icon--xs sidebar-nav__chevron"
            />
            <ChevronRight
              v-else
              class="sidebar-nav__icon sidebar-nav__icon--xs sidebar-nav__chevron"
            />
            <span class="sidebar-nav__section-label">{{ t('sidebar.projects') }}</span>
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
            :class="{ 'sidebar-nav__item--active': activeProjectId === project.id }"
            data-item-kind="project"
            :title="project.identifier"
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
  width: 248px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
}

.sidebar-nav__reopen {
  flex-shrink: 0;
  align-self: stretch;
  width: 44px;
  min-height: 100%;
  margin: 0;
  padding: 16px 0 0;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  background: var(--sidebar-bg);
  border: none;
  border-right: 1px solid var(--sidebar-border);
  color: var(--sidebar-muted);
  transition: color var(--transition-fast), background var(--transition-fast);
}

.sidebar-nav__reopen:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover);
}

.sidebar-nav__reopen-icon {
  width: 18px;
  height: 18px;
}

.sidebar-nav__header {
  position: relative;
  padding: 14px 12px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--sidebar-border-muted);
}

.sidebar-nav__identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-nav__avatar {
  width: 30px;
  height: 30px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--sidebar-text);
  background: var(--sidebar-panel);
  border: 1px solid var(--sidebar-border);
  border-radius: 8px;
  transition: border-color var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__avatar:hover,
.sidebar-nav__avatar--active {
  background: var(--sidebar-item-hover);
  border-color: var(--sidebar-accent-border);
}

.sidebar-nav__brand {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-nav__brand-name {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--sidebar-text);
}

.sidebar-nav__brand-meta {
  font-size: 11px;
  line-height: 1.2;
  color: var(--sidebar-muted);
}

.sidebar-nav__header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.sidebar-nav__icon-button {
  width: 28px;
  height: 28px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--sidebar-muted);
  background: transparent;
  border-radius: 8px;
  transition: color var(--transition-fast), background var(--transition-fast);
}

.sidebar-nav__icon-button:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover);
}

.sidebar-nav__icon-button--ghost {
  width: 24px;
  height: 24px;
  border-radius: 6px;
}

.sidebar-nav__menu {
  position: absolute;
  left: 12px;
  top: 50px;
  width: 208px;
  padding: 6px;
  background: var(--sidebar-popover-bg);
  border: 1px solid var(--sidebar-border);
  border-radius: 10px;
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
  padding: 10px 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sidebar-nav__section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar-nav__section--fill {
  flex: 1;
  min-height: 0;
}

.sidebar-nav__section-header {
  min-height: 28px;
  padding: 0 4px 0 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sidebar-nav__section-header--static {
  padding-right: 6px;
}

.sidebar-nav__section-trigger {
  width: 100%;
  min-height: 28px;
  padding: 0 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  border-radius: 8px;
  color: var(--sidebar-muted);
  transition: background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__section-trigger:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover);
}

.sidebar-nav__section-trigger--inline {
  min-height: 24px;
  padding: 0;
  border-radius: 0;
}

.sidebar-nav__section-trigger--inline:hover {
  background: transparent;
}

.sidebar-nav__section-label {
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.sidebar-nav__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-nav__list--projects {
  min-height: 0;
}

.sidebar-nav__item {
  width: 100%;
  min-height: 32px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sidebar-subtle-text);
  border: 1px solid transparent;
  border-radius: 8px;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__item:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover);
}

.sidebar-nav__item--active {
  color: var(--sidebar-text);
  background: var(--sidebar-item-active-bg);
  border-color: var(--sidebar-item-active-border);
}

.sidebar-nav__item--project {
  padding-right: 4px;
}

.sidebar-nav__item-main {
  min-width: 0;
  flex: 1;
  min-height: 30px;
  padding: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: inherit;
  background: transparent;
  text-align: left;
}

.sidebar-nav__item-action {
  width: 24px;
  height: 24px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--sidebar-muted);
  border-radius: 6px;
  opacity: 0;
  transition: opacity var(--transition-fast), background var(--transition-fast), color var(--transition-fast);
}

.sidebar-nav__item:hover .sidebar-nav__item-action,
.sidebar-nav__item:focus-within .sidebar-nav__item-action {
  opacity: 1;
}

.sidebar-nav__item-action:hover {
  color: var(--sidebar-text);
  background: var(--sidebar-item-hover-strong);
}

.sidebar-nav__item-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.3;
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
  color: var(--sidebar-muted);
}

.sidebar-nav__item--active .sidebar-nav__item-icon {
  color: var(--sidebar-accent);
}

.sidebar-nav__item-icon--favorite {
  color: var(--sidebar-accent);
}

.sidebar-nav__chevron {
  color: var(--sidebar-muted);
}
</style>
