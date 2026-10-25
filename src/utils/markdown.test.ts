import { describe, expect, it } from 'vitest'
import { renderMarkdown, renderTaskCommentMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('保留 Markdown 图片语法渲染出的 img（评论等与编辑器 Turndown 输出一致）', () => {
    const html = renderMarkdown('![](https://cdn.example.test/pic.png)')
    expect(html).toContain('<img')
    expect(html).toContain('https://cdn.example.test/pic.png')
  })

  it('剔除危险协议的图片地址', () => {
    const html = renderMarkdown('![](javascript:alert(1))')
    expect(html).not.toContain('javascript:')
  })

  it('将 mermaid 代码块输出为 div.mermaid 占位', () => {
    const html = renderMarkdown('```mermaid\ngraph TD\n  A-->B\n```')
    expect(html).toContain('class="mermaid"')
    expect(html).toContain('graph TD')
    expect(html).toContain('A--&gt;B')
  })

  it('只把已绑定的评论图片 ref 映射到带 cookie 权限的图片路由', () => {
    const html = renderTaskCommentMarkdown('![券列表](mcp-image:coupon)', 19, [{
      id: 31, ref: 'coupon', fileName: 'coupon.png', contentType: 'image/png', fileSize: 64, sourceAttachmentId: null
    }])
    expect(html).toContain('/api/document-assets/task-comments/19/31')
    expect(html).toContain('alt="券列表"')
  })

  it('不渲染未绑定 ref 或远程评论图片', () => {
    expect(renderTaskCommentMarkdown('![bad](mcp-image:missing) ![remote](https://example.test/a.png)', 19, []))
      .not.toContain('<img')
  })
})
