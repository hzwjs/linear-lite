import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { createDocumentCodec, encodeDocument, materializeAndInspect, materializeDocument } from './document-codec.js'
import { extractImageAssetIds } from '../../collaboration-schema.js'

const blocks = [
  {
    id: 'heading-1',
    type: 'heading',
    props: { level: 2, textAlignment: 'left', textColor: 'default', backgroundColor: 'default' },
    content: [{ type: 'text', text: '设计说明', styles: { bold: true } }],
    children: [],
  },
  {
    id: 'image-1',
    type: 'documentImage',
    props: { imageAssetId: 731, caption: '部署拓扑' },
    children: [],
  },
  {
    id: 'paragraph-1',
    type: 'paragraph',
    props: { textAlignment: 'left', textColor: 'default', backgroundColor: 'default' },
    content: [
      { type: 'mention', props: { userId: '42', label: '林' } },
      { type: 'text', text: ' 请审阅', styles: { italic: true } },
    ],
    children: [],
  },
  {
    id: 'table-1',
    type: 'table',
    props: { textColor: 'default', backgroundColor: 'default' },
    content: {
      type: 'tableContent',
      columnWidths: [120, 180],
      headerRows: 1,
      rows: [
        { cells: [[{ type: 'text', text: '模块', styles: {} }], [{ type: 'text', text: '状态', styles: {} }]] },
        { cells: [[{ type: 'text', text: '协作', styles: {} }], [{ type: 'text', text: '完成', styles: {} }]] },
      ],
    },
    children: [],
  },
  {
    id: 'code-1',
    type: 'codeBlock',
    props: { language: 'mermaid' },
    content: 'graph TD\nA-->B',
    children: [],
  },
  {
    id: 'list-1',
    type: 'bulletListItem',
    props: { textAlignment: 'left', textColor: 'default', backgroundColor: 'default' },
    content: '父级事项',
    children: [{
      id: 'list-2',
      type: 'bulletListItem',
      props: { textAlignment: 'left', textColor: 'default', backgroundColor: 'default' },
      content: '子级事项',
      children: [],
    }],
  },
]

describe('BlockNote Yjs codec', () => {
  it('round-trips custom blocks, mentions, nested blocks, tables, code, props, and stable block ids', () => {
    const codec = createDocumentCodec()
    const state = codec.encode(JSON.stringify(blocks))
    const decoded = JSON.parse(codec.decode(state)) as Array<Record<string, any>>

    assert.deepEqual(decoded.map((block) => block.id), blocks.map((block) => block.id))
    assert.equal(decoded[1]?.type, 'documentImage')
    assert.equal(decoded[1]?.props?.imageAssetId, 731)
    assert.equal(decoded[1]?.props?.caption, '部署拓扑')
    assert.equal(decoded[2]?.content?.[0]?.type, 'mention')
    assert.equal(decoded[2]?.content?.[0]?.props?.userId, '42')
    assert.equal(decoded[2]?.content?.[0]?.props?.label, '林')
    assert.deepEqual(decoded[3]?.content?.columnWidths, [120, 180])
    assert.equal(decoded[3]?.content?.headerRows, 1)
    assert.equal(decoded[4]?.type, 'codeBlock')
    assert.equal(decoded[4]?.props?.language, 'mermaid')
    assert.equal(decoded[4]?.content?.[0]?.text, 'graph TD\nA-->B')
    assert.equal(decoded[5]?.children?.[0]?.id, 'list-2')
    assert.equal(decoded[5]?.children?.[0]?.content?.[0]?.text, '子级事项')
  })

  it('keeps HTML as exact Y.Text content and reports UTF-8 bytes', () => {
    const html = '<article>协作内容</article>'
    const state = encodeDocument('HTML', html)
    assert.equal(materializeDocument('HTML', state), html)
    assert.equal(materializeAndInspect('HTML', state).contentBytes, Buffer.byteLength(html, 'utf8'))
  })

  it('extracts unique custom image asset ids recursively', () => {
    assert.deepEqual(extractImageAssetIds([
      { type: 'documentImage', props: { imageAssetId: 12 }, children: [{ type: 'documentImage', props: { imageAssetId: '12' } }] },
      { type: 'documentImage', props: { imageAssetId: 8 } },
    ]), [8, 12])
  })
})
