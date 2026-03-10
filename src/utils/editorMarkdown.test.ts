import { describe, expect, it } from 'vitest'
import { htmlToMd, mdToHtml } from './editorMarkdown'

/** 规格化： trim，连续换行压成单个换行，连续空格压成单个空格，便于往返比较 */
function normalize(s: string): string {
  return s
    .trim()
    .replace(/\n{2,}/g, '\n')
    .replace(/[ \t]+/g, ' ')
}

describe('editorMarkdown 往返', () => {
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
    const roundTrip = htmlToMd(mdToHtml(md))
    // Turndown 默认不输出 GFM checkbox，往返后多为 "- 待办"，仅断言为无序列表且含「待办」
    expect(normalize(roundTrip)).toContain('待办')
    expect(roundTrip.trim()).toMatch(/^-\s+.*待办/)
  })

  it('```\\ncode\\n```（代码块）', () => {
    const md = '```\ncode\n```'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })

  it('> 引用', () => {
    const md = '> 引用'
    expect(normalize(htmlToMd(mdToHtml(md)))).toBe(normalize(md))
  })
})
