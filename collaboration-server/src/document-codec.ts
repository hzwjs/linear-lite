import { ServerBlockNoteEditor } from '@blocknote/server-util'
import * as Y from 'yjs'
import {
  BLOCKNOTE_FRAGMENT,
  extractImageAssetIds,
  HTML_TEXT_FIELD,
  createDocumentSchema,
} from '../../collaboration-schema.js'
import type { ContentFormat } from './types.js'

const editor = ServerBlockNoteEditor.create({ schema: createDocumentSchema() as never })

function parseBlocks(content: string) {
  const parsed: unknown = JSON.parse(content)
  if (!Array.isArray(parsed)) throw new TypeError('BLOCKNOTE content must be a JSON block array')
  return parsed as Parameters<typeof editor.blocksToYDoc>[0]
}

function blocksFromState(state: Uint8Array) {
  const doc = new Y.Doc()
  try {
    Y.applyUpdate(doc, state)
    return editor.yDocToBlocks(doc, BLOCKNOTE_FRAGMENT)
  } finally {
    doc.destroy()
  }
}

export function createDocumentCodec() {
  return {
    encode(content: string): Uint8Array {
      const doc = editor.blocksToYDoc(parseBlocks(content), BLOCKNOTE_FRAGMENT)
      try {
        return Y.encodeStateAsUpdate(doc)
      } finally {
        doc.destroy()
      }
    },
    decode(state: Uint8Array): string {
      return JSON.stringify(blocksFromState(state))
    },
    inspect(state: Uint8Array) {
      return blocksFromState(state)
    },
  }
}

const blockNoteCodec = createDocumentCodec()

export function materializeDocument(contentFormat: ContentFormat, state: Uint8Array): string {
  if (contentFormat === 'HTML') {
    const doc = new Y.Doc()
    try {
      Y.applyUpdate(doc, state)
      return doc.getText(HTML_TEXT_FIELD).toString()
    } finally {
      doc.destroy()
    }
  }
  return blockNoteCodec.decode(state)
}

export function encodeDocument(contentFormat: ContentFormat, content: string): Uint8Array {
  if (contentFormat === 'HTML') {
    const doc = new Y.Doc()
    try {
      doc.getText(HTML_TEXT_FIELD).insert(0, content)
      return Y.encodeStateAsUpdate(doc)
    } finally {
      doc.destroy()
    }
  }
  return blockNoteCodec.encode(content)
}

export function materializeAndInspect(contentFormat: ContentFormat, state: Uint8Array) {
  const content = materializeDocument(contentFormat, state)
  const imageAssetIds = contentFormat === 'BLOCKNOTE'
    ? extractImageAssetIds(JSON.parse(content))
    : []
  return { content, contentBytes: Buffer.byteLength(content, 'utf8'), imageAssetIds }
}
