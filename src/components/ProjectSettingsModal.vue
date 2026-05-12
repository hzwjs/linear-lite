<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Project } from '../types/domain'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'
import { useI18n } from 'vue-i18n'
import ProjectSettingsDialog from './ProjectSettingsDialog.vue'

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
const { t } = useI18n()
const name = ref('')
const identifier = ref('')
const inviteEmail = ref('')
const isSubmitting = ref(false)
const isInviting = ref(false)
const error = ref('')
const inviteMessage = ref('')
const canDelete = computed(
  () => !!props.project && authStore.currentUser?.id === props.project.creatorId
)

watch(
  () => [props.open, props.project] as const,
  ([open, project]) => {
    if (open && project) {
      name.value = project.name
      identifier.value = project.identifier
      inviteEmail.value = ''
      error.value = ''
      inviteMessage.value = ''
    }
  }
)

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

function close() {
  if (!isSubmitting.value && !isInviting.value) emit('close')
}
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
    @update:name="name = $event"
    @update:identifier="identifier = $event"
    @update:invite-email="inviteEmail = $event"
    @submit="submit"
    @invite="inviteMember"
    @delete="removeProject"
    @close="close"
  />
</template>
