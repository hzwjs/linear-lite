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
  LoaderCircle,
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
const copiedValue = ref<string | null>(null)
const copyState = ref<'idle' | 'success' | 'error'>('idle')
const navigationTarget = ref<string | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | undefined
let navigationResetTimer: ReturnType<typeof setTimeout> | undefined
let sectionObserver: IntersectionObserver | null = null

function syncActiveSection() {
  const root = settingsPage.value
  if (!root) return
  const rootRect = root.getBoundingClientRect()
  const sections = Array.from(root.querySelectorAll<HTMLElement>('.settings-content > .settings-section, .settings-content > .settings-cluster'))
  const markerTop = rootRect.top + rootRect.height * 0.28
  const current = sections
    .map((section) => ({ section, top: section.getBoundingClientRect().top }))
    .filter(({ top }) => top <= markerTop)
    .sort((left, right) => right.top - left.top)[0]
    ?? sections.map((section) => ({ section, top: section.getBoundingClientRect().top })).sort((left, right) => left.top - right.top)[0]
  if (current?.section.id) activeSection.value = current.section.id
}

function observeSettingsSections() {
  sectionObserver?.disconnect()
  sectionObserver = null
  if (!props.open || !settingsPage.value || typeof IntersectionObserver === 'undefined') return

  // 设置页在自身滚动容器内导航，观察章节位置以同步唯一的当前导航项。
  sectionObserver = new IntersectionObserver(
    () => {
      if (!navigationTarget.value) syncActiveSection()
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

onUnmounted(() => {
  sectionObserver?.disconnect()
  if (copyResetTimer) clearTimeout(copyResetTimer)
  if (navigationResetTimer) clearTimeout(navigationResetTimer)
})

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
    copiedValue.value = value
    copyState.value = 'success'
  } catch {
    copiedValue.value = value
    copyState.value = 'error'
  }
  if (copyResetTimer) clearTimeout(copyResetTimer)
  copyResetTimer = setTimeout(() => {
    copiedValue.value = null
    copyState.value = 'idle'
  }, 2400)
}

function copyButtonLabel(value: string) {
  if (copiedValue.value !== value) return t('projectSettingsModal.gitlabCopy')
  return copyState.value === 'success'
    ? t('projectSettingsModal.copied')
    : t('projectSettingsModal.copyFailed')
}

function navigateToSection(sectionId: string) {
  const root = settingsPage.value
  const section = root?.querySelector<HTMLElement>(`#${sectionId}`)
  if (!root || !section) return

  if (navigationResetTimer) clearTimeout(navigationResetTimer)
  navigationTarget.value = sectionId
  activeSection.value = sectionId

  // 只滚动设置页容器，避免 scrollIntoView 同时推动外层页面而回到首个区块。
  const rootRect = root.getBoundingClientRect()
  const sectionRect = section.getBoundingClientRect()
  const topbarHeight = root.querySelector<HTMLElement>('.settings-topbar')?.getBoundingClientRect().height ?? 0
  root.scrollTo({
    top: Math.max(0, root.scrollTop + sectionRect.top - rootRect.top - topbarHeight - 12),
    behavior: 'smooth'
  })

  const releaseNavigation = () => {
    if (navigationTarget.value !== sectionId) return
    navigationTarget.value = null
    syncActiveSection()
  }
  root.addEventListener('scrollend', releaseNavigation, { once: true })
  // Safari 等未实现 scrollend 的浏览器使用确定性的短暂兜底计时器。
  navigationResetTimer = setTimeout(releaseNavigation, 900)
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
          <span class="settings-heading__project" :title="name">{{ name }}</span>
          <span class="settings-heading__identifier">{{ identifier }}</span>
          <span class="settings-heading__separator" aria-hidden="true">/</span>
          <h1 id="project-settings-title">{{ t('projectSettingsModal.title') }}</h1>
        </div>
      </div>
    </header>

    <div class="settings-layout">
      <nav class="settings-nav" :aria-label="t('projectSettingsModal.navigationLabel')">
        <a href="#settings-general" :class="{ 'is-active': activeSection === 'settings-general' }" :aria-current="activeSection === 'settings-general' ? 'location' : undefined" @click.prevent="navigateToSection('settings-general')"><Info aria-hidden="true" />{{ t('projectSettingsModal.basicTitle') }}</a>
        <a href="#settings-members" :class="{ 'is-active': activeSection === 'settings-members' }" :aria-current="activeSection === 'settings-members' ? 'location' : undefined" @click.prevent="navigateToSection('settings-members')"><Users aria-hidden="true" />{{ t('projectSettingsModal.membersNav') }}</a>
        <a href="#settings-import" :class="{ 'is-active': activeSection === 'settings-import' }" :aria-current="activeSection === 'settings-import' ? 'location' : undefined" @click.prevent="navigateToSection('settings-import')"><Download aria-hidden="true" />{{ t('projectSettingsModal.importTitle') }}</a>
        <a v-if="canDelete" href="#settings-integrations" :class="{ 'is-active': activeSection === 'settings-integrations' }" :aria-current="activeSection === 'settings-integrations' ? 'location' : undefined" @click.prevent="navigateToSection('settings-integrations')"><Gitlab aria-hidden="true" />{{ t('projectSettingsModal.integrationsNav') }}</a>
        <a v-if="canDelete" href="#settings-notifications" :class="{ 'is-active': activeSection === 'settings-notifications' }" :aria-current="activeSection === 'settings-notifications' ? 'location' : undefined" @click.prevent="navigateToSection('settings-notifications')"><Bell aria-hidden="true" />{{ t('projectSettingsModal.emailTitle') }}</a>
        <a v-if="canDelete" class="settings-nav__danger" href="#settings-danger" :class="{ 'is-active': activeSection === 'settings-danger' }" :aria-current="activeSection === 'settings-danger' ? 'location' : undefined" @click.prevent="navigateToSection('settings-danger')"><Trash2 aria-hidden="true" />{{ t('projectSettingsModal.deleteTitle') }}</a>
      </nav>

      <form class="settings-content" @submit.prevent="onSubmit">
        <p v-if="error" class="feedback feedback--error" role="alert" aria-live="assertive">{{ error }}</p>
        <p v-if="saveMessage" class="feedback feedback--success" role="status" aria-live="polite">{{ saveMessage }}</p>

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
            <div class="section-actions">
              <button type="submit" class="btn-primary" :class="{ 'is-loading': isSubmitting }" data-testid="project-settings-submit" :disabled="isSubmitting" :aria-busy="isSubmitting">
                <LoaderCircle v-if="isSubmitting" class="button-spinner" aria-hidden="true" />
                {{ isSubmitting ? t('projectSettingsModal.buttons.saving') : t('projectSettingsModal.buttons.save') }}
              </button>
            </div>
          </div>
        </section>

        <section id="settings-members" class="settings-section invite-zone">
          <div class="section-header">
            <div class="section-title-row"><MailPlus aria-hidden="true" /><h2>{{ t('projectSettingsModal.inviteTitle') }}</h2></div>
            <p>{{ t('projectSettingsModal.inviteDescription') }}</p>
          </div>
          <div class="invite-controls" :aria-busy="isInviting">
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
              <LoaderCircle v-if="isInviting" class="button-spinner" aria-hidden="true" />
              {{ isInviting ? t('projectSettingsModal.inviting') : t('projectSettingsModal.inviteButton') }}
            </button>
          </div>
          <p v-if="inviteMessage" class="feedback feedback--success" role="status" aria-live="polite">{{ inviteMessage }}</p>
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
            <div class="section-title-row"><Gitlab aria-hidden="true" /><h3>{{ t('projectSettingsModal.gitlabTitle') }}</h3><span class="integration-status" :class="{ 'is-connected': gitlabRepositories.length }">{{ gitlabRepositories.length ? t('projectSettingsModal.connected') : t('projectSettingsModal.notConnected') }}</span></div>
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
              <LoaderCircle v-if="isGitLabLoading" class="button-spinner" aria-hidden="true" />
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
              <button type="button" class="btn-secondary" :class="{ 'is-success': copiedValue === gitlabWebhookToken && copyState === 'success', 'is-error': copiedValue === gitlabWebhookToken && copyState === 'error' }" @click="copyToClipboard(gitlabWebhookToken)">
                {{ copyButtonLabel(gitlabWebhookToken) }}
              </button>
            </div>
          </div>
          <div class="gitlab-webhook-url">
            <span>{{ t('projectSettingsModal.gitlabWebhookUrl') }}</span>
            <div class="gitlab-secret-row">
              <code>{{ gitlabWebhookUrl() }}</code>
              <button type="button" class="btn-secondary" :class="{ 'is-success': copiedValue === gitlabWebhookUrl() && copyState === 'success', 'is-error': copiedValue === gitlabWebhookUrl() && copyState === 'error' }" @click="copyToClipboard(gitlabWebhookUrl())">
                {{ copyButtonLabel(gitlabWebhookUrl()) }}
              </button>
            </div>
          </div>
        </section>

        <section class="settings-section integration-section">
          <div class="section-header"><div class="section-title-row"><Github aria-hidden="true" /><h3>{{ t('projectSettingsModal.githubTitle') }}</h3></div><p>{{ t('projectSettingsModal.githubDescription') }}</p></div>
          <div class="gitlab-controls"><input :value="githubRepositoryUrl" type="url" class="input" data-testid="project-settings-github-repository-url" :placeholder="t('projectSettingsModal.githubPlaceholder')" :aria-label="t('projectSettingsModal.githubRepositoryLabel')" :disabled="isGitHubLoading || isSubmitting" @input="onGitHubRepositoryUrlInput" /><button type="button" class="btn-primary" data-testid="project-settings-github-add" :disabled="isGitHubLoading || isSubmitting || !githubRepositoryUrl.trim()" :aria-busy="isGitHubLoading" @click="emit('addGitHubRepository')"><LoaderCircle v-if="isGitHubLoading" class="button-spinner" aria-hidden="true" />{{ isGitHubLoading ? t('projectSettingsModal.gitlabAdding') : t('projectSettingsModal.gitlabAdd') }}</button></div>
          <div v-if="githubRepositories.length" class="gitlab-repository-list"><div v-for="repository in githubRepositories" :key="repository.id" class="gitlab-repository-row"><div class="gitlab-repository-identity"><code>{{ repository.repositoryUrl }}</code><span>{{ repository.repositoryPath }}</span></div><div class="gitlab-repository-actions"><button type="button" class="btn-secondary" :disabled="isGitHubLoading" @click="emit('resetGitHubWebhookSecret', repository.id)">{{ t('projectSettingsModal.gitlabResetToken') }}</button><button type="button" class="btn-danger-ghost" :disabled="isGitHubLoading" @click="emit('deleteGitHubRepository', repository.id)">{{ t('projectSettingsModal.gitlabDelete') }}</button></div></div></div><p v-else class="repository-empty">{{ t('projectSettingsModal.githubEmpty') }}</p>
          <div v-if="githubWebhookSecret" class="gitlab-secret"><p>{{ t('projectSettingsModal.githubSecretOnce') }}</p><div class="gitlab-secret-row"><code>{{ githubWebhookSecret }}</code><button type="button" class="btn-secondary" :class="{ 'is-success': copiedValue === githubWebhookSecret && copyState === 'success', 'is-error': copiedValue === githubWebhookSecret && copyState === 'error' }" @click="copyToClipboard(githubWebhookSecret)">{{ copyButtonLabel(githubWebhookSecret) }}</button></div></div>
          <div class="gitlab-webhook-url"><span>{{ t('projectSettingsModal.webhookUrl') }}</span><div class="gitlab-secret-row"><code>{{ githubWebhookUrl() }}</code><button type="button" class="btn-secondary" :class="{ 'is-success': copiedValue === githubWebhookUrl() && copyState === 'success', 'is-error': copiedValue === githubWebhookUrl() && copyState === 'error' }" @click="copyToClipboard(githubWebhookUrl())">{{ copyButtonLabel(githubWebhookUrl()) }}</button></div></div>
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
/* 页面骨架：固定阅读宽度，保留设置导航与内容的共同对齐线。 */
.settings-page { --settings-max-width: 1200px; --settings-rail-width: 192px; flex: 1; min-width: 0; overflow: auto; scroll-behavior: smooth; background: var(--color-bg-primary); color: var(--color-text-primary); }
.settings-topbar { position: sticky; top: 0; z-index: 4; border-bottom: 1px solid var(--color-border-subtle); background: color-mix(in srgb, var(--color-bg-primary) 94%, transparent); backdrop-filter: blur(12px); }
.settings-topbar__inner { width: min(var(--settings-max-width), calc(100% - 48px)); min-height: 68px; margin: 0 auto; display: grid; grid-template-columns: var(--settings-rail-width) minmax(0, 1fr); gap: 36px; align-items: center; }
.back-button { justify-self: start; min-height: 32px; padding: 5px 8px; display: inline-flex; align-items: center; gap: 7px; border-radius: var(--radius-md); color: var(--color-text-secondary); }
.back-button:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.back-button svg { width: 15px; height: 15px; }
.settings-heading { min-width: 0; display: flex; align-items: baseline; gap: 9px; overflow: hidden; }
.settings-heading__project { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-secondary); font-size: 13px; font-weight: var(--font-weight-medium); }
.settings-heading h1 { margin: 0; font-size: 17px; font-weight: var(--font-weight-semibold); letter-spacing: -0.02em; }
.settings-heading__identifier { padding: 2px 5px; border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-muted); font: 500 10px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; }
.settings-heading__separator { color: var(--color-text-muted); font-size: 14px; }
.settings-layout { width: min(var(--settings-max-width), calc(100% - 48px)); margin: 0 auto; padding: 32px 0 72px; display: grid; grid-template-columns: var(--settings-rail-width) minmax(0, 1fr); gap: 36px; align-items: start; }
.settings-nav { position: sticky; top: 108px; display: grid; gap: 2px; }
.settings-nav a { min-height: 36px; padding: 7px 10px; display: flex; align-items: center; gap: 9px; border-radius: var(--radius-md); color: var(--color-text-secondary); text-decoration: none; transition: background var(--transition-fast), color var(--transition-fast), transform var(--transition-fast); }
.settings-nav a:hover { background: var(--color-bg-hover); color: var(--color-text-primary); }
.settings-nav a:active { transform: translateX(1px); }
.settings-nav a.is-active { background: var(--color-bg-active); color: var(--color-text-primary); font-weight: var(--font-weight-medium); box-shadow: inset 2px 0 0 var(--color-accent); }
.settings-nav a.is-active svg { color: var(--color-text-primary); }
.settings-nav svg { width: 15px; height: 15px; color: var(--color-text-muted); }
.settings-nav .settings-nav__danger { margin-top: 8px; color: var(--project-settings-danger-text); }
.settings-content { min-width: 0; display: grid; gap: 44px; }
.settings-section { scroll-margin-top: 104px; min-width: 0; }
.settings-section + .settings-section, .settings-cluster + .settings-section { padding-top: 48px; border-top: 1px solid var(--color-border-subtle); }
.section-header, .cluster-header { max-width: 640px; margin-bottom: 20px; }
.section-header h2, .section-header h3, .cluster-header h2, .section-header p, .cluster-header p { margin: 0; }
.section-header h2, .cluster-header h2 { font-size: 16px; font-weight: var(--font-weight-semibold); letter-spacing: -0.01em; }
.section-header h3 { font-size: 14px; font-weight: var(--font-weight-semibold); }
.section-header p, .cluster-header p { margin-top: 5px; color: var(--color-text-secondary); font-size: var(--font-size-caption); line-height: 1.55; }
.section-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.section-title-row svg { width: 16px; height: 16px; color: var(--color-text-muted); }
.integration-status { padding: 2px 7px; border: 1px solid var(--color-border); border-radius: 999px; color: var(--color-text-muted); background: var(--color-bg-subtle); font-size: var(--font-size-xs); font-weight: var(--font-weight-medium); }
.integration-status.is-connected { border-color: color-mix(in srgb, var(--project-settings-success-text) 24%, transparent); color: var(--project-settings-success-text); background: var(--project-settings-success-bg); }
.basic-fields { display: grid; grid-template-columns: minmax(0, 1fr) 160px auto; gap: 16px; align-items: end; }
.form-group { display: flex; flex-direction: column; gap: 7px; }
.form-group label { color: var(--color-text-secondary); font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.input { width: 100%; min-height: 38px; padding: 8px 11px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg-primary); transition: border-color var(--transition-fast), box-shadow var(--transition-fast), background var(--transition-fast); }
.input:hover:not(:disabled) { border-color: var(--color-text-muted); }
.input:focus { border-color: var(--color-accent); box-shadow: 0 0 0 3px var(--color-accent-muted); }
.input--mono { text-transform: uppercase; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.section-actions { display: flex; justify-content: flex-end; }
.invite-controls, .gitlab-controls { display: flex; align-items: center; gap: 8px; }
.invite-controls .input, .gitlab-controls .input { flex: 1; min-width: 0; }
.feedback { margin: 0; padding: 10px 12px; border: 1px solid; border-radius: var(--radius-md); font-size: var(--font-size-caption); }
.feedback--error { color: var(--project-settings-danger-text); border-color: var(--project-settings-danger-border); background: var(--project-settings-danger-bg); }
.feedback--success { margin-top: 10px; color: var(--project-settings-success-text); border-color: color-mix(in srgb, var(--project-settings-success-text) 20%, transparent); background: var(--project-settings-success-bg); }
.settings-cluster { scroll-margin-top: 104px; display: grid; gap: 16px; }
.settings-cluster > .cluster-header { margin-bottom: 0; }
.integration-section { padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-bg-primary); box-shadow: 0 1px 2px color-mix(in srgb, var(--color-text-primary) 4%, transparent); }
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
.btn-secondary, .btn-primary, .btn-danger, .btn-danger-ghost { min-height: 36px; padding: 7px 12px; border: 1px solid; border-radius: var(--radius-md); font-weight: var(--font-weight-medium); transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast); }
.btn-primary, .btn-secondary, .btn-danger { display: inline-flex; align-items: center; justify-content: center; gap: 7px; }
.btn-primary { border-color: var(--color-accent); color: var(--color-text-on-accent); background: var(--color-accent); }
.btn-primary:hover:not(:disabled) { border-color: var(--color-accent-hover); background: var(--color-accent-hover); }
.btn-secondary { border-color: var(--color-border-strong); background: var(--color-bg-primary); }
.btn-secondary:hover:not(:disabled) { background: var(--color-bg-hover); }
.btn-secondary.is-success { border-color: color-mix(in srgb, var(--project-settings-success-text) 30%, transparent); color: var(--project-settings-success-text); background: var(--project-settings-success-bg); }
.btn-secondary.is-error { border-color: var(--project-settings-danger-border); color: var(--project-settings-danger-text); background: var(--project-settings-danger-bg); }
.btn-danger { border-color: var(--project-settings-danger-text); color: #fff; background: var(--project-settings-danger-text); }
.btn-danger-ghost { border-color: transparent; color: var(--project-settings-danger-text); background: transparent; }
.btn-danger-ghost:hover:not(:disabled) { background: var(--project-settings-danger-bg); }
button:disabled, input:disabled { cursor: not-allowed; opacity: 0.55; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.button-spinner { width: 14px; height: 14px; animation: settings-spin 0.8s linear infinite; }
@keyframes settings-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .settings-page { scroll-behavior: auto; } .button-spinner { animation: none; } }
@media (max-width: 760px) {
  .settings-topbar__inner, .settings-layout { width: min(100% - 32px, 640px); grid-template-columns: 1fr; gap: 0; }
  .settings-topbar__inner { min-height: 64px; grid-template-columns: auto minmax(0, 1fr); gap: 16px; }
  .settings-layout { padding-top: 20px; }
  .settings-nav { position: static; margin-bottom: 32px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .settings-content { gap: 40px; }
  .basic-fields { grid-template-columns: 1fr 160px; }
  .basic-fields .section-actions { grid-column: 1 / -1; margin-top: 4px; }
}
@media (max-width: 520px) {
  .back-button span, .settings-heading__identifier, .settings-heading__separator { display: none; }
  .settings-heading { gap: 6px; }
  .settings-heading__project { max-width: 42%; }
  .settings-nav { display: flex; overflow-x: auto; padding-bottom: 6px; }
  .settings-nav a { flex: 0 0 auto; }
  .basic-fields, .invite-controls, .gitlab-controls { display: grid; grid-template-columns: 1fr; }
  .basic-fields .section-actions { grid-column: auto; justify-content: stretch; }
  .basic-fields .section-actions .btn-primary { width: 100%; }
  .gitlab-repository-row { align-items: flex-start; flex-direction: column; }
  .gitlab-repository-actions { width: 100%; justify-content: flex-end; }
  .integration-section, .danger-zone { padding: 16px; }
}
</style>
