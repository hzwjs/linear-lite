import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { TaskImage } from '../extensions/TaskImage'
import {
  FAILED_IMAGE_STATE,
  MAX_EDITOR_IMAGE_SIZE_BYTES,
  SUPPORTED_EDITOR_IMAGE_TYPES,
  UPLOADING_IMAGE_STATE,
  buildImageMarkdown,
  getEditorUploadStateSummaryFromHtml,
  serializeEditorHtmlForSave,
  validateEditorImageFile
} from './editorImageUpload'
import { htmlToMd } from './editorMarkdown'

describe('editorImageUpload', () => {
  it('accepts supported image types under 10MB', () => {
    const file = new File(['demo'], 'demo.png', { type: 'image/png' })

    expect(validateEditorImageFile(file)).toEqual({ ok: true })
  })

  it('rejects unsupported image types', () => {
    const file = new File(['demo'], 'demo.svg', { type: 'image/svg+xml' })

    expect(validateEditorImageFile(file)).toEqual({
      ok: false,
      message: `仅支持 ${SUPPORTED_EDITOR_IMAGE_TYPES.join(' / ')} 图片`
    })
  })

  it('rejects files over size limit', () => {
    const file = new File(['demo'], 'huge.png', { type: 'image/png' })
    Object.defineProperty(file, 'size', { value: MAX_EDITOR_IMAGE_SIZE_BYTES + 1 })

    expect(validateEditorImageFile(file)).toEqual({
      ok: false,
      message: '图片大小不能超过 10MB'
    })
  })

  it('builds markdown image syntax', () => {
    expect(buildImageMarkdown('https://cdn.example.com/demo.png')).toBe(
      '![image](https://cdn.example.com/demo.png)'
    )
  })

  it('preserves inserted image nodes when serializing editor html back to markdown', () => {
    const editor = new Editor({
      element: document.createElement('div'),
      extensions: [
        StarterKit.configure({
          codeBlock: false,
          heading: false,
          blockquote: false,
          bulletList: false,
          listItem: false,
          orderedList: false
        }),
        TaskImage
      ],
      content: '<p></p>'
    })

    editor
      .chain()
      .focus()
      .insertContent({
        type: 'taskImage',
        attrs: {
          src: 'https://cdn.example.com/demo.png',
          alt: 'image',
          uploadState: 'uploaded'
        }
      })
      .insertContent(' ')
      .run()

    expect(htmlToMd(editor.getHTML())).toBe('![image](https://cdn.example.com/demo.png)')
  })

  it('removes unresolved images from serialized save html', () => {
    const html =
      '<p>a<img src="blob:one" data-upload-state="uploading" data-local-id="1">' +
      '<img src="blob:two" data-upload-state="failed" data-local-id="2">' +
      '<img src="https://cdn.example.com/final.png" data-upload-state="uploaded" data-local-id="3"></p>'

    expect(serializeEditorHtmlForSave(html)).toContain('https://cdn.example.com/final.png')
    expect(serializeEditorHtmlForSave(html)).not.toContain('blob:one')
    expect(serializeEditorHtmlForSave(html)).not.toContain('blob:two')
  })

  it('summarizes pending and failed upload states from editor html', () => {
    const html =
      `<p><img src="blob:one" data-upload-state="${UPLOADING_IMAGE_STATE}">` +
      `<img src="blob:two" data-upload-state="${FAILED_IMAGE_STATE}"></p>`

    expect(getEditorUploadStateSummaryFromHtml(html)).toEqual({
      hasPending: true,
      hasFailed: true
    })
  })

  it('preserves remote image placeholder wrapper before load', () => {
    const editor = new Editor({
      element: document.createElement('div'),
      extensions: [
        StarterKit.configure({
          codeBlock: false,
          heading: false,
          blockquote: false,
          bulletList: false,
          listItem: false,
          orderedList: false
        }),
        TaskImage
      ],
      content:
        '<p>before</p><span class="task-image-node task-image-node--loading" data-upload-state="loading-remote" data-src="https://cdn.example.com/demo.png"><span class="task-image-node__frame"><img src="https://cdn.example.com/demo.png" alt="image" class="task-image-node__image"></span></span><p>after</p>'
    })

    const html = editor.getHTML()

    expect(html).toContain('data-upload-state="loading-remote"')
    expect(htmlToMd(html)).toContain('![image](https://cdn.example.com/demo.png)')
  })
})
