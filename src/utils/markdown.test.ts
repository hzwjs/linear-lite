import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

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
})
