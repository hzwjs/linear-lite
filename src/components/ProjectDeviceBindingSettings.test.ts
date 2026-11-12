import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import zhCN from '../i18n/messages/zh-CN'
import ProjectDeviceBindingSettings from './ProjectDeviceBindingSettings.vue'
import { connectCodexHost, getCodexHostIdentity, getCodexHostStatus, validateCodexProjectDirectory } from '../services/codexHost'
import { collaborationHostApi } from '../services/api/collaborationHost'
import { collaborationBatchApi, type CollaborationBatchSummary } from '../services/api/collaborationBatch'
import { projectDeviceBindingApi, type ProjectDeviceBinding } from '../services/api/projectDeviceBinding'

vi.mock('../store/authStore', () => ({ useAuthStore: () => ({ currentUser: { id: 7, username: 'Alice' } }) }))
vi.mock('../services/codexHost', () => ({ getCodexHostIdentity: vi.fn(), getCodexHostStatus: vi.fn(), connectCodexHost: vi.fn(), validateCodexProjectDirectory: vi.fn() }))
vi.mock('../services/api/collaborationHost', () => ({ collaborationHostApi: { list: vi.fn(), revoke: vi.fn(), installer: vi.fn(), batches: vi.fn(), stop: vi.fn() } }))
vi.mock('../services/api/collaborationBatch', () => ({ collaborationBatchApi: { listAll: vi.fn(), stop: vi.fn() } }))
vi.mock('../services/api/projectDeviceBinding', () => ({
  projectDeviceBindingApi: { list: vi.fn(), save: vi.fn(), delete: vi.fn() },
}))

const local: ProjectDeviceBinding = {
  id: 1, userId: 7, projectId: 12, deviceId: 'device-a', projectDirectory: '/work/project',
  revision: 1, createdAt: '2026-11-10T10:00:00', updatedAt: '2026-11-10T10:00:00',
}
const remote: ProjectDeviceBinding = {
  ...local, id: 2, deviceId: 'device-b', projectDirectory: 'C:\\work\\project',
}
const status = { protocolVersion: 3 as const, deviceId: 'device-a', hostInstanceId: 'host-a', processId: 34, connection: 'ONLINE' as const,
  userId: 7, grantId: 8, deviceName: 'Mac', lastHeartbeatAt: '2026-11-10T10:00:00Z' }
const grant = { id: 8, userId: 7, deviceId: 'device-a', deviceName: 'Mac', authorized: true,
  online: true, lastHeartbeatAt: '2026-11-10T10:00:00Z', createdAt: '2026-11-10T09:00:00Z' }

function flushPromises() { return new Promise((resolve) => setTimeout(resolve, 0)) }
async function settle() { await nextTick(); await flushPromises(); await nextTick() }

async function mountSettings() {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const props = reactive({ projectId: 12, projectName: 'Linear-Lite' })
  const app = createApp({ render: () => h(ProjectDeviceBindingSettings, props) })
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
  app.mount(container)
  await settle()
  return { container, props, unmount: () => { app.unmount(); container.remove() } }
}

function input(container: HTMLElement): HTMLInputElement {
  return container.querySelector('#project-binding-directory') as HTMLInputElement
}

function select(container: HTMLElement): HTMLSelectElement {
  return container.querySelector('#project-binding-device') as HTMLSelectElement
}

function enterDirectory(container: HTMLElement, value: string) {
  const field = input(container)
  field.value = value
  field.dispatchEvent(new Event('input'))
}

describe('ProjectDeviceBindingSettings', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(getCodexHostIdentity).mockResolvedValue({ deviceId: 'device-a', hostInstanceId: 'host-a' })
    vi.mocked(getCodexHostStatus).mockResolvedValue(status)
    vi.mocked(connectCodexHost).mockResolvedValue({ ...status, connection: 'CONNECTING' })
    vi.mocked(collaborationHostApi.list).mockResolvedValue([grant])
    vi.mocked(collaborationBatchApi.listAll).mockResolvedValue([])
    vi.mocked(collaborationHostApi.revoke).mockResolvedValue()
    vi.mocked(collaborationBatchApi.stop).mockResolvedValue({ batchId: 'batch-1', lifecycle: 'accepted', desiredIntent: 'STOP', intentRevision: 2, displayState: 'STOPPING' })
    vi.mocked(projectDeviceBindingApi.list).mockResolvedValue([local, remote])
    vi.mocked(projectDeviceBindingApi.delete).mockResolvedValue()
    vi.mocked(validateCodexProjectDirectory).mockResolvedValue({
      deviceId: 'device-a', projectDirectory: '/real/project', docsDirectory: '/real/project/docs',
    })
    vi.mocked(projectDeviceBindingApi.save).mockResolvedValue({ ...local, projectDirectory: '/real/project', revision: 2 })
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the current user, project and device binding; other devices are read only', async () => {
    const view = await mountSettings()
    try {
      expect(view.container.textContent).toContain('Alice')
      expect(view.container.textContent).toContain('Linear-Lite')
      expect(select(view.container).value).toBe('device-a')
      expect(input(view.container).value).toBe('/work/project')
      expect(view.container.querySelector('output')?.textContent).toBe('/work/project/docs')
      expect(projectDeviceBindingApi.list).toHaveBeenCalledWith(12)
      select(view.container).value = 'device-b'
      select(view.container).dispatchEvent(new Event('change'))
      await nextTick()
      expect(input(view.container).value).toBe('C:\\work\\project')
      expect(input(view.container).readOnly).toBe(true)
      expect(view.container.querySelector('output')?.textContent).toBe('C:\\work\\project\\docs')
      expect(view.container.querySelector('.binding-actions')).toBeNull()
    } finally { view.unmount() }
  })

  it('preserves a POSIX directory name ending in a backslash when deriving docs', async () => {
    vi.mocked(projectDeviceBindingApi.list).mockResolvedValueOnce([{ ...local, projectDirectory: '/work/name\\' }])
    const view = await mountSettings()
    try {
      expect(input(view.container).value).toBe('/work/name\\')
      expect(view.container.querySelector('output')?.textContent).toBe('/work/name\\/docs')
    } finally { view.unmount() }
  })

  it('validates the local path and saves the canonical directory', async () => {
    const view = await mountSettings()
    try {
      enterDirectory(view.container, '/link/project')
      await nextTick()
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await settle()
      expect(validateCodexProjectDirectory).toHaveBeenCalledWith('device-a', '/link/project')
      expect(projectDeviceBindingApi.save).toHaveBeenCalledWith(12, 'device-a', '/real/project')
      expect(input(view.container).value).toBe('/real/project')
      expect(view.container.querySelector('output')?.textContent).toBe('/real/project/docs')
      expect(view.container.textContent).toContain('项目目录绑定已保存')
    } finally { view.unmount() }
  })

  it('does not save if the host rejects a missing directory or reports another device', async () => {
    vi.mocked(validateCodexProjectDirectory).mockRejectedValueOnce(new Error('项目目录不存在'))
      .mockResolvedValueOnce({ deviceId: 'device-b', projectDirectory: '/work/project', docsDirectory: '/work/project/docs' })
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await settle()
      expect(view.container.textContent).toContain('项目目录不存在')
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await settle()
      expect(view.container.textContent).toContain('设备身份发生变化')
      expect(projectDeviceBindingApi.save).not.toHaveBeenCalled()
    } finally { view.unmount() }
  })

  it('shows the server reason when an execution keeps the binding frozen', async () => {
    vi.mocked(projectDeviceBindingApi.save).mockRejectedValueOnce(new Error('执行记录等待停止确认，项目绑定保持冻结'))
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await settle()
      expect(view.container.querySelector('[role="alert"]')?.textContent).toContain('等待停止确认')
      expect(projectDeviceBindingApi.save).toHaveBeenCalledTimes(1)
    } finally { view.unmount() }
  })

  it('requires the current host identity before deleting the binding', async () => {
    vi.mocked(getCodexHostIdentity).mockResolvedValueOnce({ deviceId: 'device-b', hostInstanceId: 'host-b' })
      .mockResolvedValueOnce({ deviceId: 'device-a', hostInstanceId: 'host-a' })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-secondary') as HTMLButtonElement).click()
      await settle()
      expect(view.container.textContent).toContain('设备身份发生变化')
      expect(projectDeviceBindingApi.delete).not.toHaveBeenCalled()
      ;(view.container.querySelector('.binding-actions .btn-secondary') as HTMLButtonElement).click()
      await settle()
      expect(projectDeviceBindingApi.delete).toHaveBeenCalledWith(12, 'device-a')
      expect(input(view.container).value).toBe('')
      expect(select(view.container).options.length).toBe(2)
      expect(view.container.textContent).toContain('项目目录绑定已删除')
    } finally { view.unmount() }
  })

  it('keeps the directory visible when deletion awaits stop confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(projectDeviceBindingApi.delete).mockRejectedValueOnce(new Error('执行记录等待停止确认，项目绑定保持冻结'))
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-secondary') as HTMLButtonElement).click()
      await settle()
      expect(projectDeviceBindingApi.delete).toHaveBeenCalledWith(12, 'device-a')
      expect(view.container.querySelector('[role="alert"]')?.textContent).toContain('等待停止确认')
      expect(input(view.container).value).toBe('/work/project')
    } finally { view.unmount() }
  })

  it('keeps remote bindings readable when the local host is unavailable', async () => {
    vi.mocked(getCodexHostStatus).mockRejectedValueOnce(new Error('无法连接本机协作宿主'))
    const view = await mountSettings()
    try {
      expect(view.container.textContent).toContain('无法连接本机协作宿主')
      expect(select(view.container).value).toBe('device-a')
      expect(input(view.container).readOnly).toBe(true)
      expect(view.container.querySelector('.binding-actions')).toBeNull()
      expect(view.container.textContent).toContain('连接受阻')
      expect(view.container.textContent).toContain('服务器显示在线')
    } finally { view.unmount() }
  })

  it('offers the installer when a legacy local host needs an upgrade', async () => {
    vi.mocked(getCodexHostStatus).mockRejectedValueOnce(new Error('本机协作宿主协议需要升级，请下载新版宿主'))
    const view = await mountSettings()
    try {
      expect(view.container.textContent).toContain('本机协作宿主协议需要升级')
      expect(input(view.container).value).toBe('/work/project')
      expect(input(view.container).readOnly).toBe(true)
      expect([...view.container.querySelectorAll('button')].some(button => button.textContent?.includes('下载宿主'))).toBe(true)
      expect(view.container.querySelector('.binding-host-actions .btn-primary')).toBeNull()
      expect(connectCodexHost).not.toHaveBeenCalled()
    } finally { view.unmount() }
  })

  it('reports a server protocol mismatch without starting a new authorization', async () => {
    vi.mocked(getCodexHostStatus).mockResolvedValueOnce({ ...status, connection: 'UPGRADE_REQUIRED' })
    const view = await mountSettings()
    try {
      expect(view.container.textContent).toContain('宿主与服务器协议不匹配')
      expect(input(view.container).value).toBe('/work/project')
      expect(view.container.querySelector('.binding-host-actions .btn-primary')).toBeNull()
      expect(connectCodexHost).not.toHaveBeenCalled()
    } finally { view.unmount() }
  })

  it('opens local authorization without supplying web identity or tokens', async () => {
    const view = await mountSettings()
    try {
      const button = [...view.container.querySelectorAll('button')].find((item) => item.textContent?.includes('重新授权'))
      button?.click()
      await settle()
      expect(connectCodexHost).toHaveBeenCalledWith()
      expect(view.container.textContent).toContain('连接中')
    } finally { view.unmount() }
  })

  it('revokes the selected grant on the server and reloads server facts', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(collaborationHostApi.list).mockResolvedValueOnce([grant]).mockResolvedValueOnce([{ ...grant, authorized: false, online: false }])
    const view = await mountSettings()
    try {
      const button = [...view.container.querySelectorAll('button')].find((item) => item.textContent?.includes('撤销授权'))
      button?.click()
      await settle()
      expect(collaborationHostApi.revoke).toHaveBeenCalledWith(8)
      expect(view.container.textContent).toContain('授权失效')
      expect(view.container.textContent).toContain('服务器显示离线')
    } finally { view.unmount() }
  })

  it('revokes a different device through the server without invoking the local host', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(collaborationHostApi.list).mockResolvedValueOnce([{ ...grant, id: 9, deviceId: 'device-b', deviceName: 'Remote Mac' }])
    const view = await mountSettings()
    try {
      const button = [...view.container.querySelectorAll('button')].find((item) => item.textContent?.includes('撤销授权'))
      button?.click()
      await settle()
      expect(collaborationHostApi.revoke).toHaveBeenCalledWith(9)
    } finally { view.unmount() }
  })

  it('does not present a local authorization belonging to another account as this user', async () => {
    vi.mocked(getCodexHostStatus).mockResolvedValueOnce({ ...status, userId: 99 })
    const view = await mountSettings()
    try { expect(view.container.textContent).toContain('其他账号') }
    finally { view.unmount() }
  })

  it('provides a stop request when the server blocks a directory change, without releasing the binding', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(projectDeviceBindingApi.save).mockRejectedValueOnce(new Error('协作批次等待停止确认，项目绑定保持冻结'))
    vi.mocked(collaborationBatchApi.listAll).mockResolvedValueOnce([{ batchId: 'batch-1', bindingId: 1, lifecycle: 'accepted', desiredIntent: 'RUN' } as CollaborationBatchSummary])
      .mockResolvedValueOnce([{ batchId: 'batch-1', bindingId: 1, lifecycle: 'accepted', desiredIntent: 'STOP' } as CollaborationBatchSummary])
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await settle()
      expect(view.container.textContent).toContain('等待停止确认')
      const button = [...view.container.querySelectorAll('button')].find((item) => item.textContent?.includes('停止批次'))
      button?.click()
      await settle()
      expect(collaborationBatchApi.stop).toHaveBeenCalledWith(12, 'batch-1', expect.any(String))
      expect(view.container.textContent).toContain('停止中')
      expect(input(view.container).value).toBe('/work/project')
      expect(projectDeviceBindingApi.save).toHaveBeenCalledTimes(1)
    } finally { view.unmount() }
  })

  it('disables writes and shows the permission error when the list request fails', async () => {
    vi.mocked(projectDeviceBindingApi.list).mockRejectedValueOnce(new Error('你不是该项目成员'))
    const view = await mountSettings()
    try {
      expect(view.container.textContent).toContain('你不是该项目成员')
      expect(input(view.container).readOnly).toBe(true)
      expect(view.container.querySelector('.binding-actions')).toBeNull()
    } finally { view.unmount() }
  })

  it('does not save an old project after a project switch during host validation', async () => {
    let finishValidation: ((value: { deviceId: string; projectDirectory: string; docsDirectory: string }) => void) | undefined
    vi.mocked(validateCodexProjectDirectory).mockImplementationOnce(() => new Promise((resolve) => { finishValidation = resolve }))
    const view = await mountSettings()
    try {
      ;(view.container.querySelector('.binding-actions .btn-primary') as HTMLButtonElement).click()
      await nextTick()
      vi.mocked(projectDeviceBindingApi.list).mockResolvedValueOnce([])
      view.props.projectId = 13
      view.props.projectName = 'Other'
      await settle()
      finishValidation?.({ deviceId: 'device-a', projectDirectory: '/real/project', docsDirectory: '/real/project/docs' })
      await settle()
      expect(projectDeviceBindingApi.save).not.toHaveBeenCalled()
      expect(view.container.textContent).toContain('Other')
      expect(input(view.container).value).toBe('')
    } finally { view.unmount() }
  })

  it('discards a download response after switching projects', async () => {
    let finishDownload: ((value: { blob: Blob; filename: string }) => void) | undefined
    vi.mocked(collaborationHostApi.installer).mockImplementationOnce(() => new Promise((resolve) => { finishDownload = resolve }))
    const objectUrl = vi.fn(() => 'blob:test')
    const OriginalURL = URL
    vi.stubGlobal('URL', Object.assign(class extends OriginalURL {}, { createObjectURL: objectUrl, revokeObjectURL: vi.fn() }))
    const view = await mountSettings()
    try {
      const button = [...view.container.querySelectorAll('button')].find((item) => item.textContent?.includes('下载宿主'))
      button?.click()
      await nextTick()
      vi.mocked(projectDeviceBindingApi.list).mockResolvedValueOnce([])
      view.props.projectId = 13
      view.props.projectName = 'Other'
      await settle()
      finishDownload?.({ blob: new Blob(['installer']), filename: 'linear-lite-host-macos-arm64-V3.dmg' })
      await settle()
      expect(objectUrl).not.toHaveBeenCalled()
      expect(view.container.textContent).toContain('Other')
      expect((view.container.querySelector('.binding-host-actions button') as HTMLButtonElement).disabled).toBe(false)
    } finally { view.unmount(); vi.unstubAllGlobals() }
  })
})
