<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import type { Project, User } from '../types/domain'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'
import { useTaskStore } from '../store/taskStore'
import { useOverlayStore } from '../store/overlayStore'
import { useI18n } from 'vue-i18n'
import { projectApi } from '../services/api/project'
import { shouldIgnoreProjectResponse } from '../utils/projectRequestGuard'
import ProjectSettingsDialog from './ProjectSettingsDialog.vue'
// Excel 导入只在项目设置中打开导入弹窗时需要，不能进入首屏入口包。
const TaskImportModal = defineAsyncComponent(() => import('./TaskImportModal.vue'))

const props = defineProps<{
  open: boolean
  project: Project | null
}>()

const emit = defineEmits<{
  close: []
  updated: []
  deleted: []
}>()

const projectStore = useProjectStore()
const authStore = useAuthStore()
const taskStore = useTaskStore()
const overlayStore = useOverlayStore()
const { t } = useI18n()
const name = ref('')
const identifier = ref('')
const inviteEmail = ref('')
const importOpen = ref(false)
const importUsers = ref<User[]>([])
const isSubmitting = ref(false)
const isInviting = ref(false)
const error = ref('')
const inviteMessage = ref('')
const canDelete = computed(
  () => !!props.project && authStore.currentUser?.id === props.project.creatorId
)
const dailySummaryEnabled = ref(false)
const isEmailSaving = ref(false)
let emailSettingsRequestSeq = 0

watch(
  () => [props.open, props.project] as const,
  ([open, project]) => {
    if (open && project) {
      name.value = project.name
      identifier.value = project.identifier
      inviteEmail.value = ''
      importUsers.value = []
      error.value = ''
      inviteMessage.value = ''
      dailySummaryEnabled.value = false
      isEmailSaving.value = false
      void loadEmailSettings(project)
    }
    if (!open) {
      importOpen.value = false
      importUsers.value = []
      emailSettingsRequestSeq += 1
      isEmailSaving.value = false
    }
  }
)

async function loadEmailSettings(project: Project) {
  if (!canDelete.value) return
  const requestSeq = ++emailSettingsRequestSeq
  const projectId = project.id
  try {
    const settings = await projectApi.getEmailSettings(projectId)
    if (shouldIgnoreProjectResponse(requestSeq, emailSettingsRequestSeq, props.project?.id, projectId)) return
    const daily = settings.find((s) => s.scenarioKey === 'daily_summary')
    dailySummaryEnabled.value = daily?.enabled ?? false
  } catch (e) {
    if (shouldIgnoreProjectResponse(requestSeq, emailSettingsRequestSeq, props.project?.id, projectId)) return
    error.value = e instanceof Error ? e.message : '无法读取邮件设置'
  }
}

async function onToggleDailySummary(enabled: boolean) {
  if (!props.project) return
  const projectId = props.project.id
  const previous = dailySummaryEnabled.value
  dailySummaryEnabled.value = enabled
  isEmailSaving.value = true
  error.value = ''
  try {
    await projectApi.putEmailSettings(projectId, [{ scenarioKey: 'daily_summary', enabled }])
  } catch (e) {
    if (props.project?.id !== projectId) return
    dailySummaryEnabled.value = previous
    error.value = e instanceof Error ? e.message : '无法保存邮件设置'
  } finally {
    if (props.project?.id === projectId) {
      isEmailSaving.value = false
    }
  }
}

watch(importOpen, (open) => {
  if (open) {
    overlayStore.push('task-import-modal', () => {
      importOpen.value = false
    })
  } else {
    overlayStore.remove('task-import-modal')
  }
})

async function submit() {
  if (!props.project) return
  const n = name.value.trim()
  const id = identifier.value.trim().toUpperCase()
  if (!n || !id) {
    error.value = t('projectModal.validation.nameAndIdentifierRequired')
    return
  }
  if (id.length > 16) {
    error.value = t('projectModal.validation.identifierTooLong')
    return
  }
  isSubmitting.value = true
  error.value = ''
  try {
    await projectStore.updateProject(props.project.id, { name: n, identifier: id })
    emit('updated')
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.updateFailed')
  } finally {
    isSubmitting.value = false
  }
}

async function removeProject() {
  if (!props.project || !canDelete.value || isSubmitting.value) return
  const confirmed = window.confirm(
    t('projectSettingsModal.deleteConfirm', { name: props.project?.name ?? '' })
  )
  if (!confirmed) return

  isSubmitting.value = true
  error.value = ''
  try {
    await projectStore.deleteProject(props.project.id)
    emit('deleted')
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.deleteFailed')
  } finally {
    isSubmitting.value = false
  }
}

async function inviteMember() {
  if (!props.project) return
  const email = inviteEmail.value.trim()
  if (!email) {
    error.value = t('projectSettingsModal.errors.emailRequired')
    return
  }

  isInviting.value = true
  error.value = ''
  inviteMessage.value = ''
  try {
    await projectStore.inviteToProject(props.project.id, email)
    inviteEmail.value = ''
    inviteMessage.value = t('projectSettingsModal.inviteSuccess')
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.inviteFailed')
  } finally {
    isInviting.value = false
  }
}

async function openTaskImport() {
  if (!props.project || isSubmitting.value || isInviting.value) return
  importOpen.value = true
  try {
    importUsers.value = await projectApi.listMembers(props.project.id)
  } catch (e) {
    console.error('Failed to load project members:', e)
    importUsers.value = []
  }
}

function closeTaskImport() {
  importOpen.value = false
}

function handleImported() {
  if (!props.project || projectStore.activeProjectId !== props.project.id) return
  taskStore.fetchTasks()
}

function close() {
  if (!isSubmitting.value && !isInviting.value) emit('close')
}

onUnmounted(() => {
  overlayStore.remove('task-import-modal')
})
</script>

<template>
  <ProjectSettingsDialog
    :open="open && project != null"
    :name="name"
    :identifier="identifier"
    :invite-email="inviteEmail"
    :error="error"
    :invite-message="inviteMessage"
    :is-submitting="isSubmitting"
    :is-inviting="isInviting"
    :can-delete="canDelete"
    :daily-summary-enabled="dailySummaryEnabled"
    :is-email-saving="isEmailSaving"
    @update:name="name = $event"
    @update:identifier="identifier = $event"
    @update:invite-email="inviteEmail = $event"
    @toggle-daily-summary="onToggleDailySummary"
    @submit="submit"
    @invite="inviteMember"
    @import="openTaskImport"
    @delete="removeProject"
    @close="close"
  />
  <TaskImportModal
    :open="importOpen"
    :project-id="project?.id ?? null"
    :users="importUsers"
    @close="closeTaskImport"
    @imported="handleImported"
  />
</template>
