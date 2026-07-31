<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { githubWebhookUrl, gitlabWebhookUrl, type GitHubRepository, type GitLabRepository } from '../services/api/project'
import {
  ArrowLeft,
  Bell,
  Download,
  Github,
  Gitlab,
  Info,
  MailPlus,
  Trash2,
  Users
} from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  name: string
  identifier: string
  inviteEmail: string
  error: string
  inviteMessage: string
  saveMessage: string
  isSubmitting: boolean
  isInviting: boolean
  canDelete: boolean
  dailySummaryEnabled: boolean
  isEmailSaving: boolean
  gitlabRepositories: GitLabRepository[]
  gitlabRepositoryUrl: string
  gitlabWebhookToken: string
  isGitLabLoading: boolean
  githubRepositories: GitHubRepository[]
  githubRepositoryUrl: string
  githubWebhookSecret: string
  isGitHubLoading: boolean
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
  'update:gitlabRepositoryUrl': [value: string]
  addGitLabRepository: []
  resetGitLabWebhookToken: [repositoryId: number]
  deleteGitLabRepository: [repositoryId: number]
  'update:githubRepositoryUrl': [value: string]
  addGitHubRepository: []
  resetGitHubWebhookSecret: [repositoryId: number]
  deleteGitHubRepository: [repositoryId: number]
}>()

const { t } = useI18n()
const settingsPage = ref<HTMLElement | null>(null)
const activeSection = ref('settings-general')
let sectionObserver: IntersectionObserver | null = null

function observeSettingsSections() {
  sectionObserver?.disconnect()
  sectionObserver = null
  if (!props.open || !settingsPage.value || typeof IntersectionObserver === 'undefined') return

  // 设置页在自身滚动容器内导航，观察章节位置以同步唯一的当前导航项。
  sectionObserver = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0]
      if (visible?.target.id) activeSection.value = visible.target.id
    },
    { root: settingsPage.value, rootMargin: '-18% 0px -68% 0px', threshold: [0, 0.1] }
  )
  settingsPage.value
    .querySelectorAll<HTMLElement>('.settings-content > .settings-section, .settings-content > .settings-cluster')
    .forEach((section) => sectionObserver?.observe(section))
}

watch(() => props.open, async (open) => {
  if (!open) {
    sectionObserver?.disconnect()
    return
  }
  await nextTick()
  observeSettingsSections()
}, { immediate: true })

onUnmounted(() => sectionObserver?.disconnect())

function onNameInput(event: Event) {
  emit('update:name', (event.target as HTMLInputElement).value)
}

function onIdentifierInput(event: Event) {
  emit('update:identifier', (event.target as HTMLInputElement).value)
}

function onInviteEmailInput(event: Event) {
  emit('update:inviteEmail', (event.target as HTMLInputElement).value)
}

function onGitLabRepositoryUrlInput(event: Event) {
  emit('update:gitlabRepositoryUrl', (event.target as HTMLInputElement).value)
}

function onGitHubRepositoryUrlInput(event: Event) {
  emit('update:githubRepositoryUrl', (event.target as HTMLInputElement).value)
}

async function copyToClipboard(value: string) {
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    // 浏览器剪贴板不可用时不影响仓库配置本身。
  }
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
  <section v-if="open" ref="settingsPage" class="settings-page" aria-labelledby="project-settings-title">
    <header class="settings-topbar">
      <div class="settings-topbar__inner">
        <button
          type="button"
          class="back-button"
          data-testid="project-settings-close"
          :aria-label="t('projectSettingsModal.backToProject')"
          @click="onClose"
        >
          <ArrowLeft aria-hidden="true" />
          <span>{{ t('projectSettingsModal.backToProject') }}</span>
        </button>
        <div class="settings-heading">
          <span class="settings-heading__identifier">{{ identifier }}</span>
          <h1 id="project-settings-title">{{ t('projectSettingsModal.title') }}</h1>
        </div>
      </div>
    </header>

    <div class="settings-layout">
      <nav class="settings-nav" :aria-label="t('projectSettingsModal.navigationLabel')">
        <a href="#settings-general" :class="{ 'is-active': activeSection === 'settings-general' }" :aria-current="activeSection === 'settings-general' ? 'location' : undefined" @click="activeSection = 'settings-general'"><Info aria-hidden="true" />{{ t('projectSettingsModal.basicTitle') }}</a>
        <a href="#settings-members" :class="{ 'is-active': activeSection === 'settings-members' }" :aria-current="activeSection === 'settings-members' ? 'location' : undefined" @click="activeSection = 'settings-members'"><Users aria-hidden="true" />{{ t('projectSettingsModal.membersNav') }}</a>
        <a href="#settings-import" :class="{ 'is-active': activeSection === 'settings-import' }" :aria-current="activeSection === 'settings-import' ? 'location' : undefined" @click="activeSection = 'settings-import'"><Download aria-hidden="true" />{{ t('projectSettingsModal.importTitle') }}</a>
        <a v-if="canDelete" href="#settings-integrations" :class="{ 'is-active': activeSection === 'settings-integrations' }" :aria-current="activeSection === 'settings-integrations' ? 'location' : undefined" @click="activeSection = 'settings-integrations'"><Gitlab aria-hidden="true" />{{ t('projectSettingsModal.integrationsNav') }}</a>
        <a v-if="canDelete" href="#settings-notifications" :class="{ 'is-active': activeSection === 'settings-notifications' }" :aria-current="activeSection === 'settings-notifications' ? 'location' : undefined" @click="activeSection = 'settings-notifications'"><Bell aria-hidden="true" />{{ t('projectSettingsModal.emailTitle') }}</a>
        <a v-if="canDelete" class="settings-nav__danger" href="#settings-danger" :class="{ 'is-active': activeSection === 'settings-danger' }" :aria-current="activeSection === 'settings-danger' ? 'location' : undefined" @click="activeSection = 'settings-danger'"><Trash2 aria-hidden="true" />{{ t('projectSettingsModal.deleteTitle') }}</a>
      </nav>

      <form class="settings-content" @submit.prevent="onSubmit">
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
        <p v-if="saveMessage" class="feedback feedback--success" role="status">{{ saveMessage }}</p>

        <section id="settings-general" class="settings-section basic-section">
          <div class="section-header">
            <h2>{{ t('projectSettingsModal.basicTitle') }}</h2>
            <p>{{ t('projectSettingsModal.basicDescription') }}</p>
          </div>
          <div class="basic-fields">
            <div class="form-group">
              <label for="project-settings-name">{{ t('projectModal.form.nameLabel') }}</label>
              <input id="project-settings-name" :value="name" type="text" class="input" data-testid="project-settings-name" @input="onNameInput" />
            </div>
            <div class="form-group form-group--identifier">
              <label for="project-settings-identifier">{{ t('projectModal.form.identifierLabel') }}</label>
              <input id="project-settings-identifier" :value="identifier" type="text" class="input input--mono" maxlength="16" data-testid="project-settings-identifier" @input="onIdentifierInput" />
            </div>
          </div>
          <div class="section-actions">
            <button type="submit" class="btn-primary" data-testid="project-settings-submit" :disabled="isSubmitting">
              {{ isSubmitting ? t('projectSettingsModal.buttons.saving') : t('projectSettingsModal.buttons.save') }}
            </button>
          </div>
        </section>

        <section id="settings-members" class="settings-section invite-zone">
          <div class="section-header">
            <div class="section-title-row"><MailPlus aria-hidden="true" /><h2>{{ t('projectSettingsModal.inviteTitle') }}</h2></div>
            <p>{{ t('projectSettingsModal.inviteDescription') }}</p>
          </div>
          <div class="invite-controls">
            <input
              :value="inviteEmail"
              type="email"
              class="input"
              data-testid="project-settings-invite-email"
              :placeholder="t('projectSettingsModal.invitePlaceholder')"
              :aria-label="t('projectSettingsModal.inviteEmailLabel')"
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
          <p v-if="inviteMessage" class="feedback feedback--success" role="status">{{ inviteMessage }}</p>
        </section>

        <section id="settings-import" class="settings-section import-zone">
          <div class="section-header">
            <h2>{{ t('projectSettingsModal.importTitle') }}</h2>
            <p>{{ t('projectSettingsModal.importDescription') }}</p>
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
        </section>

        <div v-if="canDelete" id="settings-integrations" class="settings-cluster">
          <div class="cluster-header">
            <h2>{{ t('projectSettingsModal.integrationsNav') }}</h2>
            <p>{{ t('projectSettingsModal.integrationsDescription') }}</p>
          </div>

        <section class="settings-section integration-section">
          <div class="section-header">
            <div class="section-title-row"><Gitlab aria-hidden="true" /><h3>{{ t('projectSettingsModal.gitlabTitle') }}</h3></div>
            <p>{{ t('projectSettingsModal.gitlabDescription') }}</p>
          </div>
          <div class="gitlab-controls">
            <input
              :value="gitlabRepositoryUrl"
              type="url"
              class="input"
              data-testid="project-settings-gitlab-repository-url"
              :placeholder="t('projectSettingsModal.gitlabPlaceholder')"
              :aria-label="t('projectSettingsModal.gitlabRepositoryLabel')"
              :disabled="isGitLabLoading || isSubmitting"
              @input="onGitLabRepositoryUrlInput"
            />
            <button
              type="button"
              class="btn-primary"
              data-testid="project-settings-gitlab-add"
              :disabled="isGitLabLoading || isSubmitting || !gitlabRepositoryUrl.trim()"
              @click="emit('addGitLabRepository')"
            >
              {{ isGitLabLoading ? t('projectSettingsModal.gitlabAdding') : t('projectSettingsModal.gitlabAdd') }}
            </button>
          </div>
          <div v-if="gitlabRepositories.length" class="gitlab-repository-list">
            <div v-for="repository in gitlabRepositories" :key="repository.id" class="gitlab-repository-row">
              <div class="gitlab-repository-identity">
                <code>{{ repository.repositoryUrl }}</code>
                <span>{{ repository.repositoryPath }}</span>
              </div>
              <div class="gitlab-repository-actions">
                <button type="button" class="btn-secondary" :disabled="isGitLabLoading" @click="emit('resetGitLabWebhookToken', repository.id)">
                  {{ t('projectSettingsModal.gitlabResetToken') }}
                </button>
                <button type="button" class="btn-danger-ghost gitlab-delete" :disabled="isGitLabLoading" @click="emit('deleteGitLabRepository', repository.id)">
                  {{ t('projectSettingsModal.gitlabDelete') }}
                </button>
              </div>
            </div>
          </div>
          <p v-else class="repository-empty">{{ t('projectSettingsModal.gitlabEmpty') }}</p>
          <div v-if="gitlabWebhookToken" class="gitlab-secret">
            <p>{{ t('projectSettingsModal.gitlabTokenOnce') }}</p>
            <div class="gitlab-secret-row">
              <code data-testid="project-settings-gitlab-token">{{ gitlabWebhookToken }}</code>
              <button type="button" class="btn-secondary" @click="copyToClipboard(gitlabWebhookToken)">
                {{ t('projectSettingsModal.gitlabCopy') }}
              </button>
            </div>
          </div>
          <div class="gitlab-webhook-url">
            <span>{{ t('projectSettingsModal.gitlabWebhookUrl') }}</span>
            <div class="gitlab-secret-row">
              <code>{{ gitlabWebhookUrl() }}</code>
              <button type="button" class="btn-secondary" @click="copyToClipboard(gitlabWebhookUrl())">
                {{ t('projectSettingsModal.gitlabCopy') }}
              </button>
            </div>
          </div>
        </section>

        <section class="settings-section integration-section">
          <div class="section-header"><div class="section-title-row"><Github aria-hidden="true" /><h3>{{ t('projectSettingsModal.githubTitle') }}</h3></div><p>{{ t('projectSettingsModal.githubDescription') }}</p></div>
          <div class="gitlab-controls"><input :value="githubRepositoryUrl" type="url" class="input" data-testid="project-settings-github-repository-url" placeholder="https://github.com/organization/repository" :aria-label="t('projectSettingsModal.githubRepositoryLabel')" :disabled="isGitHubLoading || isSubmitting" @input="onGitHubRepositoryUrlInput" /><button type="button" class="btn-primary" data-testid="project-settings-github-add" :disabled="isGitHubLoading || isSubmitting || !githubRepositoryUrl.trim()" @click="emit('addGitHubRepository')">{{ isGitHubLoading ? t('projectSettingsModal.gitlabAdding') : t('projectSettingsModal.gitlabAdd') }}</button></div>
          <div v-if="githubRepositories.length" class="gitlab-repository-list"><div v-for="repository in githubRepositories" :key="repository.id" class="gitlab-repository-row"><div class="gitlab-repository-identity"><code>{{ repository.repositoryUrl }}</code><span>{{ repository.repositoryPath }}</span></div><div class="gitlab-repository-actions"><button type="button" class="btn-secondary" :disabled="isGitHubLoading" @click="emit('resetGitHubWebhookSecret', repository.id)">{{ t('projectSettingsModal.gitlabResetToken') }}</button><button type="button" class="btn-danger-ghost" :disabled="isGitHubLoading" @click="emit('deleteGitHubRepository', repository.id)">{{ t('projectSettingsModal.gitlabDelete') }}</button></div></div></div><p v-else class="repository-empty">{{ t('projectSettingsModal.githubEmpty') }}</p>
          <div v-if="githubWebhookSecret" class="gitlab-secret"><p>{{ t('projectSettingsModal.githubSecretOnce') }}</p><div class="gitlab-secret-row"><code>{{ githubWebhookSecret }}</code><button type="button" class="btn-secondary" @click="copyToClipboard(githubWebhookSecret)">{{ t('projectSettingsModal.gitlabCopy') }}</button></div></div>
          <div class="gitlab-webhook-url"><span>{{ t('projectSettingsModal.webhookUrl') }}</span><div class="gitlab-secret-row"><code>{{ githubWebhookUrl() }}</code><button type="button" class="btn-secondary" @click="copyToClipboard(githubWebhookUrl())">{{ t('projectSettingsModal.gitlabCopy') }}</button></div></div>
        </section>
        </div>

        <section v-if="canDelete" id="settings-notifications" class="settings-section email-zone">
          <div class="section-header">
            <h2>{{ t('projectSettingsModal.emailTitle') }}</h2>
            <p>{{ t('projectSettingsModal.emailDescription') }}</p>
          </div>
          <label class="email-toggle">
            <span class="email-toggle__copy"><strong>{{ t('projectSettingsModal.dailySummary') }}</strong><small>{{ t('projectSettingsModal.dailySummaryDescription') }}</small></span>
            <input type="checkbox" data-testid="project-settings-daily-summary" :checked="dailySummaryEnabled" :disabled="isEmailSaving" @change="emit('toggleDailySummary', ($event.target as HTMLInputElement).checked)" />
          </label>
        </section>

        <section v-if="canDelete" id="settings-danger" class="settings-section danger-zone">
          <div class="section-header">
            <h2>{{ t('projectSettingsModal.deleteTitle') }}</h2>
            <p>{{ t('projectSettingsModal.deleteDescription') }}</p>
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
        </section>
      </form>
    </div>
  </section>
</template>

<style scoped>
.settings-page { --settings-max-width: 1040px; --settings-rail-width: 184px; flex: 1; min-width: 0; overflow: auto; scroll-behavior: smooth; background: var(--color-bg-primary); color: var(--color-text-primary); }
.settings-topbar { position: sticky; top: 0; z-index: 4; border-bottom: 1px solid var(--color-border-subtle); background: color-mix(in srgb, var(--color-bg-primary) 94%, transparent); backdrop-filter: blur(12px); }
.settings-topbar__inner { width: min(var(--settings-max-width), calc(100% - 48px)); min-height: 72px; margin: 0 auto; display: grid; grid-template-columns: var(--settings-rail-width) minmax(0, 1fr); gap: 40px; align-items: center; }
.back-button { justify-self: start; min-height: 32px; padding: 5px 8px; display: inline-flex; align-items: center; gap: 7px; border-radius: var(--radius-md); color: var(--color-text-secondary); }
.back-button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.back-button svg { width: 15px; height: 15px; }
.settings-heading { min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.settings-heading h1 { margin: 0; font-size: 17px; font-weight: var(--font-weight-semibold); letter-spacing: -0.02em; }
.settings-heading__identifier { padding: 2px 5px; border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-muted); font: 500 10px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; }
.settings-layout { width: min(var(--settings-max-width), calc(100% - 48px)); margin: 0 auto; padding: 36px 0 80px; display: grid; grid-template-columns: var(--settings-rail-width) minmax(0, 1fr); gap: 40px; align-items: start; }
.settings-nav { position: sticky; top: 108px; display: grid; gap: 2px; }
.settings-nav a { min-height: 32px; padding: 6px 8px; display: flex; align-items: center; gap: 9px; border-radius: var(--radius-md); color: var(--color-text-secondary); text-decoration: none; }
.settings-nav a:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.settings-nav a.is-active { background: var(--color-bg-active); color: var(--color-text-primary); font-weight: var(--font-weight-medium); }
.settings-nav a.is-active svg { color: var(--color-text-primary); }
.settings-nav svg { width: 15px; height: 15px; color: var(--color-text-muted); }
.settings-nav .settings-nav__danger { margin-top: 8px; color: var(--project-settings-danger-text); }
.settings-content { min-width: 0; display: grid; gap: 48px; }
.settings-section { scroll-margin-top: 104px; min-width: 0; }
.settings-section + .settings-section, .settings-cluster + .settings-section { padding-top: 48px; border-top: 1px solid var(--color-border-subtle); }
.section-header, .cluster-header { max-width: 640px; margin-bottom: 20px; }
.section-header h2, .section-header h3, .cluster-header h2, .section-header p, .cluster-header p { margin: 0; }
.section-header h2, .cluster-header h2 { font-size: 15px; font-weight: var(--font-weight-semibold); }
.section-header h3 { font-size: 14px; font-weight: var(--font-weight-semibold); }
.section-header p, .cluster-header p { margin-top: 5px; color: var(--color-text-secondary); font-size: var(--font-size-caption); line-height: 1.55; }
.section-title-row { display: flex; align-items: center; gap: 8px; }
.section-title-row svg { width: 16px; height: 16px; color: var(--color-text-muted); }
.basic-fields { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 7px; }
.form-group label { color: var(--color-text-secondary); font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.input { width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg-primary); transition: border-color var(--transition-fast), box-shadow var(--transition-fast); }
.input:focus { border-color: var(--color-accent); box-shadow: 0 0 0 3px var(--color-accent-muted); }
.input--mono { text-transform: uppercase; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.section-actions { margin-top: 16px; display: flex; justify-content: flex-end; }
.invite-controls, .gitlab-controls { display: flex; align-items: center; gap: 8px; }
.invite-controls .input, .gitlab-controls .input { flex: 1; min-width: 0; }
.feedback { margin: 0; padding: 10px 12px; border: 1px solid; border-radius: var(--radius-md); font-size: var(--font-size-caption); }
.feedback--error { color: var(--project-settings-danger-text); border-color: var(--project-settings-danger-border); background: var(--project-settings-danger-bg); }
.feedback--success { margin-top: 10px; color: var(--project-settings-success-text); border-color: color-mix(in srgb, var(--project-settings-success-text) 20%, transparent); background: var(--project-settings-success-bg); }
.settings-cluster { scroll-margin-top: 104px; display: grid; gap: 28px; }
.integration-section { padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-bg-primary); }
.gitlab-repository-list { display: grid; gap: 6px; margin-top: 14px; }
.gitlab-repository-row { min-width: 0; padding: 10px 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; border-top: 1px solid var(--color-border-subtle); }
.gitlab-repository-identity { min-width: 0; display: grid; gap: 3px; }
.gitlab-repository-identity code, .gitlab-secret code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-primary); font-size: var(--font-size-caption); }
.gitlab-repository-identity span, .gitlab-webhook-url > span { color: var(--color-text-muted); font-size: var(--font-size-xs); }
.gitlab-repository-actions, .gitlab-secret-row { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.repository-empty { margin: 12px 0 0; color: var(--color-text-muted); font-size: var(--font-size-caption); }
.gitlab-secret, .gitlab-webhook-url { display: grid; gap: 7px; margin-top: 14px; }
.gitlab-secret { padding: 12px; border-radius: var(--radius-md); background: var(--color-bg-subtle); }
.gitlab-secret p { margin: 0; color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.gitlab-secret-row code { flex: 1; min-width: 0; padding: 7px 9px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-primary); }
.email-toggle { padding: 12px 0; display: flex; align-items: center; justify-content: space-between; gap: 24px; border-top: 1px solid var(--color-border-subtle); border-bottom: 1px solid var(--color-border-subtle); }
.email-toggle__copy { display: grid; gap: 3px; }
.email-toggle__copy strong { font-size: var(--font-size-body); font-weight: var(--font-weight-medium); }
.email-toggle__copy small { color: var(--color-text-muted); font-size: var(--font-size-caption); }
.email-toggle input { width: 30px; height: 18px; accent-color: var(--color-accent); }
.danger-zone { padding: 20px; border: 1px solid var(--project-settings-danger-border); border-radius: var(--radius-lg); background: var(--project-settings-danger-bg); }
.danger-zone .section-header { margin-bottom: 16px; }
.danger-zone .section-header h2, .danger-zone .section-header p { color: var(--project-settings-danger-text); }
.btn-secondary, .btn-primary, .btn-danger, .btn-danger-ghost { min-height: 34px; padding: 7px 12px; border: 1px solid; border-radius: var(--radius-md); font-weight: var(--font-weight-medium); transition: background var(--transition-fast), border-color var(--transition-fast); }
.btn-primary { border-color: var(--color-accent); color: var(--color-text-on-accent); background: var(--color-accent); }
.btn-primary:hover:not(:disabled) { border-color: var(--color-accent-hover); background: var(--color-accent-hover); }
.btn-secondary { border-color: var(--color-border-strong); background: var(--color-bg-primary); }
.btn-secondary:hover:not(:disabled) { background: var(--color-bg-hover); }
.btn-danger { border-color: var(--project-settings-danger-text); color: #fff; background: var(--project-settings-danger-text); }
.btn-danger-ghost { border-color: transparent; color: var(--project-settings-danger-text); background: transparent; }
.btn-danger-ghost:hover:not(:disabled) { background: var(--project-settings-danger-bg); }
button:disabled, input:disabled { cursor: not-allowed; opacity: 0.55; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
@media (max-width: 760px) {
  .settings-topbar__inner, .settings-layout { width: min(100% - 32px, 640px); grid-template-columns: 1fr; gap: 0; }
  .settings-topbar__inner { min-height: 64px; grid-template-columns: auto minmax(0, 1fr); gap: 16px; }
  .settings-layout { padding-top: 20px; }
  .settings-nav { position: static; margin-bottom: 32px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .settings-content { gap: 40px; }
}
@media (max-width: 520px) {
  .back-button span, .settings-heading__identifier { display: none; }
  .settings-nav { display: flex; overflow-x: auto; padding-bottom: 6px; }
  .settings-nav a { flex: 0 0 auto; }
  .basic-fields, .invite-controls, .gitlab-controls { display: grid; grid-template-columns: 1fr; }
  .gitlab-repository-row { align-items: flex-start; flex-direction: column; }
  .gitlab-repository-actions { width: 100%; justify-content: flex-end; }
  .integration-section, .danger-zone { padding: 16px; }
}
</style>
