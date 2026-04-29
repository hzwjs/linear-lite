import { describe, expect, it } from 'vitest'
import { mermaidPreviewHtmlFromDescriptionBody } from './blockNoteHtml'

describe('mermaidPreviewHtmlFromDescriptionBody', () => {
  it('从 BlockNote JSON 的 mermaid 代码块生成占位 div', () => {
    const doc = [
      {
        id: '1',
        type: 'codeBlock',
        props: { language: 'mermaid' },
        content: [{ type: 'text', text: 'graph TD\nA-->B', styles: {} }],
        children: [],
      },
    ]
    const html = mermaidPreviewHtmlFromDescriptionBody(JSON.stringify(doc))
    expect(html).toContain('class="mermaid"')
    expect(html).toContain('graph TD')
    expect(html).toContain('A--&gt;B')
  })

  it('剥离代码块内误贴的围栏后再预览', () => {
    const doc = [
      {
        id: '1',
        type: 'codeBlock',
        props: { language: 'mermaid' },
        content: [{ type: 'text', text: '```mermaid\ngraph LR\na-->b\n```', styles: {} }],
        children: [],
      },
    ]
    const html = mermaidPreviewHtmlFromDescriptionBody(JSON.stringify(doc))
    expect(html).toContain('graph LR')
    expect(html).not.toContain('```mermaid')
  })
})
