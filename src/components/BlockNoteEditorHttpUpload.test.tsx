/** @jsxImportSource react */
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { it, expect, vi } from 'vitest'
import BlockNoteEditorReact from './BlockNoteEditorReact'

it('pastes a document attachment when HTTP provides getRandomValues without randomUUID', async () => {
  vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) })
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }))
  const host = document.createElement('div')
  document.body.appendChild(host)
  const root = createRoot(host)
  const file = new File(['HTTP paste'], 'http.txt', { type: 'text/plain' })
  let finishUpload!: (url: string) => void
  const upload = vi.fn().mockImplementation(() => new Promise<string>(resolve => { finishUpload = resolve }))
  try {
    await act(async () => {
      root.render(createElement(BlockNoteEditorReact, {
        documentId: 11, pasteFileAsLink: true, uploadFile: upload,
        initialContent: JSON.stringify([{ id: 'start', type: 'paragraph', content: [], children: [] }]),
      }))
    })
    const target = host.querySelector<HTMLElement>('.bn-editor[contenteditable]')!
    const beforePaste = target.innerHTML
    await act(async () => {
      target.focus()
      const event = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'clipboardData', { value: {
        types: ['Files'], items: [{ getAsFile: () => file }], getData: () => '',
      } })
      target.dispatchEvent(event)
    })
    expect(upload).toHaveBeenCalledWith(file)
    // 传输期间正文不插入状态块，附件完成后保留粘贴位置。
    expect(target.innerHTML).toBe(beforePaste)
    await act(async () => { finishUpload('/api/project-documents/11/attachments/31/download') })
    expect(host.querySelector('a[href="/api/project-documents/11/attachments/31/download"]')).not.toBeNull()
  } finally {
    await act(async () => { root.unmount() })
    host.remove()
    vi.unstubAllGlobals()
  }
})
