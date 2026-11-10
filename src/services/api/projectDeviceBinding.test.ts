import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { projectDeviceBindingApi } from './projectDeviceBinding'

const binding = {
  id: 1, userId: 7, projectId: 12, deviceId: 'device-a', projectDirectory: '/work/project',
  revision: 3, createdAt: '2026-11-10T10:00:00', updatedAt: '2026-11-10T10:10:00',
}

describe('projectDeviceBindingApi', () => {
  afterEach(() => vi.restoreAllMocks())

  it('uses the server binding list as the only device data source', async () => {
    const request = vi.spyOn(api, 'post').mockResolvedValue({ data: { code: 200, data: { bindings: [binding] } } } as never)
    await expect(projectDeviceBindingApi.list(12)).resolves.toEqual([binding])
    expect(request).toHaveBeenCalledWith('/collaboration/bindings/list', { projectId: 12 })
  })

  it('sends the host validated directory and current device without user identity fields', async () => {
    const request = vi.spyOn(api, 'post').mockResolvedValue({ data: { code: 200, data: binding } } as never)
    await expect(projectDeviceBindingApi.save(12, 'device-a', '/work/project')).resolves.toEqual(binding)
    expect(request).toHaveBeenCalledWith('/collaboration/bindings/save', {
      projectId: 12, deviceId: 'device-a', projectDirectory: '/work/project',
    })
  })

  it('passes the server frozen-execution reason to the interface', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ isAxiosError: true, response: {
      status: 409, data: { message: '执行记录等待停止确认，项目绑定保持冻结' },
    } })
    await expect(projectDeviceBindingApi.delete(12, 'device-a'))
      .rejects.toThrow('执行记录等待停止确认，项目绑定保持冻结')
  })
})
