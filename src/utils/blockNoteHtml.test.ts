import { describe, expect, it } from 'vitest'
import { mermaidPreviewHtmlFromDescriptionBody, renderBody } from './blockNoteHtml'
import { renderMarkdown } from './markdown'

function imageCommentBody(url = 'https://cdn.example.test/reply.png'): string {
  return JSON.stringify([
    {
      id: 'image-1',
      type: 'image',
      props: { url, caption: 'Pasted image', previewWidth: 320 },
      content: [],
      children: [],
    },
  ])
}

describe('renderBody', () => {
  it('renders images stored in BlockNote comment bodies after submission', () => {
    const html = renderBody(imageCommentBody(), renderMarkdown)

    expect(html).toContain('<img')
    expect(html).toContain('src="https://cdn.example.test/reply.png"')
    expect(html).toContain('alt="Pasted image"')
  })

  it('does not render unsafe image URLs from comment bodies', () => {
    const html = renderBody(imageCommentBody('javascript:alert(1)'), renderMarkdown)

    expect(html).not.toContain('src=')
  })
})

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
