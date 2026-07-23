import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { projectApi } from './project'

vi.mock('./index', () => ({
  api: {
    get: vi.fn(),
    put: vi.fn()
  },
  unwrap: (res: { data: { data: unknown } }) => res.data.data
}))

describe('projectApi email settings', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset()
    vi.mocked(api.put).mockReset()
  })

  it('getEmailSettings returns scenario flags', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: { code: 200, data: [{ scenarioKey: 'daily_summary', enabled: false }] }
    } as any)

    const result = await projectApi.getEmailSettings(10)

    expect(api.get).toHaveBeenCalledWith('/projects/10/email-settings')
    expect(result).toEqual([{ scenarioKey: 'daily_summary', enabled: false }])
  })

  it('putEmailSettings posts items', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: { code: 200, data: null } } as any)

    await projectApi.putEmailSettings(10, [{ scenarioKey: 'daily_summary', enabled: true }])

    expect(api.put).toHaveBeenCalledWith('/projects/10/email-settings', {
      items: [{ scenarioKey: 'daily_summary', enabled: true }]
    })
  })
})
