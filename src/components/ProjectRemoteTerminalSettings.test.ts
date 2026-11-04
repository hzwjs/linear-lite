import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectRemoteTerminalSettings from './ProjectRemoteTerminalSettings.vue'
import zhCN from '../i18n/messages/zh-CN'
import { remoteTerminalApi, type RtOverview } from '../services/api/remoteTerminal'
import { projectApi } from '../services/api/project'

vi.mock('../services/api/remoteTerminal', () => ({
  remoteTerminalApi: {
    overview: vi.fn(), createCa: vi.fn(), rotateCa: vi.fn(), retireCa: vi.fn(), createTarget: vi.fn(), updateTarget: vi.fn(),
    deleteTarget: vi.fn(), accessScript: vi.fn(), createProfile: vi.fn(), updateProfile: vi.fn(), deleteProfile: vi.fn(),
    setGrants: vi.fn(), certificates: vi.fn(), auditEvents: vi.fn(),
  },
}))
vi.mock('../services/api/project', () => ({ projectApi: { listMembers: vi.fn() } }))

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function overview(extra: Partial<RtOverview> = {}): RtOverview {
  return {
    enabled: true,
    projectIdentifier: 'ENG',
    cas: [{ id: 1, status: 'active', publicKey: 'ca', fingerprint: 'SHA256:ca', createdAt: '2026-11-03T10:00:00' }],
    targets: [{
      id: 10, targetId: 'prod-api', alias: 'API', environment: 'prod', host: '10.0.0.5', port: 22,
      hostKey: 'ssh-ed25519 AAAA', hostKeyFingerprint: 'SHA256:host', hostKeyRev: 1,
      profiles: [{ id: 20, profileId: 'deploy', loginUser: 'deploy', maxTtlSeconds: 600, permitPty: true, grantUserIds: [2] }],
    }],
    ...extra,
  }
}

async function mountSettings() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const app = createApp(ProjectRemoteTerminalSettings, { projectId: 7 })
  app.use(i18n)
  app.mount(host)
  await nextTick()
  await flush()
  return { host, unmount: () => { app.unmount(); host.remove() } }
}

describe('ProjectRemoteTerminalSettings', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(projectApi.listMembers).mockResolvedValue([
      { id: 1, username: 'owner' }, { id: 2, username: 'alice' },
    ] as never)
    vi.mocked(remoteTerminalApi.overview).mockResolvedValue(overview())
  })

  it('lists targets with their host fingerprint and the members granted each identity', async () => {
    const view = await mountSettings()
    try {
      expect(remoteTerminalApi.overview).toHaveBeenCalledWith(7)
      const target = view.host.querySelector('[data-testid="remote-terminal-target-prod-api"]')!
      expect(target.textContent).toContain('SHA256:host')
      const boxes = [...target.querySelectorAll<HTMLInputElement>('.grant input[type="checkbox"]')]
      const byName = (name: string) => boxes.find((box) => box.parentElement?.textContent?.includes(name))!
      expect(byName('alice').checked).toBe(true)
      expect(byName('owner').checked).toBe(false)
    } finally { view.unmount() }
  })

  it('saves the full grant list when a member is toggled', async () => {
    vi.mocked(remoteTerminalApi.setGrants).mockResolvedValue([2, 1])
    const view = await mountSettings()
    try {
      const owner = [...view.host.querySelectorAll<HTMLInputElement>('.grant input[type="checkbox"]')]
        .find((box) => box.parentElement?.textContent?.includes('owner'))!
      owner.checked = true
      owner.dispatchEvent(new Event('change'))
      await flush()
      expect(remoteTerminalApi.setGrants).toHaveBeenCalledWith(7, 20, [2, 1])
    } finally { view.unmount() }
  })

  it('refuses CA creation and says why when the server has no KEK', async () => {
    vi.mocked(remoteTerminalApi.overview).mockResolvedValue(overview({ enabled: false, cas: [] }))
    const view = await mountSettings()
    try {
      expect(view.host.querySelector('[data-testid="remote-terminal-disabled"]')).not.toBeNull()
      expect((view.host.querySelector('[data-testid="remote-terminal-create-ca"]') as HTMLButtonElement).disabled).toBe(true)
    } finally { view.unmount() }
  })

  it('does not rotate the CA unless the owner confirms', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const view = await mountSettings()
    try {
      ;(view.host.querySelector('[data-testid="remote-terminal-rotate-ca"]') as HTMLButtonElement).click()
      await flush()
      expect(confirm).toHaveBeenCalled()
      expect(remoteTerminalApi.rotateCa).not.toHaveBeenCalled()
    } finally { confirm.mockRestore(); view.unmount() }
  })

  it('shows the access script for a server and reports backend errors verbatim', async () => {
    vi.mocked(remoteTerminalApi.accessScript).mockResolvedValue('#!/bin/sh\necho hi\n')
    const view = await mountSettings()
    try {
      const open = [...view.host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === '接入脚本')!
      open.click()
      await flush()
      expect(view.host.querySelector('[data-testid="remote-terminal-script"] pre')!.textContent).toContain('echo hi')
    } finally { view.unmount() }

    vi.mocked(remoteTerminalApi.overview).mockRejectedValue(new Error('只有项目创建者可以执行此操作'))
    const denied = await mountSettings()
    try {
      expect(denied.host.querySelector('[role="alert"]')!.textContent).toContain('只有项目创建者可以执行此操作')
    } finally { denied.unmount() }
  })
})
