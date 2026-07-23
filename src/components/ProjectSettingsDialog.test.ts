import { createApp, h, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import ProjectSettingsDialog from './ProjectSettingsDialog.vue'

describe('ProjectSettingsDialog', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('renders nothing when closed', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp(ProjectSettingsDialog, {
      open: false,
      name: 'Core',
      identifier: 'CORE',
      inviteEmail: '',
      error: '',
      inviteMessage: '',
      isSubmitting: false,
      isInviting: false,
      canDelete: true,
      showCodex: false,
      codexRunners: [],
      codexRepositories: [],
      codexRunnerId: null,
      codexRepositoryId: null,
      codexBaseBranch: '',
      enrollmentCode: '',
      isCodexLoading: false,
      dailySummaryEnabled: false,
      isEmailSaving: false
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.modal-overlay')).toBeNull()
    app.unmount()
    host.remove()
  })

  it('emits field updates and action events', async () => {
    const onUpdateName = vi.fn()
    const onUpdateIdentifier = vi.fn()
    const onUpdateInviteEmail = vi.fn()
    const onSubmit = vi.fn()
    const onInvite = vi.fn()
    const onImport = vi.fn()
    const onDelete = vi.fn()
    const onClose = vi.fn()
    const onToggleDailySummary = vi.fn()

    const host = document.createElement('div')
    document.body.appendChild(host)

    const app = createApp({
      render() {
        return h(ProjectSettingsDialog, {
          open: true,
          name: 'Core',
          identifier: 'CORE',
          inviteEmail: '',
          error: '',
          inviteMessage: '',
          isSubmitting: false,
          isInviting: false,
          canDelete: true,
          showCodex: true,
          codexRunners: [{ id: 1, name: 'Mac', status: 'active' }],
          codexRepositories: [{ id: 2, displayName: 'Linear', repositoryKey: 'linear', defaultBranch: 'main' }],
          codexRunnerId: 1,
          codexRepositoryId: 2,
          codexBaseBranch: 'main',
          enrollmentCode: 'once-code',
          isCodexLoading: false,
          dailySummaryEnabled: false,
          isEmailSaving: false,
          'onUpdate:name': onUpdateName,
          'onUpdate:identifier': onUpdateIdentifier,
          'onUpdate:inviteEmail': onUpdateInviteEmail,
          onSubmit,
          onInvite,
          onImport,
          onDelete,
          onClose,
          onToggleDailySummary
        })
      }
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[data-testid="project-settings-name"]') as HTMLInputElement).value = 'Core 2'
    ;(host.querySelector('[data-testid="project-settings-name"]') as HTMLInputElement).dispatchEvent(
      new Event('input', { bubbles: true })
    )
    ;(host.querySelector('[data-testid="project-settings-identifier"]') as HTMLInputElement).value = 'COR'
    ;(host.querySelector('[data-testid="project-settings-identifier"]') as HTMLInputElement).dispatchEvent(
      new Event('input', { bubbles: true })
    )
    ;(host.querySelector('[data-testid="project-settings-invite-email"]') as HTMLInputElement).value = 'a@b.com'
    ;(host.querySelector('[data-testid="project-settings-invite-email"]') as HTMLInputElement).dispatchEvent(
      new Event('input', { bubbles: true })
    )

    ;(host.querySelector('[data-testid="project-settings-submit"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="project-settings-invite"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="project-settings-import"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="project-settings-delete"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="project-settings-close"]') as HTMLButtonElement).click()
    ;(host.querySelector('[data-testid="project-settings-daily-summary"]') as HTMLInputElement).checked = true
    ;(host.querySelector('[data-testid="project-settings-daily-summary"]') as HTMLInputElement).dispatchEvent(
      new Event('change', { bubbles: true })
    )
    await nextTick()

    expect(onUpdateName).toHaveBeenCalledWith('Core 2')
    expect(onUpdateIdentifier).toHaveBeenCalledWith('COR')
    expect(onUpdateInviteEmail).toHaveBeenCalledWith('a@b.com')
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onInvite).toHaveBeenCalledTimes(1)
    expect(onImport).toHaveBeenCalledTimes(1)
    expect(onDelete).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onToggleDailySummary).toHaveBeenCalledWith(true)
    expect(host.querySelector('[data-testid="codex-enrollment-code"]')?.textContent).toBe('once-code')

    app.unmount()
    host.remove()
  })
})
