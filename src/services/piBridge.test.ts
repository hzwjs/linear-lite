import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectPiExecution, detectPiBridgePlatform, downloadPiBridgeInstaller } from './piBridge'

afterEach(() => vi.unstubAllGlobals())

describe('Pi Bridge browser adapter', () => {
  it('sends one execution credential to the local Bridge', async () => {
    vi.stubGlobal('window', {
      location: { href: 'https://linear.example.com/projects/7/tasks/LL-1' },
      setTimeout,
      clearTimeout,
    })
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ executionId: 'exec-1' }) })
    vi.stubGlobal('fetch', fetchMock)

    await connectPiExecution('exec-1', 'credential-1')

    expect(fetchMock.mock.calls[0][0]).toBe('http://127.0.0.1:9780/api/executions')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      apiBaseUrl: 'https://linear.example.com',
      executionId: 'exec-1',
      executionCredential: 'credential-1',
    })
  })

  it('detects macOS and Windows installers from the browser platform', () => {
    expect(detectPiBridgePlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)')).toBe('darwin')
    expect(detectPiBridgePlatform('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('win32')
  })

  it('downloads the supported platform installer from the Pi entry', () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.getAttribute('href')).toBe('http://localhost:3000/api/bridge/installers/darwin')
      expect(this.hasAttribute('download')).toBe(true)
    })

    expect(downloadPiBridgeInstaller('darwin')).toBe(true)

    expect(click).toHaveBeenCalledOnce()
    expect(document.querySelector('a[download]')).toBeNull()
  })

  it('does not attempt an installer download on an unsupported platform', () => {
    expect(downloadPiBridgeInstaller('linux')).toBe(false)
  })

  it('returns a project-settings instruction when Bridge is absent', async () => {
    vi.stubGlobal('window', {
      location: { href: 'https://linear.example.com/projects/7/tasks/LL-1' },
      setTimeout,
      clearTimeout,
    })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(connectPiExecution('exec-1', 'credential-1')).rejects.toThrow('项目设置的“本地执行”')
  })
})
