import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  compileOutlineSyncCatalog,
  runCatalogCompiler
} from './compile-outline-sync-catalog.mjs'

const temporaryDirectories: string[] = []

function source(outlineDocumentId: string, title: string) {
  return {
    outlineDocumentId,
    title,
    markdown: `# ${title}`,
    sourceUrl: `http://outline.example/doc/${title}-${outlineDocumentId}`
  }
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

describe('Outline sync catalog compiler', () => {
  it('preserves mapped target layout and appends new snapshot children', () => {
    const snapshot = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [
        { outlineDocumentId: 'Input00001', parentOutlineDocumentId: 'Manage0001', sortOrder: 0 },
        { outlineDocumentId: 'NewChild01', parentOutlineDocumentId: 'Input00001', sortOrder: 0 }
      ]
    }
    const state = {
      version: 1,
      projectIdentifier: 'JLNX',
      documents: {
        Manage0001: { linearLiteDocumentId: 9 },
        Input00001: { linearLiteDocumentId: 34 },
        OldChild01: { linearLiteDocumentId: 63 }
      }
    }
    const targetTree = [
      { id: 9, projectId: 7, parentDocumentId: null, sortOrder: 2 },
      // 输入已经由用户确认调整到根级，编译器不能按 Outline 快照移回管理下。
      { id: 34, projectId: 7, parentDocumentId: null, sortOrder: 4 },
      { id: 63, projectId: 7, parentDocumentId: 34, sortOrder: 0 }
    ]
    const sourceDocuments = new Map([
      ['Manage0001', source('Manage0001', '管理')],
      ['Input00001', source('Input00001', '输入')],
      ['OldChild01', source('OldChild01', '已有子节点')],
      ['NewChild01', source('NewChild01', '新增子节点')]
    ])

    const catalog = compileOutlineSyncCatalog({ snapshot, state, targetTree, sourceDocuments })
    const input = catalog.documents.find(document => document.outlineDocumentId === 'Input00001')!
    const oldChild = catalog.documents.find(document => document.outlineDocumentId === 'OldChild01')!
    const newChild = catalog.documents.find(document => document.outlineDocumentId === 'NewChild01')!

    expect(input.parentOutlineDocumentId).toBeNull()
    expect(oldChild).toMatchObject({ parentOutlineDocumentId: 'Input00001', sortOrder: 0 })
    expect(newChild).toMatchObject({ parentOutlineDocumentId: 'Input00001', sortOrder: 1 })
  })

  it('queries every mapped and new Outline ID and writes a private complete catalog', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'outline-catalog-test-'))
    temporaryDirectories.push(root)
    const snapshotPath = path.join(root, 'snapshot.json')
    const statePath = path.join(root, 'state.json')
    const outlineAuthFile = path.join(root, 'outline.env')
    const targetAuthFile = path.join(root, 'target.env')
    const outputPath = path.join(root, 'catalog.json')
    fs.writeFileSync(snapshotPath, JSON.stringify({
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [{ outlineDocumentId: 'NewChild01', parentOutlineDocumentId: 'Input00001', sortOrder: 0 }]
    }), { mode: 0o600 })
    fs.writeFileSync(statePath, JSON.stringify({
      version: 1,
      projectIdentifier: 'JLNX',
      documents: { Input00001: { linearLiteDocumentId: 34 } }
    }), { mode: 0o600 })
    fs.writeFileSync(outlineAuthFile, 'OUTLINE_API_TOKEN=outline-secret\n', { mode: 0o600 })
    fs.writeFileSync(targetAuthFile, 'JWT=target-secret\n', { mode: 0o600 })
    const getDocument = vi.fn(async outlineDocumentId => source(
      outlineDocumentId,
      outlineDocumentId === 'Input00001' ? '输入' : '新增子节点'))

    const result = await runCatalogCompiler({
      snapshotPath,
      statePath,
      outlineAuthFile,
      targetAuthFile,
      outputPath
    }, {
      createOutlineApi: () => ({ getDocument }),
      createTargetApi: () => ({
        async listProjects() { return [{ id: 7, identifier: 'JLNX' }] },
        async listDocumentTree() {
          return [{ id: 34, projectId: 7, parentDocumentId: null, sortOrder: 4 }]
        }
      })
    })

    expect(result.documents).toBe(2)
    expect(getDocument.mock.calls.map(([id]) => id).sort()).toEqual(['Input00001', 'NewChild01'])
    expect(fs.statSync(outputPath).mode & 0o777).toBe(0o600)
    expect(JSON.parse(fs.readFileSync(outputPath, 'utf8')).documents).toHaveLength(2)
  })
})
