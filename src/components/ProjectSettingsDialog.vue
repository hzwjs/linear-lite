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
}>()

const emit = defineEmits<{
  close: []
  submit: []
  invite: []
  delete: []
  'update:name': [value: string]
  'update:identifier': [value: string]
  'update:inviteEmail': [value: string]
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
        <div class="section-panel">
          <div class="section-header">
            <p class="section-title">{{ t('projectModal.title') }}</p>
          </div>
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
        <p v-if="error" class="error-msg">{{ error }}</p>
        <div class="section-panel invite-zone">
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
        <div v-if="canDelete" class="section-panel danger-zone">
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
.modal-overlay {
  position: fixed;
  inset: 0;
  background: var(--project-settings-overlay);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.modal {
  width: min(560px, 100%);
  max-height: min(760px, calc(100vh - 40px));
  overflow: auto;
  background: var(--project-settings-surface);
  border: 1px solid var(--project-settings-border);
  border-radius: 10px;
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.18);
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--project-settings-border);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--project-settings-text);
}
.close-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  line-height: 1;
  color: var(--project-settings-muted);
  background: none;
  border-radius: 6px;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.close-btn:hover {
  color: var(--project-settings-text);
  background: var(--color-bg-hover);
}
.modal-body {
  padding: 16px 18px 18px;
}
.form-group {
  margin-bottom: 10px;
}
.form-group label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--project-settings-muted);
  margin-bottom: 5px;
}
.input {
  width: 100%;
  min-height: 34px;
  padding: 7px 10px;
  font-size: 13px;
  background: #ffffff;
  border: 1px solid var(--project-settings-border);
  border-radius: 7px;
  color: var(--project-settings-text);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
}
.input:focus {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px rgba(71, 85, 105, 0.12);
}
.section-panel {
  padding: 12px;
  border: 1px solid var(--project-settings-border);
  background: var(--project-settings-section);
  border-radius: 8px;
}
.section-header {
  margin-bottom: 10px;
}
.section-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--project-settings-text);
}
.section-text {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--project-settings-muted);
}
.error-msg {
  margin: 10px 0 0;
  padding: 8px 10px;
  border: 1px solid #fcb3b3;
  border-radius: 7px;
  background: #fff5f5;
  font-size: 12px;
  color: #e5484d;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}
.invite-zone {
  margin-top: 12px;
}
.invite-controls {
  display: flex;
  gap: 8px;
}
.invite-success {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 7px;
  background: var(--project-settings-success-bg);
  border: 1px solid #bbf7d0;
  font-size: 12px;
  color: var(--project-settings-success-text);
}
.danger-zone {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 16px;
  background: var(--project-settings-danger-bg);
  border-color: var(--project-settings-danger-border);
}
.danger-zone-title {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--project-settings-danger-text);
}
.danger-zone-text {
  margin: 4px 0 0;
  font-size: 12px;
  color: #9f1239;
}
.btn-cancel {
  padding: 8px 14px;
  border-radius: 7px;
  color: var(--project-settings-text);
  background: #ffffff;
  border: 1px solid var(--project-settings-border-strong);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}
.btn-cancel:hover {
  background: var(--color-bg-hover);
  border-color: var(--project-settings-border);
}
.btn-primary {
  padding: 8px 14px;
  border-radius: 7px;
  background: var(--sidebar-accent);
  color: white;
  border: none;
  transition: filter var(--transition-fast);
}
.btn-primary:hover:not(:disabled) {
  filter: brightness(0.95);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.btn-danger {
  padding: 8px 14px;
  border-radius: 7px;
  background: #d64545;
  color: white;
  border: none;
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
