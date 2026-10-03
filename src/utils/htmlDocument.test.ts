import { describe, expect, it } from 'vitest'
import { readHtmlDocumentFile } from './htmlDocument'

function file(bytes: Uint8Array): File {
  return { name: 'source.html', size: bytes.byteLength, arrayBuffer: async () => bytes.buffer } as File
}
describe('HTML source import', () => {
  it('preserves UTF-8 BOM, newlines and source whitespace', async () => {
    const source = '\uFEFF\n<!doctype html><p>原文</p>\n'
    expect(await readHtmlDocumentFile(file(new TextEncoder().encode(source)))).toBe(source)
  })
  it('rejects malformed UTF-8 instead of replacing source bytes', async () => {
    await expect(readHtmlDocumentFile(file(new Uint8Array([0xff])))).rejects.toThrow('documents.html.invalidEncoding')
  })
})
