import { describe, it, expect, beforeEach, vi } from 'vitest'
import { uploadApi } from '../src/services/api/upload'

const mockApiResponse = <T>(data: T) => ({
  data: { code: 200, data }
})

vi.mock('../src/services/api/index', () => ({
  api: {
    post: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api, unwrap } = await import('../src/services/api/index')

describe('uploadApi', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset()
    vi.mocked(unwrap).mockClear()
  })

  it('uploads multipart image and returns url/key', async () => {
    const file = new File(['demo'], 'demo.png', { type: 'image/png' })
    vi.mocked(api.post).mockResolvedValue(
      mockApiResponse({
        url: 'https://cdn.example.com/task-images/2026/03/demo.png',
        key: 'task-images/2026/03/demo.png'
      })
    )

    const result = await uploadApi.uploadImage(file)

    expect(api.post).toHaveBeenCalledTimes(1)
    const [path, body, config] = vi.mocked(api.post).mock.calls[0]
    expect(path).toBe('/uploads/images')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)
    expect(config).toEqual({
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    expect(result).toEqual({
      url: 'https://cdn.example.com/task-images/2026/03/demo.png',
      key: 'task-images/2026/03/demo.png'
    })
  })

  it('surfaces backend failure messages', async () => {
    const file = new File(['demo'], 'demo.png', { type: 'image/png' })
    vi.mocked(api.post).mockRejectedValue(new Error('upload failed'))

    await expect(uploadApi.uploadImage(file)).rejects.toThrow('upload failed')
  })
})
