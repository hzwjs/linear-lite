<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { gitlabWebhookUrl } from '../services/api/codex'

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
  showCodex: boolean
  codexRunners: { id: number; name: string; status: string; lastSeenAt?: string | null }[]
  codexRepositories: { id: number; displayName: string; repositoryKey: string; defaultBranch: string }[]
  codexRunnerId: number | null
  codexRepositoryId: number | null
  codexBaseBranch: string
  codexWebhookPath: string
  codexWebhookToken: string
  enrollmentCode: string
  isCodexLoading: boolean
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
  'update:codexRunnerId': [value: number | null]
  'update:codexRepositoryId': [value: number | null]
  'update:codexBaseBranch': [value: string]
  createEnrollmentCode: []
  revokeRunner: [runnerId: number]
  saveCodexBinding: []
  resetWebhookToken: []
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
function onRunnerChange(event: Event) { const value = (event.target as HTMLSelectElement).value; emit('update:codexRunnerId', value ? Number(value) : null) }
function onRepositoryChange(event: Event) { const value = (event.target as HTMLSelectElement).value; emit('update:codexRepositoryId', value ? Number(value) : null) }
async function copyToClipboard(text: string) {
  try { await navigator.clipboard.writeText(text) } catch { /* 剪贴板不可用时静默失败 */ }
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
        <div v-if="showCodex" class="section-panel codex-zone">
          <div class="section-header">
            <p class="section-title">Codex 执行</p>
            <p class="section-text">连接本地 Runner 后，为当前项目固定一个代码仓库和基础分支。</p>
          </div>
          <div class="codex-enrollment-row">
            <button type="button" class="btn-secondary" data-testid="codex-create-enrollment" :disabled="isCodexLoading" @click="emit('createEnrollmentCode')">创建 Runner 连接码</button>
            <code v-if="enrollmentCode" class="codex-enrollment-code" data-testid="codex-enrollment-code">{{ enrollmentCode }}</code>
            <span v-else class="codex-enrollment-hint">生成一次性连接码，用于注册本地 Runner</span>
          </div>
          <div class="form-group">
            <label>Runner</label>
            <select class="input" data-testid="codex-runner-select" :value="codexRunnerId ?? ''" :disabled="isCodexLoading" @change="onRunnerChange">
              <option value="">请选择 Runner</option>
              <option v-for="runner in codexRunners" :key="runner.id" :value="runner.id">{{ runner.name }}（{{ runner.status }}）</option>
            </select>
            <button v-if="codexRunnerId" type="button" class="btn-danger codex-revoke" :disabled="isCodexLoading" @click="emit('revokeRunner', codexRunnerId)">撤销 Runner</button>
          </div>
          <div class="form-group">
            <label>仓库</label>
            <select class="input" data-testid="codex-repository-select" :value="codexRepositoryId ?? ''" :disabled="isCodexLoading || !codexRunnerId" @change="onRepositoryChange">
              <option value="">请选择仓库</option>
              <option v-for="repository in codexRepositories" :key="repository.id" :value="repository.id">{{ repository.displayName }}（{{ repository.repositoryKey }}）</option>
            </select>
          </div>
          <div class="form-group">
            <label>基础分支</label>
            <input class="input" data-testid="codex-base-branch" :value="codexBaseBranch" :disabled="isCodexLoading || !codexRepositoryId" @input="emit('update:codexBaseBranch', ($event.target as HTMLInputElement).value)" />
          </div>
          <div class="codex-save-row">
            <span class="codex-save-hint">绑定后，任务会默认派发到此 Runner。</span>
            <button type="button" class="btn-primary" data-testid="codex-save-binding" :disabled="isCodexLoading || !codexRunnerId || !codexRepositoryId || !codexBaseBranch.trim()" @click="emit('saveCodexBinding')">保存 Codex 绑定</button>
          </div>
          <div v-if="codexRunnerId && codexRepositoryId" class="webhook-zone">
            <div class="section-header">
              <p class="section-title">GitLab 提交联动</p>
              <p class="section-text">在 GitLab 项目 Settings → Webhooks 新建 Webhook，勾选 Push events，粘贴以下 URL 与 Secret token。推送提交消息中引用任务编号（如 ENG-1）时，提交会自动作为评论同步到对应任务。</p>
            </div>
            <div class="webhook-field">
              <label>URL</label>
              <div class="webhook-value-row">
                <code class="webhook-value">{{ gitlabWebhookUrl() }}</code>
                <button type="button" class="btn-secondary webhook-copy" :disabled="isCodexLoading" @click="copyToClipboard(gitlabWebhookUrl())">复制</button>
              </div>
            </div>
            <div v-if="codexWebhookToken" class="webhook-field">
              <label>Secret token（仅显示一次）</label>
              <div class="webhook-value-row">
                <code class="webhook-value" data-testid="codex-webhook-token">{{ codexWebhookToken }}</code>
                <button type="button" class="btn-secondary webhook-copy" :disabled="isCodexLoading" @click="copyToClipboard(codexWebhookToken)">复制</button>
              </div>
            </div>
            <div class="webhook-meta-row">
              <span v-if="codexWebhookPath" class="codex-save-hint">已接收来自 GitLab 项目 {{ codexWebhookPath }} 的推送。</span>
              <span v-else class="codex-save-hint">保存绑定后，首次 GitLab 推送会回填项目身份。</span>
              <button type="button" class="btn-secondary" data-testid="codex-reset-webhook-token" :disabled="isCodexLoading" @click="emit('resetWebhookToken')">重置 Secret token</button>
            </div>
          </div>
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
.import-zone {
  margin-top: 12px;
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 16px;
}
.codex-zone {
  margin-top: 14px;
  padding: 18px;
  border-color: #dfe4ec;
  border-radius: 12px;
  background: linear-gradient(180deg, #fbfcfe 0%, #f7f9fc 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85), 0 1px 2px rgba(15, 23, 42, 0.03);
}
.codex-zone .section-header {
  margin-bottom: 16px;
}
.codex-zone .section-title {
  font-size: 15px;
  line-height: 20px;
  letter-spacing: -0.01em;
}
.codex-zone .section-text {
  max-width: 46em;
  margin-top: 5px;
  font-size: 12px;
  line-height: 18px;
}
.codex-enrollment-row {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  min-height: 48px;
  margin-bottom: 18px;
  padding: 8px;
  border: 1px solid #e4e8ef;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.82);
}
.codex-enrollment-code,
.codex-enrollment-hint {
  min-width: 0;
  overflow: hidden;
  padding: 0 3px;
  color: #475569;
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.codex-enrollment-code {
  color: #172033;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.codex-enrollment-hint {
  color: #8a94a5;
}
.codex-zone .form-group {
  margin-bottom: 14px;
}
.codex-zone .form-group label {
  margin-bottom: 6px;
  color: #5f6b7d;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
}
.codex-zone .input {
  min-height: 40px;
  padding: 9px 12px;
  border-color: #dfe4ec;
  border-radius: 9px;
  background: #ffffff;
  font-size: 13px;
  box-shadow: 0 1px 1px rgba(15, 23, 42, 0.02);
}
.codex-zone select.input {
  appearance: none;
  padding-right: 36px;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 14 14' fill='none'%3E%3Cpath d='m3.5 5.25 3.5 3.5 3.5-3.5' stroke='%236b778c' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-position: right 12px center;
  background-repeat: no-repeat;
}
.codex-zone .input:hover:not(:disabled) {
  border-color: #c7cfdb;
}
.codex-zone .input:focus {
  border-color: #64748b;
  box-shadow: 0 0 0 3px rgba(100, 116, 139, 0.14), 0 1px 1px rgba(15, 23, 42, 0.02);
}
.codex-zone .input:disabled {
  color: #a0a9b8;
  background: #f5f7fa;
  cursor: not-allowed;
}
.codex-revoke {
  margin-top: 7px;
  padding: 0;
  min-height: 22px;
  color: #b42318;
  background: transparent;
  font-size: 12px;
  font-weight: 500;
}
.codex-revoke:hover:not(:disabled) {
  color: #8f1d15;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.codex-save-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 18px;
}
.codex-save-hint {
  color: #8a94a5;
  font-size: 11px;
  line-height: 16px;
}
.webhook-zone {
  margin-top: 14px;
  padding-top: 16px;
  border-top: 1px solid #e6eaf1;
}
.webhook-zone .section-header {
  margin-bottom: 14px;
}
.webhook-field {
  margin-bottom: 12px;
}
.webhook-field label {
  display: block;
  margin-bottom: 6px;
  color: #5f6b7d;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
}
.webhook-value-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.webhook-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  padding: 8px 10px;
  border: 1px solid #e2e7ef;
  border-radius: 8px;
  background: #f4f6fa;
  color: #334155;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.webhook-copy {
  flex-shrink: 0;
  min-height: 34px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
}
.webhook-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 4px;
}
.codex-zone .btn-secondary,
.codex-zone .btn-primary {
  min-height: 36px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  transition: transform 160ms cubic-bezier(0.23, 1, 0.32, 1), background 120ms ease, border-color 120ms ease, box-shadow 120ms ease, opacity 120ms ease;
}
.codex-zone .btn-secondary {
  border-color: #cfd6e1;
  box-shadow: 0 1px 1px rgba(15, 23, 42, 0.03);
}
.codex-zone .btn-secondary:hover:not(:disabled) {
  border-color: #b8c2d0;
  background: #f8fafc;
  box-shadow: 0 2px 4px rgba(15, 23, 42, 0.06);
}
.codex-zone .btn-primary {
  background: #475569;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.14);
}
.codex-zone .btn-primary:hover:not(:disabled) {
  filter: none;
  background: #334155;
  box-shadow: 0 2px 5px rgba(15, 23, 42, 0.16);
}
.codex-zone .btn-primary:disabled {
  background: #a2adbc;
  opacity: 1;
  box-shadow: none;
}
.codex-zone .btn-secondary:active:not(:disabled),
.codex-zone .btn-primary:active:not(:disabled),
.codex-zone .codex-revoke:active:not(:disabled) {
  transform: scale(0.98);
}
@media (max-width: 520px) {
  .codex-zone {
    padding: 14px;
  }
  .codex-enrollment-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .codex-enrollment-code,
  .codex-enrollment-hint {
    padding: 2px 3px 0;
  }
  .codex-save-row {
    align-items: stretch;
    flex-direction: column-reverse;
    gap: 8px;
  }
  .codex-zone .btn-primary[data-testid="codex-save-binding"] {
    width: 100%;
  }
}
@media (prefers-reduced-motion: reduce) {
  .codex-zone .btn-secondary,
  .codex-zone .btn-primary,
  .codex-zone .codex-revoke {
    transition: none;
  }
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
.btn-secondary {
  padding: 8px 14px;
  border-radius: 7px;
  color: var(--project-settings-text);
  background: #ffffff;
  border: 1px solid var(--project-settings-border-strong);
  white-space: nowrap;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}
.btn-secondary:hover:not(:disabled) {
  background: var(--color-bg-hover);
  border-color: var(--project-settings-border);
}
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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

/* Project settings visual system: one rhythm for every section and action. */
.modal-overlay {
  padding: 16px;
  background: rgba(15, 23, 42, 0.38);
}
.modal {
  max-height: min(760px, calc(100vh - 32px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-color: #dfe4ec;
  border-radius: 16px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.2), 0 4px 12px rgba(15, 23, 42, 0.08);
}
.modal-header {
  flex: 0 0 auto;
  min-height: 58px;
  padding: 14px 20px;
  border-bottom-color: #e5e9f0;
}
.modal-header h3 {
  color: #111827;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.025em;
  line-height: 28px;
}
.modal-header .close-btn {
  width: 32px;
  height: 32px;
  margin-right: -4px;
  border-radius: 8px;
  font-size: 28px;
  font-weight: 300;
}
.modal-body {
  min-height: 0;
  overflow: auto;
  padding: 18px;
  scrollbar-color: #cbd3df transparent;
  scrollbar-width: thin;
}
.modal-body::-webkit-scrollbar {
  width: 8px;
}
.modal-body::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: #cbd3df;
  background-clip: padding-box;
}
.section-panel {
  padding: 16px;
  border-color: #e1e6ee;
  border-radius: 12px;
  background: #fbfcfe;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
}
.section-header {
  margin-bottom: 14px;
}
.section-title {
  color: #172033;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 20px;
}
.section-text {
  margin-top: 5px;
  color: #718096;
  font-size: 13px;
  line-height: 19px;
}
.form-group {
  margin-bottom: 14px;
}
.form-group:last-child {
  margin-bottom: 0;
}
.form-group label {
  margin-bottom: 7px;
  color: #657187;
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.01em;
  line-height: 17px;
}
.modal .input {
  min-height: 40px;
  padding: 9px 12px;
  border-color: #dfe4ec;
  border-radius: 10px;
  background: #ffffff;
  font-size: 13px;
  transition: border-color 120ms ease, box-shadow 120ms ease, background 120ms ease;
}
.modal .input:hover:not(:disabled) {
  border-color: #c7cfdb;
}
.modal .input:focus {
  border-color: #64748b;
  box-shadow: 0 0 0 3px rgba(100, 116, 139, 0.14);
  outline: none;
}
.modal .input:disabled {
  color: #a0a9b8;
  background: #f5f7fa;
  cursor: not-allowed;
}
.error-msg {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-color: #fecaca;
  border-radius: 10px;
  background: #fff7f7;
  color: #b42318;
  line-height: 18px;
}
.invite-zone,
.import-zone,
.codex-zone,
.danger-zone {
  margin-top: 12px;
}
.invite-controls {
  display: flex;
  align-items: stretch;
  gap: 10px;
}
.invite-controls .input {
  min-width: 0;
  flex: 1 1 auto;
}
.invite-controls .btn-primary {
  flex: 0 0 auto;
}
.invite-success {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 18px;
}
.import-zone {
  grid-template-columns: minmax(0, 1fr) auto;
}
.codex-zone {
  padding: 18px;
  border-color: #d9e2ee;
  border-radius: 14px;
  background: linear-gradient(180deg, #fbfcfe 0%, #f7f9fc 100%);
}
.codex-zone .section-header {
  margin-bottom: 16px;
}
.codex-zone .section-text {
  max-width: 46em;
}
.codex-enrollment-row {
  min-height: 48px;
  margin-bottom: 18px;
  padding: 8px;
  border-color: #e0e7f0;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.82);
}
.codex-zone .input {
  border-radius: 10px;
}
.codex-zone select.input {
  appearance: none;
  padding-right: 36px;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 14 14' fill='none'%3E%3Cpath d='m3.5 5.25 3.5 3.5 3.5-3.5' stroke='%236b778c' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-position: right 12px center;
  background-repeat: no-repeat;
}
.codex-save-row {
  margin-top: 18px;
}
.danger-zone {
  border-color: #fecdca;
  background: #fff8f7;
}
.danger-zone-title {
  color: #b42318;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
}
.danger-zone-text {
  margin-top: 5px;
  color: #9f1239;
  line-height: 18px;
}
.modal-footer {
  margin-top: 16px;
  padding-top: 16px;
  border-top-color: #e7ebf1;
}
.btn-cancel,
.btn-secondary,
.btn-primary,
.btn-danger {
  min-height: 40px;
  padding: 9px 14px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
  transition: transform 160ms cubic-bezier(0.23, 1, 0.32, 1), background 120ms ease, border-color 120ms ease, box-shadow 120ms ease, color 120ms ease, opacity 120ms ease;
}
.btn-cancel,
.btn-secondary {
  border: 1px solid #cfd6e1;
  color: #172033;
  background: #ffffff;
  box-shadow: 0 1px 1px rgba(15, 23, 42, 0.03);
}
.btn-cancel:hover:not(:disabled),
.btn-secondary:hover:not(:disabled) {
  border-color: #b8c2d0;
  background: #f8fafc;
  box-shadow: 0 2px 4px rgba(15, 23, 42, 0.06);
}
.btn-primary {
  border: 1px solid #475569;
  color: #ffffff;
  background: #475569;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.14);
}
.btn-primary:hover:not(:disabled) {
  border-color: #334155;
  background: #334155;
  box-shadow: 0 2px 5px rgba(15, 23, 42, 0.16);
  filter: none;
}
.btn-danger {
  border: 1px solid #d64545;
  color: #ffffff;
  background: #d64545;
}
.btn-danger:hover:not(:disabled) {
  border-color: #bd3737;
  background: #bd3737;
}
.btn-cancel:disabled,
.btn-secondary:disabled,
.btn-primary:disabled,
.btn-danger:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.btn-primary:disabled {
  border-color: #a2adbc;
  background: #a2adbc;
  box-shadow: none;
}
.btn-danger:disabled {
  border-color: #e49a9a;
  background: #e49a9a;
}
.btn-cancel:active:not(:disabled),
.btn-secondary:active:not(:disabled),
.btn-primary:active:not(:disabled),
.btn-danger:active:not(:disabled),
.close-btn:active {
  transform: scale(0.98);
}
.close-btn:hover {
  color: #172033;
  background: #f1f4f8;
}
.codex-revoke {
  min-height: 22px;
  margin-top: 7px;
  padding: 0;
  border: 0;
  color: #b42318;
  background: transparent;
  font-size: 12px;
  font-weight: 500;
}
.codex-revoke:hover:not(:disabled) {
  color: #8f1d15;
  background: transparent;
  box-shadow: none;
  text-decoration: underline;
  text-underline-offset: 3px;
}
@media (max-width: 520px) {
  .modal-overlay {
    align-items: flex-start;
    padding: 8px;
  }
  .modal {
    max-height: calc(100vh - 16px);
    border-radius: 14px;
  }
  .modal-header {
    padding-inline: 16px;
  }
  .modal-body {
    padding: 12px;
  }
  .section-panel {
    padding: 14px;
  }
  .invite-controls,
  .import-zone,
  .danger-zone {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }
  .invite-controls {
    display: grid;
  }
  .invite-controls .btn-primary,
  .import-zone .btn-secondary,
  .danger-zone .btn-danger {
    width: 100%;
  }
  .codex-zone {
    padding: 14px;
  }
  .codex-enrollment-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .codex-enrollment-code,
  .codex-enrollment-hint {
    padding: 2px 3px 0;
  }
  .codex-save-row {
    align-items: stretch;
    flex-direction: column-reverse;
    gap: 8px;
  }
  .codex-zone .btn-primary[data-testid="codex-save-binding"] {
    width: 100%;
  }
  .modal-footer {
    flex-direction: column-reverse;
  }
  .modal-footer .btn-cancel,
  .modal-footer .btn-primary {
    width: 100%;
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn-cancel,
  .btn-secondary,
  .btn-primary,
  .btn-danger,
  .close-btn {
    transition: none;
  }
}

/* Settings surfaces stay close to the app shell: one panel, compact rows,
   and dividers instead of a stack of elevated cards. */
.modal {
  width: min(640px, 100%);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-popover);
}
.modal-header {
  min-height: 48px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border-subtle);
}
.modal-header h3 {
  font-size: 15px;
  font-weight: var(--font-weight-semibold);
  letter-spacing: var(--letter-spacing);
  line-height: 20px;
}
.modal-header .close-btn {
  width: 28px;
  height: 28px;
  margin-right: -4px;
  font-size: 22px;
}
.modal-body {
  padding: 0 16px 12px;
}
.settings-section {
  padding: 16px 0;
  border-bottom: 1px solid var(--color-border-subtle);
  background: transparent;
}
.settings-section .section-header {
  margin-bottom: 10px;
}
.settings-section .section-title {
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
  line-height: 18px;
}
.settings-section .section-text {
  margin-top: 2px;
  font-size: 12px;
  line-height: 17px;
}
.basic-section {
  padding-top: 14px;
}
.basic-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 156px;
  gap: 12px;
}
.form-group,
.codex-zone .form-group {
  margin-bottom: 0;
}
.form-group label {
  margin-bottom: 5px;
  font-size: 11px;
  line-height: 15px;
}
.modal .input {
  min-height: 34px;
  padding: 7px 9px;
  border-radius: var(--radius-md);
  font-size: 13px;
  box-shadow: none;
}
.invite-zone,
.import-zone,
.email-zone,
.danger-zone,
.codex-zone {
  margin-top: 0;
}
.invite-controls {
  gap: 8px;
}
.import-zone,
.danger-zone {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
}
.import-zone .section-header,
.danger-zone .section-header {
  margin-bottom: 0;
}
.email-zone {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.email-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  color: var(--color-text-primary);
  font-size: 13px;
  white-space: nowrap;
}
.email-toggle input {
  accent-color: var(--color-accent);
}
.codex-zone {
  padding: 16px 0;
  border: 0;
  border-bottom: 1px solid var(--color-border-subtle);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.codex-zone .section-header {
  margin-bottom: 10px;
}
.codex-enrollment-row {
  min-height: 38px;
  margin-bottom: 12px;
  padding: 4px;
  border-radius: var(--radius-md);
  background: var(--color-bg-subtle);
  box-shadow: none;
}
.codex-zone .input {
  min-height: 34px;
  padding: 7px 9px;
  border-radius: var(--radius-md);
  box-shadow: none;
}
.codex-zone .form-group {
  margin-bottom: 10px;
}
.codex-save-row {
  margin-top: 12px;
}
.danger-zone {
  border-bottom: 0;
  background: transparent;
}
.danger-zone-title {
  font-size: 13px;
}
.danger-zone-text {
  font-size: 12px;
}
.error-msg {
  margin: 10px 0 0;
  border-radius: var(--radius-md);
}
.modal-footer {
  margin-top: 0;
  padding: 12px 0 0;
  border-top: 0;
}
.btn-cancel,
.btn-secondary,
.btn-primary,
.btn-danger {
  min-height: 34px;
  padding: 7px 12px;
  border-radius: var(--radius-md);
  font-size: 12px;
  line-height: 18px;
}
@media (max-width: 520px) {
  .basic-fields {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .email-zone {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}
</style>
