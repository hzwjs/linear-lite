<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Project } from '../types/domain'
import { useProjectStore } from '../store/projectStore'
import { useAuthStore } from '../store/authStore'

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
    error.value = 'Please enter project name and identifier'
    return
  }
  if (id.length > 16) {
    error.value = 'Identifier must be at most 16 characters'
    return
  }
  isSubmitting.value = true
  error.value = ''
  try {
    await projectStore.updateProject(props.project.id, { name: n, identifier: id })
    emit('updated')
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Update failed'
  } finally {
    isSubmitting.value = false
  }
}

async function removeProject() {
  if (!props.project || !canDelete.value || isSubmitting.value) return
  const confirmed = window.confirm(
    `Delete project "${props.project.name}" and all its tasks? This cannot be undone.`
  )
  if (!confirmed) return

  isSubmitting.value = true
  error.value = ''
  try {
    await projectStore.deleteProject(props.project.id)
    emit('deleted')
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Delete failed'
  } finally {
    isSubmitting.value = false
  }
}

async function inviteMember() {
  if (!props.project) return
  const email = inviteEmail.value.trim()
  if (!email) {
    error.value = 'Please enter an email to invite'
    return
  }

  isInviting.value = true
  error.value = ''
  inviteMessage.value = ''
  try {
    await projectStore.inviteToProject(props.project.id, email)
    inviteEmail.value = ''
    inviteMessage.value = 'Invitation sent.'
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Invite failed'
  } finally {
    isInviting.value = false
  }
}

function close() {
  if (!isSubmitting.value && !isInviting.value) emit('close')
}
</script>

<template>
  <div v-if="open && project" class="modal-overlay" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>Project settings</h3>
        <button type="button" class="close-btn" @click="close">×</button>
      </div>
      <form class="modal-body" @submit.prevent="submit">
        <div class="form-group">
          <label>Project name</label>
          <input v-model="name" type="text" class="input" />
        </div>
        <div class="form-group">
          <label>Identifier</label>
          <input
            v-model="identifier"
            type="text"
            class="input"
            maxlength="16"
          />
        </div>
        <p v-if="error" class="error-msg">{{ error }}</p>
        <div class="invite-zone">
          <div>
            <p class="invite-zone-title">Invite by email</p>
            <p class="invite-zone-text">Invited users will see this project after they sign in or register.</p>
          </div>
          <div class="invite-controls">
            <input
              v-model="inviteEmail"
              type="email"
              class="input"
              placeholder="name@example.com"
              :disabled="isInviting || isSubmitting"
            />
            <button
              type="button"
              class="btn-primary"
              :disabled="isInviting || isSubmitting"
              @click="inviteMember"
            >
              {{ isInviting ? 'Inviting...' : 'Invite' }}
            </button>
          </div>
          <p v-if="inviteMessage" class="invite-success">{{ inviteMessage }}</p>
        </div>
        <div v-if="canDelete" class="danger-zone">
          <div>
            <p class="danger-zone-title">Delete project</p>
            <p class="danger-zone-text">This deletes the project and all tasks permanently.</p>
          </div>
          <button
            type="button"
            class="btn-danger"
            :disabled="isSubmitting"
            @click="removeProject"
          >
            {{ isSubmitting ? 'Deleting...' : 'Delete project' }}
          </button>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn-cancel" @click="close">Cancel</button>
          <button type="submit" class="btn-primary" :disabled="isSubmitting">
            {{ isSubmitting ? 'Saving...' : 'Save' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.2);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* P4-9.1: 减轻浮层感 */
.modal {
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  min-width: 360px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}
.close-btn {
  font-size: 24px;
  line-height: 1;
  color: var(--color-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
}
.close-btn:hover {
  color: var(--color-text-primary);
}
.modal-body {
  padding: 20px;
}
.form-group {
  margin-bottom: 16px;
}
.form-group label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}
.input {
  width: 100%;
  padding: 8px 12px;
  font-size: 14px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
}
.error-msg {
  margin: 0 0 12px;
  font-size: 13px;
  color: #e5484d;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.invite-zone {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}
.invite-zone-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
}
.invite-zone-text {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.invite-controls {
  display: flex;
  gap: 8px;
}
.invite-success {
  margin: 8px 0 0;
  font-size: 12px;
  color: #2f7d32;
}
.danger-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}
.danger-zone-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: #c23b3f;
}
.danger-zone-text {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.btn-cancel {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  cursor: pointer;
}
.btn-cancel:hover {
  background: var(--color-bg-hover);
}
.btn-primary {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  background: var(--color-accent);
  color: white;
  border: none;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-hover);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.btn-danger {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  background: #d64545;
  color: white;
  border: none;
  cursor: pointer;
  white-space: nowrap;
}
.btn-danger:hover:not(:disabled) {
  background: #bd3737;
}
.btn-danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
