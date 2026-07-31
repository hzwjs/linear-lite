<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  open: boolean
  name: string
  identifier: string
  inviteEmail: string
  error: string
  inviteMessage: string
  isSubmitting: boolean
  isInviting: boolean
  canDelete: boolean
  dailySummaryEnabled: boolean
  isEmailSaving: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: []
  invite: []
  import: []
  delete: []
  'update:name': [value: string]
  'update:identifier': [value: string]
  'update:inviteEmail': [value: string]
  toggleDailySummary: [value: boolean]
}>()

const { t } = useI18n()

function onNameInput(event: Event) {
  emit('update:name', (event.target as HTMLInputElement).value)
}

function onIdentifierInput(event: Event) {
  emit('update:identifier', (event.target as HTMLInputElement).value)
}

function onInviteEmailInput(event: Event) {
  emit('update:inviteEmail', (event.target as HTMLInputElement).value)
}

function onSubmit() {
  emit('submit')
}

function onInvite() {
  emit('invite')
}

function onImport() {
  emit('import')
}

function onDelete() {
  emit('delete')
}

function onClose() {
  emit('close')
}
</script>

<template>
  <div v-if="open" class="modal-overlay" @click.self="onClose">
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-header">
        <h3>{{ t('projectSettingsModal.title') }}</h3>
        <button
          type="button"
          class="close-btn"
          data-testid="project-settings-close"
          @click="onClose"
        >
          ×
        </button>
      </div>
      <form class="modal-body" @submit.prevent="onSubmit">
        <div class="settings-section basic-section">
          <div class="section-header">
            <p class="section-title">{{ t('projectSettingsModal.basicTitle') }}</p>
          </div>
          <div class="basic-fields">
          <div class="form-group">
            <label>{{ t('projectModal.form.nameLabel') }}</label>
            <input
              :value="name"
              type="text"
              class="input"
              data-testid="project-settings-name"
              @input="onNameInput"
            />
          </div>
          <div class="form-group">
            <label>{{ t('projectModal.form.identifierLabel') }}</label>
            <input
              :value="identifier"
              type="text"
              class="input"
              maxlength="16"
              data-testid="project-settings-identifier"
              @input="onIdentifierInput"
            />
          </div>
          </div>
        </div>
        <p v-if="error" class="error-msg">{{ error }}</p>
        <div class="settings-section invite-zone">
          <div class="section-header">
            <p class="section-title">{{ t('projectSettingsModal.inviteTitle') }}</p>
            <p class="section-text">{{ t('projectSettingsModal.inviteDescription') }}</p>
          </div>
          <div class="invite-controls">
            <input
              :value="inviteEmail"
              type="email"
              class="input"
              data-testid="project-settings-invite-email"
              :placeholder="t('projectSettingsModal.invitePlaceholder')"
              :disabled="isInviting || isSubmitting"
              @input="onInviteEmailInput"
            />
            <button
              type="button"
              class="btn-primary"
              data-testid="project-settings-invite"
              :disabled="isInviting || isSubmitting"
              @click="onInvite"
            >
              {{ isInviting ? t('projectSettingsModal.inviting') : t('projectSettingsModal.inviteButton') }}
            </button>
          </div>
          <p v-if="inviteMessage" class="invite-success">{{ inviteMessage }}</p>
        </div>
        <div class="settings-section import-zone">
          <div class="section-header">
            <p class="section-title">{{ t('projectSettingsModal.importTitle') }}</p>
            <p class="section-text">{{ t('projectSettingsModal.importDescription') }}</p>
          </div>
          <button
            type="button"
            class="btn-secondary"
            data-testid="project-settings-import"
            :disabled="isInviting || isSubmitting"
            @click="onImport"
          >
            {{ t('projectSettingsModal.importButton') }}
          </button>
        </div>
        <div v-if="canDelete" class="settings-section email-zone">
          <div class="section-header">
            <p class="section-title">{{ t('projectSettingsModal.emailTitle') }}</p>
            <p class="section-text">{{ t('projectSettingsModal.emailDescription') }}</p>
          </div>
          <label class="email-toggle">
            <input
              type="checkbox"
              data-testid="project-settings-daily-summary"
              :checked="dailySummaryEnabled"
              :disabled="isEmailSaving"
              @change="emit('toggleDailySummary', ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ t('projectSettingsModal.dailySummary') }}</span>
          </label>
        </div>
        <div v-if="canDelete" class="settings-section danger-zone">
          <div class="section-header">
            <p class="danger-zone-title">{{ t('projectSettingsModal.deleteTitle') }}</p>
            <p class="danger-zone-text">{{ t('projectSettingsModal.deleteDescription') }}</p>
          </div>
          <button
            type="button"
            class="btn-danger"
            data-testid="project-settings-delete"
            :disabled="isSubmitting"
            @click="onDelete"
          >
            {{ isSubmitting ? t('projectSettingsModal.deleting') : t('projectSettingsModal.deleteButton') }}
          </button>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn-cancel" @click="onClose">{{ t('common.cancel') }}</button>
          <button
            type="submit"
            class="btn-primary"
            data-testid="project-settings-submit"
            :disabled="isSubmitting"
          >
            {{ isSubmitting ? t('projectSettingsModal.buttons.saving') : t('projectSettingsModal.buttons.save') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; padding: 20px; background: rgba(15, 23, 42, 0.45); }
.modal { width: min(640px, 100%); max-height: 90vh; overflow: auto; border-radius: 12px; background: var(--color-bg-primary, #fff); box-shadow: 0 20px 50px rgba(15, 23, 42, 0.2); }
.modal-header, .modal-footer { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; }
.modal-header { border-bottom: 1px solid var(--color-border-subtle, #e5e7eb); }
.modal-header h3 { margin: 0; }
.close-btn { border: 0; background: transparent; font-size: 22px; cursor: pointer; }
.modal-body { padding: 0 16px 16px; }
.settings-section { padding: 16px 0; border-bottom: 1px solid var(--color-border-subtle, #e5e7eb); }
.section-header { margin-bottom: 10px; }
.section-title, .section-text { margin: 0; }
.section-title { font-weight: 600; }
.section-text { margin-top: 4px; color: var(--color-text-secondary, #64748b); font-size: 12px; }
.basic-fields { display: grid; grid-template-columns: minmax(0, 1fr) 156px; gap: 12px; }
.form-group { display: flex; flex-direction: column; gap: 5px; }
.form-group label { font-size: 12px; }
.input { min-height: 34px; padding: 7px 9px; border: 1px solid var(--color-border, #d1d5db); border-radius: 7px; background: #fff; }
.invite-controls, .email-toggle { display: flex; align-items: center; gap: 8px; }
.invite-controls .input { flex: 1; min-width: 0; }
.email-zone { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.danger-zone { background: #fff8f7; }
.danger-zone-title, .danger-zone-text { margin: 0; }
.danger-zone-text { margin-top: 4px; color: #b42318; font-size: 12px; }
.error-msg { margin: 10px 0 0; color: #b42318; font-size: 12px; }
.invite-success { margin: 10px 0 0; color: #15803d; font-size: 12px; }
.btn-cancel, .btn-secondary, .btn-primary, .btn-danger { min-height: 34px; padding: 7px 12px; border-radius: 7px; cursor: pointer; }
.btn-primary { border: 1px solid #4f46e5; color: #fff; background: #4f46e5; }
.btn-secondary, .btn-cancel { border: 1px solid #d1d5db; background: #fff; }
.btn-danger { border: 1px solid #dc2626; color: #fff; background: #dc2626; }
@media (max-width: 520px) { .basic-fields, .invite-controls, .email-zone { grid-template-columns: 1fr; flex-direction: column; align-items: stretch; } .invite-controls { display: grid; } .modal-overlay { padding: 8px; } }
</style>
