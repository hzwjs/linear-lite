import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { collaborationHostApi } from './collaborationHost'

vi.mock('./index', () => ({
  api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
  unwrap: (response: { data: { data: unknown } }) => response.data.data,
  toApiError: (error: unknown) => error,
}))

const reply = (data: unknown) => ({ data: { code: 200, data } })

describe('collaboration host API', () => {
  beforeEach(() => vi.resetAllMocks())

  it('reads account devices and revokes a grant using its server identifier', async () => {
    const grant = { id: 8, deviceId: 'device-a', authorized: true, online: true }
    vi.mocked(api.get).mockResolvedValueOnce(reply([grant]))
    vi.mocked(api.delete).mockResolvedValueOnce(reply(null))
    await expect(collaborationHostApi.list()).resolves.toEqual([grant])
    await collaborationHostApi.revoke(8)
    expect(api.get).toHaveBeenCalledWith('/collaboration/hosts')
    expect(api.delete).toHaveBeenCalledWith('/collaboration/hosts/8')
  })

  it('downloads an authenticated blob', async () => {
    const blob = new Blob(['installer'])
    vi.mocked(api.get).mockResolvedValueOnce({ data: blob, headers: { 'content-disposition': 'attachment; filename="linear-lite-host-macos-arm64-V3.0.1.dmg"' } })
    expect(await collaborationHostApi.installer()).toEqual({ blob, filename: 'linear-lite-host-macos-arm64-V3.0.1.dmg' })
    expect(api.get).toHaveBeenCalledWith('/collaboration/host-installer', { responseType: 'blob' })
  })
})
