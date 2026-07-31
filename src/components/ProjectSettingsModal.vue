<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref, watch } from 'vue'
import type { Project, User } from '../types/domain'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'
import { useTaskStore } from '../store/taskStore'
import { useOverlayStore } from '../store/overlayStore'
import { useI18n } from 'vue-i18n'
import { projectApi, type GitHubRepository, type GitLabRepository } from '../services/api/project'
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
const saveMessage = ref('')
const canDelete = computed(
  () => !!props.project && authStore.currentUser?.id === props.project.creatorId
)
const dailySummaryEnabled = ref(false)
const isEmailSaving = ref(false)
const gitlabRepositories = ref<GitLabRepository[]>([])
const gitlabRepositoryUrl = ref('')
const gitlabWebhookToken = ref('')
const isGitLabLoading = ref(false)
const githubRepositories = ref<GitHubRepository[]>([])
const githubRepositoryUrl = ref('')
const githubWebhookSecret = ref('')
const isGitHubLoading = ref(false)
let emailSettingsRequestSeq = 0
let gitlabRepositoriesRequestSeq = 0

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
      saveMessage.value = ''
      dailySummaryEnabled.value = false
      isEmailSaving.value = false
      gitlabRepositories.value = []
      gitlabRepositoryUrl.value = ''
      gitlabWebhookToken.value = ''
      isGitLabLoading.value = false
      githubRepositories.value = []
      githubRepositoryUrl.value = ''
      githubWebhookSecret.value = ''
      isGitHubLoading.value = false
      void loadEmailSettings(project)
      void loadGitLabRepositories(project)
      void loadGitHubRepositories(project)
    }
    if (!open) {
      importOpen.value = false
      importUsers.value = []
      emailSettingsRequestSeq += 1
      gitlabRepositoriesRequestSeq += 1
      isEmailSaving.value = false
      isGitLabLoading.value = false
      isGitHubLoading.value = false
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
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.emailLoadFailed')
  }
}

async function loadGitLabRepositories(project: Project) {
  if (!canDelete.value) return
  const requestSeq = ++gitlabRepositoriesRequestSeq
  const projectId = project.id
  isGitLabLoading.value = true
  try {
    const repositories = await projectApi.listGitLabRepositories(projectId)
    if (shouldIgnoreProjectResponse(requestSeq, gitlabRepositoriesRequestSeq, props.project?.id, projectId)) return
    gitlabRepositories.value = repositories
  } catch (e) {
    if (shouldIgnoreProjectResponse(requestSeq, gitlabRepositoriesRequestSeq, props.project?.id, projectId)) return
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.gitlabLoadFailed')
  } finally {
    if (!shouldIgnoreProjectResponse(requestSeq, gitlabRepositoriesRequestSeq, props.project?.id, projectId)) {
      isGitLabLoading.value = false
    }
  }
}

async function addGitLabRepository() {
  if (!props.project || !gitlabRepositoryUrl.value.trim() || isGitLabLoading.value) return
  const projectId = props.project.id
  isGitLabLoading.value = true
  error.value = ''
  try {
    const repository = await projectApi.createGitLabRepository(projectId, gitlabRepositoryUrl.value.trim())
    if (props.project?.id !== projectId) return
    gitlabRepositories.value = [...gitlabRepositories.value, repository]
    gitlabRepositoryUrl.value = ''
    gitlabWebhookToken.value = repository.webhookToken ?? ''
  } catch (e) {
    if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.gitlabAddFailed')
  } finally {
    if (props.project?.id === projectId) isGitLabLoading.value = false
  }
}

async function resetGitLabWebhookToken(repositoryId: number) {
  if (!props.project || isGitLabLoading.value) return
  const projectId = props.project.id
  isGitLabLoading.value = true
  error.value = ''
  try {
    const repository = await projectApi.resetGitLabWebhookToken(projectId, repositoryId)
    if (props.project?.id !== projectId) return
    gitlabRepositories.value = gitlabRepositories.value.map((item) => item.id === repository.id ? repository : item)
    gitlabWebhookToken.value = repository.webhookToken ?? ''
  } catch (e) {
    if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.gitlabResetFailed')
  } finally {
    if (props.project?.id === projectId) isGitLabLoading.value = false
  }
}

async function deleteGitLabRepository(repositoryId: number) {
  if (!props.project || isGitLabLoading.value) return
  const repository = gitlabRepositories.value.find((item) => item.id === repositoryId)
  if (!repository || !window.confirm(t('projectSettingsModal.gitlabRemoveConfirm', { repository: repository.repositoryPath }))) return
  const projectId = props.project.id
  isGitLabLoading.value = true
  error.value = ''
  try {
    await projectApi.deleteGitLabRepository(projectId, repositoryId)
    if (props.project?.id !== projectId) return
    gitlabRepositories.value = gitlabRepositories.value.filter((item) => item.id !== repositoryId)
    gitlabWebhookToken.value = ''
  } catch (e) {
    if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.gitlabRemoveFailed')
  } finally {
    if (props.project?.id === projectId) isGitLabLoading.value = false
  }
}

async function loadGitHubRepositories(project: Project) {
  if (!canDelete.value) return
  try { githubRepositories.value = await projectApi.listGitHubRepositories(project.id) }
  catch (e) { error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.githubLoadFailed') }
}

async function addGitHubRepository() {
  if (!props.project || !githubRepositoryUrl.value.trim() || isGitHubLoading.value) return
  const projectId = props.project.id
  isGitHubLoading.value = true; error.value = ''
  try {
    const repository = await projectApi.createGitHubRepository(projectId, githubRepositoryUrl.value.trim())
    if (props.project?.id !== projectId) return
    githubRepositories.value = [...githubRepositories.value, repository]
    githubRepositoryUrl.value = ''; githubWebhookSecret.value = repository.webhookSecret ?? ''
  } catch (e) { if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.githubAddFailed') }
  finally { if (props.project?.id === projectId) isGitHubLoading.value = false }
}

async function resetGitHubWebhookSecret(repositoryId: number) {
  if (!props.project || isGitHubLoading.value) return
  const projectId = props.project.id; isGitHubLoading.value = true; error.value = ''
  try {
    const repository = await projectApi.resetGitHubWebhookSecret(projectId, repositoryId)
    if (props.project?.id !== projectId) return
    githubRepositories.value = githubRepositories.value.map((item) => item.id === repository.id ? repository : item)
    githubWebhookSecret.value = repository.webhookSecret ?? ''
  } catch (e) { if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.githubResetFailed') }
  finally { if (props.project?.id === projectId) isGitHubLoading.value = false }
}

async function deleteGitHubRepository(repositoryId: number) {
  if (!props.project || isGitHubLoading.value) return
  const repository = githubRepositories.value.find((item) => item.id === repositoryId)
  if (!repository || !window.confirm(t('projectSettingsModal.githubRemoveConfirm', { repository: repository.repositoryPath }))) return
  const projectId = props.project.id; isGitHubLoading.value = true; error.value = ''
  try {
    await projectApi.deleteGitHubRepository(projectId, repositoryId)
    if (props.project?.id !== projectId) return
    githubRepositories.value = githubRepositories.value.filter((item) => item.id !== repositoryId); githubWebhookSecret.value = ''
  } catch (e) { if (props.project?.id === projectId) error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.githubRemoveFailed') }
  finally { if (props.project?.id === projectId) isGitHubLoading.value = false }
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
    error.value = e instanceof Error ? e.message : t('projectSettingsModal.errors.emailSaveFailed')
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
  saveMessage.value = ''
  try {
    await projectStore.updateProject(props.project.id, { name: n, identifier: id })
    emit('updated')
    saveMessage.value = t('projectSettingsModal.saved')
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
    :save-message="saveMessage"
    :is-submitting="isSubmitting"
    :is-inviting="isInviting"
    :can-delete="canDelete"
    :daily-summary-enabled="dailySummaryEnabled"
    :is-email-saving="isEmailSaving"
    :gitlab-repositories="gitlabRepositories"
    :gitlab-repository-url="gitlabRepositoryUrl"
    :gitlab-webhook-token="gitlabWebhookToken"
    :is-git-lab-loading="isGitLabLoading"
    :github-repositories="githubRepositories"
    :github-repository-url="githubRepositoryUrl"
    :github-webhook-secret="githubWebhookSecret"
    :is-git-hub-loading="isGitHubLoading"
    @update:name="name = $event; saveMessage = ''"
    @update:identifier="identifier = $event; saveMessage = ''"
    @update:invite-email="inviteEmail = $event"
    @toggle-daily-summary="onToggleDailySummary"
    @update:gitlab-repository-url="gitlabRepositoryUrl = $event"
    @add-git-lab-repository="addGitLabRepository"
    @reset-git-lab-webhook-token="resetGitLabWebhookToken"
    @delete-git-lab-repository="deleteGitLabRepository"
    @update:github-repository-url="githubRepositoryUrl = $event"
    @add-git-hub-repository="addGitHubRepository"
    @reset-git-hub-webhook-secret="resetGitHubWebhookSecret"
    @delete-git-hub-repository="deleteGitHubRepository"
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
