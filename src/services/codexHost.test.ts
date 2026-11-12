import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectCodexHost, getCodexHostIdentity, getCodexHostStatus, validateCodexProjectDirectory } from './codexHost'

function response(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as Response
}

describe('codex host device and directory', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('reads identity and validates the requested directory with the same device', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ protocolVersion: 3, deviceId: 'device-a', hostInstanceId: 'host-a' }))
      .mockResolvedValueOnce(response({ protocolVersion: 3, deviceId: 'device-a', projectDirectory: '/real/project', docsDirectory: '/real/project/docs' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getCodexHostIdentity()).resolves.toEqual({ deviceId: 'device-a', hostInstanceId: 'host-a' })
    await expect(validateCodexProjectDirectory('device-a', '/link/project')).resolves.toEqual({
      deviceId: 'device-a', projectDirectory: '/real/project', docsDirectory: '/real/project/docs',
    })
    expect(fetchMock.mock.calls[0][0]).toBe('http://127.0.0.1:9781/identity')
    expect(fetchMock.mock.calls[1][0]).toBe('http://127.0.0.1:9781/validate-directory')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ deviceId: 'device-a', projectDirectory: '/link/project' })
  })

  it('exposes host validation errors and rejects malformed identity', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ code: 'DEVICE_MISMATCH', message: '设备身份不匹配' }, false))
      .mockResolvedValueOnce(response({ protocolVersion: 3, deviceId: 'device-a' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(validateCodexProjectDirectory('device-b', '/work')).rejects.toThrow('设备身份不匹配')
    await expect(getCodexHostIdentity()).rejects.toThrow('本机宿主设备信息无效')
  })

  it('reports a host connection failure without substituting a browser device identifier', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')))
    await expect(getCodexHostIdentity()).rejects.toThrow('无法连接本机协作宿主')
  })

  it('uses only purpose-specific local requests for status and connect', async () => {
    const status = { protocolVersion: 3, deviceId: 'device-a', hostInstanceId: 'host-a', processId: 1, connection: 'AUTH_REQUIRED',
      userId: null, grantId: null, deviceName: null, lastHeartbeatAt: null }
    const fetchMock = vi.fn().mockResolvedValue(response(status))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getCodexHostStatus()).resolves.toEqual(status)
    await connectCodexHost()
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      'http://127.0.0.1:9781/status', 'http://127.0.0.1:9781/connect',
    ])
    for (const [, options] of fetchMock.mock.calls) {
      expect(options.credentials).toBe('omit')
      expect(options.headers?.Authorization).toBeUndefined()
      if (options.method === 'POST') expect(JSON.parse(options.body)).toEqual({})
    }
  })

  it('rejects incomplete status responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ connection: 'ONLINE' })))
    await expect(getCodexHostStatus()).rejects.toThrow('本机宿主状态无效')
  })

  it('asks for a host upgrade when the old loopback host has no current protocol', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({
      deviceId: 'device-a', hostInstanceId: 'host-a', connection: 'ONLINE',
    })))
    await expect(getCodexHostStatus()).rejects.toThrow('本机协作宿主协议需要升级')
    await expect(getCodexHostIdentity()).rejects.toThrow('本机协作宿主协议需要升级')
  })

  it('asks for an upgrade when the legacy loopback host has no status route', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      status: 404, ok: false, json: async () => ({ code: 'ROUTE_NOT_FOUND', message: '宿主操作不存在' }),
    }))
    await expect(getCodexHostStatus()).rejects.toThrow('本机协作宿主协议需要升级')
  })
})
