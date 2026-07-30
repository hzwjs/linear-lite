import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  parseCliArgs,
  readPrivateEnvironmentFile,
  runOutlineSync
} from './outline-sync.mjs'

const temporaryDirectories: string[] = []

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'outline-sync-test-'))
  temporaryDirectories.push(root)
  const catalogPath = path.join(root, 'catalog.json')
  const statePath = path.join(root, 'state.json')
  const outlineAuthFile = path.join(root, 'outline.env')
  const targetAuthFile = path.join(root, 'target.env')
  fs.writeFileSync(catalogPath, JSON.stringify({
    version: 1,
    sourceMode: 'outline-api',
    outlineBaseUrl: 'http://outline.example',
    projectIdentifier: 'JLNX',
    documents: [{
      outlineDocumentId: 'Root123456',
      title: '根节点',
      parentOutlineDocumentId: null,
      sortOrder: 0,
      sourceUrl: 'http://outline.example/doc/root-Root123456'
    }]
  }))
  fs.writeFileSync(outlineAuthFile, 'OUTLINE_API_TOKEN=outline-secret\n', { mode: 0o600 })
  fs.writeFileSync(targetAuthFile, 'JWT=target-secret\n', { mode: 0o600 })
  return {
    root,
    catalogPath,
    statePath,
    outlineAuthFile,
    targetAuthFile,
    runDirectory: path.join(root, 'run')
  }
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

describe('outline-sync batch runner', () => {
  it('runs one batch, verifies idempotency, and writes private artifacts', async () => {
    const sample = fixture()
    const migrate = vi.fn()
      .mockImplementationOnce(async options => {
        options.onProgress('document_created', {
          outlineDocumentId: 'Root123456',
          linearLiteDocumentId: 63
        })
        return {
        projectId: 7,
        documents: 1,
        migratedDocuments: 1,
        blockedDocuments: 0,
        createdDocuments: 1,
        updatedDocuments: 1,
        uploadedAttachments: 2
        }
      })
      .mockResolvedValueOnce({
        projectId: 7,
        documents: 1,
        migratedDocuments: 1,
        blockedDocuments: 0,
        createdDocuments: 0,
        updatedDocuments: 0,
        uploadedAttachments: 0
      })
    const createLinearLiteApi = vi.fn(() => ({ kind: 'linear-lite' }))
    const createOutlineApi = vi.fn(() => ({ kind: 'outline' }))
    const result = await runOutlineSync({
      ...sample,
      subtreeRootOutlineDocumentId: 'Root123456'
    }, {
      migrate,
      createLinearLiteApi,
      createOutlineApi,
      now: () => new Date('2026-07-30T04:00:00.000Z')
    })

    expect(result.status).toBe('completed')
    expect(migrate).toHaveBeenCalledTimes(2)
    expect(createLinearLiteApi).toHaveBeenCalledWith({
      apiBaseUrl: 'http://localhost:5173/api',
      token: 'target-secret'
    })
    expect(createOutlineApi).toHaveBeenCalledWith({
      outlineBaseUrl: 'http://outline.example',
      token: 'outline-secret'
    })
    expect(fs.existsSync(`${sample.statePath}.lock`)).toBe(false)
    for (const name of ['result.json', 'events.ndjson']) {
      const artifact = path.join(sample.runDirectory, name)
      expect(fs.existsSync(artifact)).toBe(true)
      expect(fs.statSync(artifact).mode & 0o777).toBe(0o600)
    }
    expect(fs.statSync(sample.runDirectory).mode & 0o777).toBe(0o700)
    expect(fs.readFileSync(path.join(sample.runDirectory, 'events.ndjson'), 'utf8'))
      .not.toContain('secret')
    expect(fs.readFileSync(path.join(sample.runDirectory, 'events.ndjson'), 'utf8'))
      .toContain('document_created')
  })

  it('fails the batch when the immediate rerun is not idempotent', async () => {
    const sample = fixture()
    const migrate = vi.fn()
      .mockResolvedValueOnce({
        documents: 1,
        migratedDocuments: 1,
        blockedDocuments: 0,
        createdDocuments: 1,
        updatedDocuments: 0,
        uploadedAttachments: 0
      })
      .mockResolvedValueOnce({
        documents: 1,
        migratedDocuments: 1,
        blockedDocuments: 0,
        createdDocuments: 0,
        updatedDocuments: 1,
        uploadedAttachments: 0
      })

    await expect(runOutlineSync({
      ...sample,
      subtreeRootOutlineDocumentId: 'Root123456'
    }, {
      migrate,
      createLinearLiteApi: () => ({}),
      createOutlineApi: () => ({}),
      now: () => new Date('2026-07-30T04:00:00.000Z')
    })).rejects.toThrow('幂等复跑失败')

    expect(JSON.parse(fs.readFileSync(path.join(sample.runDirectory, 'result.json'), 'utf8')))
      .toMatchObject({ status: 'failed' })
    expect(fs.existsSync(`${sample.statePath}.lock`)).toBe(false)
  })

  it('rejects authentication files readable by group or others', () => {
    const sample = fixture()
    fs.chmodSync(sample.outlineAuthFile, 0o644)
    expect(() => readPrivateEnvironmentFile(sample.outlineAuthFile, ['OUTLINE_API_TOKEN']))
      .toThrow('权限必须为 0600')
  })

  it('rejects an existing state file readable by group or others', async () => {
    const sample = fixture()
    fs.writeFileSync(sample.statePath, '{}', { mode: 0o644 })
    await expect(runOutlineSync({
      ...sample,
      subtreeRootOutlineDocumentId: 'Root123456'
    })).rejects.toThrow('state 权限必须为 0600')
  })

  it('parses only the standard run command and fixed credential paths', () => {
    expect(parseCliArgs([
      'run',
      '--catalog', '/tmp/catalog.json',
      '--subtree-root-id', 'Root123456',
      '--state', '/tmp/state.json',
      '--outline-auth-file', '/tmp/outline.env',
      '--target-auth-file', '/tmp/target.env'
    ])).toMatchObject({
      catalogPath: '/tmp/catalog.json',
      subtreeRootOutlineDocumentId: 'Root123456',
      preflightConcurrency: 3,
      maxAttachmentBytes: 50 * 1024 * 1024
    })
    expect(() => parseCliArgs(['run', '--manifest', '/tmp/legacy.json']))
      .toThrow('未知参数')
  })
})
