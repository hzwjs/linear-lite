import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCodexHostIdentity, validateCodexProjectDirectory } from './codexHost'

function response(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as Response
}

describe('codex host device and directory', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('reads identity and validates the requested directory with the same device', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ deviceId: 'device-a', hostInstanceId: 'host-a' }))
      .mockResolvedValueOnce(response({ deviceId: 'device-a', projectDirectory: '/real/project', docsDirectory: '/real/project/docs' }))
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
      .mockResolvedValueOnce(response({ deviceId: 'device-a' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(validateCodexProjectDirectory('device-b', '/work')).rejects.toThrow('设备身份不匹配')
    await expect(getCodexHostIdentity()).rejects.toThrow('本机宿主设备信息无效')
  })

  it('reports a host connection failure without substituting a browser device identifier', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')))
    await expect(getCodexHostIdentity()).rejects.toThrow('无法连接本机协作宿主')
  })
})
