import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectLocalExecutionSettings from './ProjectLocalExecutionSettings.vue'
import {
  configurePiBridgeInstance,
  detectPiBridgePlatform,
  getPiBridgeHealth,
  listPiBridgeProjects,
  savePiBridgeProject,
} from '../services/piBridge'

vi.mock('../services/piBridge', () => ({
  configurePiBridgeInstance: vi.fn(),
  detectPiBridgePlatform: vi.fn(),
  getPiBridgeHealth: vi.fn(),
  listPiBridgeProjects: vi.fn(),
  piBridgeInstallerUrl: vi.fn((platform: string) => `/api/bridge/installers/${platform}`),
  savePiBridgeProject: vi.fn(),
}))

function flushPromises() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountSettings() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(ProjectLocalExecutionSettings, { projectId: 12, projectName: 'Linear-Lite' })
  app.mount(host)
  await nextTick()
  await flushPromises()
  return { host, unmount: () => { app.unmount(); host.remove() } }
}

describe('ProjectLocalExecutionSettings', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(detectPiBridgePlatform).mockReturnValue('darwin')
    vi.mocked(configurePiBridgeInstance).mockResolvedValue()
    vi.mocked(listPiBridgeProjects).mockResolvedValue([])
  })

  it('shows the platform installer when Bridge is absent', async () => {
    vi.mocked(getPiBridgeHealth).mockResolvedValue(null)
    const view = await mountSettings()
    try {
      expect(view.host.textContent).toContain('未检测到 Bridge')
      const installer = view.host.querySelector('.local-execution-install') as HTMLAnchorElement
      expect(installer.textContent).toContain('安装 Bridge')
      expect(installer.getAttribute('href')).toBe('/api/bridge/installers/darwin')
    } finally {
      view.unmount()
    }
  })

  it('loads and saves the current project directory through Bridge', async () => {
    vi.mocked(getPiBridgeHealth).mockResolvedValue({ status: 'idle', configured: true })
    vi.mocked(listPiBridgeProjects).mockResolvedValue([{
      projectId: 12,
      projectName: 'Linear-Lite',
      directoryPath: '/Users/me/linear-lite',
      valid: true,
    }])
    vi.mocked(savePiBridgeProject).mockResolvedValue({
      projectId: 12,
      projectName: 'Linear-Lite',
      directoryPath: '/Users/me/linear-lite-next',
      valid: true,
    })
    const view = await mountSettings()
    try {
      const input = view.host.querySelector('#project-local-directory') as HTMLInputElement
      expect(input.value).toBe('/Users/me/linear-lite')
      input.value = '/Users/me/linear-lite-next'
      input.dispatchEvent(new Event('input'))
      await nextTick()
      ;(view.host.querySelector('.local-execution-controls button') as HTMLButtonElement).click()
      await flushPromises()

      expect(savePiBridgeProject).toHaveBeenCalledWith(12, 'Linear-Lite', '/Users/me/linear-lite-next')
      expect(view.host.textContent).toContain('本地执行配置已保存')
    } finally {
      view.unmount()
    }
  })
})
