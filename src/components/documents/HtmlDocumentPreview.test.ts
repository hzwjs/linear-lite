import { createApp, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import { documentApi } from '../../services/api/documents'
import HtmlDocumentPreview from './HtmlDocumentPreview.vue'

vi.mock('../../services/api/documents', () => ({ documentApi: { renderHtml: vi.fn() } }))
describe('HTML sandbox preview', () => {
  it('grants scripts without same-origin, popups or top navigation', async () => {
    vi.mocked(documentApi.renderHtml).mockResolvedValue('<html><body><script>window.x=1</script></body></html>')
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(HtmlDocumentPreview, { projectId: 3, content: '<p>HTML</p>', title: '文档' }).use(i18n)
    app.mount(host); await Promise.resolve(); await nextTick()
    const frame = host.querySelector('iframe')!
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts')
    expect(frame.getAttribute('referrerpolicy')).toBe('no-referrer')
    expect(frame.title).toBe('文档')
    expect(documentApi.renderHtml).toHaveBeenCalledWith(3, '<p>HTML</p>')
    app.unmount(); host.remove()
  })
})
