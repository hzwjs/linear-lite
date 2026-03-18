import { describe, expect, it } from 'vitest'
import { htmlToMd, looksLikeMarkdownTaskList, mdToHtml } from './editorMarkdown'

/** 规格化： trim，连续换行压成单个换行，连续空格压成单个空格，便于往返比较 */
function normalize(s: string): string {
  return s
    .trim()
    .replace(/\n{2,}/g, '\n')
    .replace(/[ \t]+/g, ' ')
}

describe('editorMarkdown 往返', () => {
  it('识别 markdown checklist 文本', () => {
    expect(looksLikeMarkdownTaskList('- [ ] 待办')).toBe(true)
    expect(looksLikeMarkdownTaskList('- [x] 完成')).toBe(true)
    expect(looksLikeMarkdownTaskList('普通段落')).toBe(false)
  })

  it('空字符串', () => {
    const md = ''
    expect(htmlToMd(mdToHtml(md))).toBe('')
  })

  it('简单段落', () => {
    const md = 'hello world'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('## 标题', () => {
    const md = '## 标题'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('- 项（无序列表）', () => {
    const md = '- 项'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('1. 项（有序列表）', () => {
    const md = '1. 项'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('- [ ] 待办（任务项）', () => {
    const md = '- [ ] 待办'
    const html = mdToHtml(md)
    expect(html).toContain('data-type="taskList"')
    expect(html).toContain('data-type="taskItem"')
    expect(html).toContain('data-checked="false"')
    expect(normalize(htmlToMd(html))).toBe(normalize(md))
  })

  it('- [x] 已完成（任务项）', () => {
    const md = '- [x] 已完成'
    const html = mdToHtml(md)
    expect(html).toContain('data-type="taskList"')
    expect(html).toContain('data-type="taskItem"')
    expect(html).toContain('data-checked="true"')
    expect(normalize(htmlToMd(html))).toBe(normalize(md))
  })

  it('Tiptap task list HTML 会序列化为标准 GFM checklist', () => {
    const html =
      '<ul data-type="taskList"><li data-type="taskItem" data-checked="false"><label><input type="checkbox"></label><div><p>待办</p></div></li><li data-type="taskItem" data-checked="true"><label><input type="checkbox" checked></label><div><p>完成</p></div></li></ul>'
    expect(normalize(htmlToMd(html))).toBe(normalize('- [ ] 待办\n- [x] 完成'))
  })

  it('```\\ncode\\n```（代码块）', () => {
    const md = '```\ncode\n```'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('> 引用', () => {
    const md = '> 引用'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('图片 markdown', () => {
    const md = '![image](https://cdn.example.com/task-images/demo.png)'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('图片与正文混排', () => {
    const md = 'before\n\n![image](https://cdn.example.com/demo.png)\n\nafter'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('markdown 图片保持原生 img 标签渲染', () => {
    const html = mdToHtml('before\n\n![image](https://cdn.example.com/demo.png)\n\nafter')

    expect(html).toContain('<img')
    expect(html).toContain('src="https://cdn.example.com/demo.png"')
  })

  it('连续图片保留在同一个段落中，避免改变原有布局', () => {
    const html = mdToHtml(
      '![a](https://cdn.example.com/a.png)![b](https://cdn.example.com/b.png)![c](https://cdn.example.com/c.png)'
    )
    const doc = new DOMParser().parseFromString(html, 'text/html')

    expect(doc.querySelectorAll('p > img').length).toBe(3)
    expect(doc.querySelectorAll('img').length).toBe(3)
  })
})
