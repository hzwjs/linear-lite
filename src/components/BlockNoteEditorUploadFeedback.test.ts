import { describe, expect, it } from 'vitest'
import { formatUploadFeedback } from './BlockNoteEditorReact'
import blockNoteReactSource from './BlockNoteEditorReact.tsx?raw'

describe('document attachment upload feedback', () => {
  it('replaces the {name} placeholder with the file name', () => {
    expect(formatUploadFeedback('正在上传附件 {name}…', 'guide.pdf', 'fallback')).toBe(
      '正在上传附件 guide.pdf…'
    )
    expect(formatUploadFeedback('附件 {name} 上传失败', 'guide.pdf', 'fallback')).toBe(
      '附件 guide.pdf 上传失败'
    )
  })

  it('falls back to plain copy when no i18n template is configured', () => {
    expect(formatUploadFeedback(undefined, 'guide.pdf', 'Uploading guide.pdf…')).toBe(
      'Uploading guide.pdf…'
    )
  })

  it('shows an instant uploading placeholder, then flips to the link card or failure text', () => {
    // 开始反馈：占位块在 await 上传之前插入，且使用灰色文本标记系统状态。
    expect(blockNoteReactSource).toContain("text: formatUploadFeedback(fileUploadingTextResolved, file.name")
    expect(blockNoteReactSource).toContain("styles: { textColor: 'gray' }")
    // 完成反馈：原地 updateBlock 为附件链接卡片，复用现有附件卡片样式。
    expect(blockNoteReactSource).toContain(
      "content: [{ type: 'link', href: url, content: file.name }]"
    )
    // 失败反馈：占位块改写为可见错误文本，不再静默吞掉上传异常。
    expect(blockNoteReactSource).toContain("text: formatUploadFeedback(fileUploadFailedTextResolved, file.name")
    expect(blockNoteReactSource).toContain("styles: { textColor: 'red' }")
    // 用户在上传期间删掉占位块时，不向已不存在的块写回数据。
    expect(blockNoteReactSource).toContain('editorInstance.getBlock(placeholder.id)')
  })

  it('documents upload feedback text is wired from the document editor i18n keys', () => {
    expect(blockNoteReactSource).toContain('fileUploadingText?: string')
    expect(blockNoteReactSource).toContain('fileUploadFailedText?: string')
    expect(blockNoteReactSource).toContain("props.fileUploadingText ?? props['file-uploading-text']")
  })
})
