import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import zhCN from '../i18n/messages/zh-CN'
import ProjectSettingsDialog from './ProjectSettingsDialog.vue'
import { getCodexHostIdentity, getCodexHostStatus } from '../services/codexHost'
import { collaborationHostApi } from '../services/api/collaborationHost'
import { projectDeviceBindingApi } from '../services/api/projectDeviceBinding'
import { getPiBridgeHealth } from '../services/piBridge'

vi.mock('../store/authStore', () => ({ useAuthStore: () => ({ currentUser: { id: 7, username: 'Alice' } }) }))
vi.mock('../services/codexHost', () => ({ getCodexHostIdentity: vi.fn(), getCodexHostStatus: vi.fn(), connectCodexHost: vi.fn(), validateCodexProjectDirectory: vi.fn() }))
vi.mock('../services/api/collaborationHost', () => ({ collaborationHostApi: { list: vi.fn(), revoke: vi.fn(), installer: vi.fn(), batches: vi.fn(), stop: vi.fn() } }))
vi.mock('../services/api/projectDeviceBinding', () => ({ projectDeviceBindingApi: { list: vi.fn(), save: vi.fn(), delete: vi.fn() } }))
vi.mock('../services/piBridge', () => ({
  configurePiBridgeInstance: vi.fn(), detectPiBridgePlatform: vi.fn(() => 'darwin'), getPiBridgeHealth: vi.fn(),
  listPiBridgeProjects: vi.fn(), piBridgeInstallerUrl: vi.fn(() => '/api/bridge/installers/darwin'), savePiBridgeProject: vi.fn(),
}))

async function settle() { await nextTick(); await new Promise((resolve) => setTimeout(resolve, 0)); await nextTick() }

describe('project settings binding entry', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(getCodexHostIdentity).mockResolvedValue({ deviceId: 'device-a', hostInstanceId: 'host-a' })
    vi.mocked(getCodexHostStatus).mockResolvedValue({ protocolVersion: 3, deviceId: 'device-a', hostInstanceId: 'host-a', processId: 9,
      connection: 'AUTH_REQUIRED', userId: null, grantId: null, deviceName: null, lastHeartbeatAt: null })
    vi.mocked(collaborationHostApi.list).mockResolvedValue([])
    vi.mocked(projectDeviceBindingApi.list).mockResolvedValue([{
      id: 1, userId: 7, projectId: 12, deviceId: 'device-a', projectDirectory: '/work/project', revision: 1,
      createdAt: '2026-11-10T10:00:00', updatedAt: '2026-11-10T10:00:00',
    }])
    vi.mocked(getPiBridgeHealth).mockResolvedValue(null)
  })

  it('shows the binding navigation beside existing settings and preserves the close action', async () => {
    const container = document.createElement('div')
    document.body.appendChild(container)
    const close = vi.fn()
    const app = createApp(ProjectSettingsDialog, {
      open: true, projectId: 12, name: 'Linear-Lite', identifier: 'LINEAR', inviteEmail: '', error: '',
      inviteMessage: '', saveMessage: '', isSubmitting: false, isInviting: false, canDelete: false,
      dailySummaryEnabled: false, isEmailSaving: false, gitlabRepositories: [], gitlabRepositoryUrl: '',
      gitlabWebhookToken: '', isGitLabLoading: false, githubRepositories: [], githubRepositoryUrl: '',
      githubWebhookSecret: '', isGitHubLoading: false, onClose: close,
    })
    app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
    app.mount(container)
    await settle()
    try {
      expect(container.querySelector('a[href="#settings-device-binding"]')?.textContent).toContain('项目目录绑定')
      expect((container.querySelector('#project-binding-directory') as HTMLInputElement).value).toBe('/work/project')
      expect(container.querySelector('#settings-local-execution')).not.toBeNull()
      expect((container.querySelector('[data-testid="project-settings-name"]') as HTMLInputElement).value).toBe('Linear-Lite')
      ;(container.querySelector('[data-testid="project-settings-close"]') as HTMLButtonElement).click()
      expect(close).toHaveBeenCalledOnce()
    } finally { app.unmount(); container.remove() }
  })
})
