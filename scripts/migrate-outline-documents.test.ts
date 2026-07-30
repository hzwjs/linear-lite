// @vitest-environment node
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  convertMarkdownToBlockNote,
  createHttpApi,
  createOutlineApiClient,
  downloadOutlineAttachmentToFile,
  migrateOutlineApiDocuments,
  migrateOutlineDocuments
} from './migrate-outline-documents.mjs'
import { parseBlockNoteStoredBlocks } from '../src/utils/blockNoteDescription'

const temporaryDirectories: string[] = []

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'outline-migration-'))
  temporaryDirectories.push(root)
  const exportDir = path.join(root, 'export')
  fs.mkdirSync(path.join(exportDir, '吉林农信', '安全扫描'), { recursive: true })
  fs.writeFileSync(
    path.join(exportDir, '吉林农信', '项目开发概述.md'),
    '# 项目开发概述\n\n[配置文件](./settings.xml)\n')
  fs.writeFileSync(path.join(exportDir, '吉林农信', 'settings.xml'), '<settings />')
  fs.writeFileSync(
    path.join(exportDir, '吉林农信', '安全扫描.md'),
    '# 安全扫描\n')
  fs.writeFileSync(
    path.join(exportDir, '吉林农信', '安全扫描', '20260122结果.md'),
    [
      '# 20260122结果',
      '',
      '[扫描报告](./report.pdf)',
      '',
      '[项目开发概述](../项目开发概述.md)',
      '',
      '[范围外文档](http://124.223.84.101:8888/doc/dev-rule-Outside01)'
    ].join('\n'))
  fs.writeFileSync(path.join(exportDir, '吉林农信', '安全扫描', 'report.pdf'), 'pdf')
  const manifest = {
    version: 1,
    projectIdentifier: 'JLNX',
    documents: [
      {
        outlineDocumentId: 'jIbDVtIQLv',
        title: '安全扫描',
        markdownPath: '吉林农信/安全扫描.md',
        parentOutlineDocumentId: null,
        sortOrder: 0,
        sourceUrl: 'http://124.223.84.101:8888/doc/5a6j5ywo5omr5op-jIbDVtIQLv'
      },
      {
        outlineDocumentId: 'EUEFsRqmJ4',
        title: '项目开发概述',
        markdownPath: '吉林农信/项目开发概述.md',
        parentOutlineDocumentId: null,
        sortOrder: 1,
        sourceUrl: 'http://124.223.84.101:8888/doc/6ag555uu5bya5yr5qac6lw-EUEFsRqmJ4'
      },
      {
        outlineDocumentId: 'RZAdPKfrmZ',
        title: '20260122结果',
        markdownPath: '吉林农信/安全扫描/20260122结果.md',
        parentOutlineDocumentId: 'jIbDVtIQLv',
        sortOrder: 0,
        sourceUrl: 'http://124.223.84.101:8888/doc/20260122-RZAdPKfrmZ'
      }
    ]
  }
  return {
    root,
    exportDir,
    manifest,
    statePath: path.join(root, 'state.json'),
    blockedReportPath: path.join(root, 'blocked.md')
  }
}

class FakeApi {
  projects = [{ id: 7, identifier: 'JLNX' }]
  documents = new Map<number, Record<string, any>>()
  documentsByExternalId = new Map<string, Record<string, any>>()
  attachmentsBySourceId = new Map<string, Record<string, any>>()
  actualCreatedDocuments = 0
  actualUploadedAttachments = 0
  nextDocumentId = 100
  nextAttachmentId = 500

  async listProjects() {
    return this.projects
  }

  async listDocumentTree(projectId: number) {
    return [...this.documents.values()]
      .filter(document => document.projectId === projectId)
      .sort((left, right) => {
        const leftParent = left.parentDocumentId ?? -1
        const rightParent = right.parentDocumentId ?? -1
        return leftParent - rightParent || left.sortOrder - right.sortOrder
      })
  }

  async getDocument(documentId: number) {
    const document = this.documents.get(documentId)
    if (!document) throw new Error(`missing document ${documentId}`)
    return document
  }

  async createDocument(projectId: number, body: Record<string, any>) {
    const existing = this.documentsByExternalId.get(body.externalSourceId)
    if (existing) return existing
    const sortOrder = [...this.documents.values()].filter(
      item => item.projectId === projectId && item.parentDocumentId === body.parentDocumentId).length
    const document = {
      id: this.nextDocumentId++,
      projectId,
      parentDocumentId: body.parentDocumentId,
      externalSource: body.externalSource,
      externalSourceId: body.externalSourceId,
      title: body.title,
      content: body.content,
      sortOrder,
      version: 1
    }
    this.documents.set(document.id, document)
    this.documentsByExternalId.set(document.externalSourceId, document)
    this.actualCreatedDocuments += 1
    return document
  }

  async updateDocument(documentId: number, body: Record<string, any>) {
    const document = await this.getDocument(documentId)
    if (document.version !== body.expectedVersion) throw new Error('version conflict')
    document.title = body.title
    document.content = body.content
    document.version += 1
    return document
  }

  async uploadAttachment(
    documentId: number,
    sourceId: string,
    absolutePath: string,
    contentType: string,
    fileName = path.basename(absolutePath)
  ) {
    const existing = this.attachmentsBySourceId.get(sourceId)
    if (existing) return existing
    const bytes = fs.readFileSync(absolutePath)
    const attachment = {
      id: this.nextAttachmentId++,
      documentId,
      sourceId,
      fileName,
      fileSize: bytes.length,
      contentType,
      sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      url: `/api/project-documents/${documentId}/attachments/${this.nextAttachmentId - 1}/download`
    }
    this.attachmentsBySourceId.set(sourceId, attachment)
    this.actualUploadedAttachments += 1
    return attachment
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  for (const directory of temporaryDirectories.splice(0)) {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})

describe('Outline document migration', () => {
  it('produces deterministic full BlockNote JSON accepted by the frontend parser', () => {
    const markdown = '# 标题\n\n- 一级\n  - 二级'
    const first = convertMarkdownToBlockNote(markdown, 'EUEFsRqmJ4')
    const second = convertMarkdownToBlockNote(markdown, 'EUEFsRqmJ4')
    const otherDocument = convertMarkdownToBlockNote(markdown, 'jIbDVtIQLv')
    const blocks = JSON.parse(first) as Array<Record<string, any>>
    const ids: string[] = []
    const collectIds = (items: Array<Record<string, any>>) => {
      for (const block of items) {
        ids.push(block.id)
        if (Array.isArray(block.children)) collectIds(block.children)
      }
    }
    collectIds(blocks)

    expect(first).toBe(second)
    expect(first).not.toBe(otherDocument)
    expect(ids.length).toBeGreaterThan(0)
    expect(ids.every(id => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id))).toBe(true)
    expect(new Set(ids).size).toBe(ids.length)
    expect(parseBlockNoteStoredBlocks(first)).toBeDefined()
  })

  it('rejects Markdown conversion without the fixed Outline namespace', () => {
    expect(() => convertMarkdownToBlockNote('# 标题', '')).toThrow(
      'Markdown 转换必须提供 Outline 文档 ID')
  })

  it('normalizes single literal newline escapes only in ordinary Markdown text', () => {
    const markdown = [
      '目录：\\n├── src\\n└── test；双反斜杠：\\\\n保留；`inline\\ncode`',
      '',
      '```text',
      'fenced\\ncode',
      '```'
    ].join('\n')

    const blocks = JSON.parse(convertMarkdownToBlockNote(markdown, 'EUEFsRqmJ4')) as Array<Record<string, any>>

    expect(blocks[0]?.content).toEqual([
      { type: 'text', text: '目录： ├── src └── test；双反斜杠：\\n保留；', styles: {} },
      { type: 'text', text: 'inline\\ncode', styles: { code: true } }
    ])
    expect(blocks[1]).toMatchObject({
      type: 'codeBlock',
      content: [{ type: 'text', text: 'fenced\\ncode', styles: {} }]
    })
  })

  it('normalizes ordinary horizontal spacing and removes single-backslash placeholder lines', () => {
    const markdown = [
      'npm install  安装依赖；双反斜杠：\\\\n',
      '',
      '`inline  code`  后文',
      '',
      '```text',
      'fenced  code',
      '```',
      '',
      '## 服务部署区域',
      '',
      '\\',
      '\\',
      '\\'
    ].join('\n')

    const blocks = JSON.parse(convertMarkdownToBlockNote(markdown, 'EUEFsRqmJ4')) as Array<Record<string, any>>

    expect(blocks[0]?.content).toEqual([
      { type: 'text', text: 'npm install 安装依赖；双反斜杠：\\n', styles: {} }
    ])
    expect(blocks[1]?.content).toEqual([
      { type: 'text', text: 'inline  code', styles: { code: true } },
      { type: 'text', text: ' 后文', styles: {} }
    ])
    expect(blocks[2]).toMatchObject({
      type: 'codeBlock',
      content: [{ type: 'text', text: 'fenced  code', styles: {} }]
    })
    expect(blocks).toHaveLength(4)
    expect(blocks[3]).toMatchObject({ type: 'heading', content: [{ text: '服务部署区域' }] })
  })

  it('logs in with the backend identity field', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 200,
        data: { token: 'test-token' }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 200,
        data: [{ id: 7, identifier: 'JLNX' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 200,
        data: []
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const api = createHttpApi({
      apiBaseUrl: 'http://localhost:5173/api',
      identity: 'hzw',
      password: 'secret'
    })

    await api.listProjects()
    await api.listDocumentTree(7)

    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      identity: 'hzw',
      password: 'secret'
    })
    expect(String(fetchMock.mock.calls[2]?.[0]))
      .toBe('http://localhost:5173/api/projects/7/documents/tree')
  })

  it('reads one Outline document through documents.info with a bearer token', async () => {
    const calls: Array<{ url: string; options: RequestInit }> = []
    const fetchImpl = vi.fn(async (input: URL | RequestInfo, options: RequestInit = {}) => {
      calls.push({ url: String(input), options })
      return new Response(JSON.stringify({
        data: {
          id: 'internal-uuid',
          urlId: 'EUEFsRqmJ4',
          url: '/doc/project-EUEFsRqmJ4',
          title: '项目开发概述',
          text: '# 项目开发概述'
        }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    const outlineApi = createOutlineApiClient({
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      fetchImpl
    })

    const document = await outlineApi.getDocument('EUEFsRqmJ4')

    expect(document.title).toBe('项目开发概述')
    expect(document.sourceUrl).toBe('http://outline.example/doc/project-EUEFsRqmJ4')
    expect(calls).toHaveLength(1)
    expect(calls[0]?.url).toBe('http://outline.example/api/documents.info')
    expect(calls[0]?.options.headers).toMatchObject({ Authorization: 'Bearer read-only-token' })
    expect(JSON.parse(String(calls[0]?.options.body))).toEqual({ id: 'EUEFsRqmJ4' })
    expect(calls[0]?.url).not.toContain('export')
  })

  it('resolves a document mention only through documents.info id/urlId/url', async () => {
    const internalId = '68f202a9-5425-4ae3-9318-4ef37d1bdffc'
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      data: {
        id: internalId,
        urlId: 'EUEFsRqmJ4',
        url: '/doc/project-EUEFsRqmJ4'
      }
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const outlineApi = createOutlineApiClient({
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      fetchImpl
    })

    const reference = await outlineApi.resolveDocumentMention(internalId)

    expect(reference).toEqual({
      internalId,
      outlineDocumentId: 'EUEFsRqmJ4',
      sourceUrl: 'http://outline.example/doc/project-EUEFsRqmJ4'
    })
    expect(JSON.parse(String(fetchImpl.mock.calls[0]?.[1]?.body))).toEqual({ id: internalId })
  })

  it('rejects mention metadata whose fixed URL path is not an Outline document', async () => {
    const internalId = '68f202a9-5425-4ae3-9318-4ef37d1bdffc'
    const outlineApi = createOutlineApiClient({
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      fetchImpl: async () => new Response(JSON.stringify({
        data: { id: internalId, urlId: 'Outside01', url: 'https://other.example/doc/outside' }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })

    await expect(outlineApi.resolveDocumentMention(internalId))
      .rejects.toThrow('Outline mention 文档 URL 非法')
  })

  it('streams a redirected attachment to disk without forwarding the token', async () => {
    const sample = fixture()
    const tempFile = path.join(sample.root, 'single-attachment.tmp')
    const chunkSize = 64 * 1024
    const chunkCount = 96
    let emittedChunks = 0
    const stream = new ReadableStream({
      pull(controller) {
        if (emittedChunks === chunkCount) {
          controller.close()
          return
        }
        emittedChunks += 1
        controller.enqueue(new Uint8Array(chunkSize).fill(emittedChunks % 251))
      }
    })
    const calls: Array<{ url: string; authorization?: string }> = []
    const fetchImpl = vi.fn(async (input: URL | RequestInfo, options: RequestInit = {}) => {
      const headers = new Headers(options.headers)
      calls.push({ url: String(input), authorization: headers.get('Authorization') ?? undefined })
      if (calls.length === 1) {
        return new Response(null, {
          status: 302,
          headers: { Location: 'https://objects.example/signed/report.pdf' }
        })
      }
      return new Response(stream, {
        status: 200,
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': "inline; filename=\"??????????report.pdf\"; filename*=UTF-8''%E3%80%90%E5%BA%94%E7%94%A8%E6%8A%A5%E5%91%8A%E3%80%91report.pdf"
        }
      })
    })

    const downloaded = await downloadOutlineAttachmentToFile({
      attachmentUrl: '/api/attachments.redirect?id=attachment-1',
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      tempFile,
      maxBytes: 10 * 1024 * 1024,
      fetchImpl
    })

    expect(downloaded.fileSize).toBe(chunkSize * chunkCount)
    expect(downloaded.fileName).toBe('【应用报告】report.pdf')
    expect(fs.statSync(tempFile).size).toBe(chunkSize * chunkCount)
    expect(emittedChunks).toBe(chunkCount)
    expect(calls).toEqual([
      { url: 'http://outline.example/api/attachments.redirect?id=attachment-1', authorization: 'Bearer read-only-token' },
      { url: 'https://objects.example/signed/report.pdf', authorization: undefined }
    ])
  })

  it('uses the authoritative ASCII filename when Outline omits filename*', async () => {
    const sample = fixture()
    const tempFile = path.join(sample.root, 'image.tmp')

    const downloaded = await downloadOutlineAttachmentToFile({
      attachmentUrl: '/api/attachments.redirect?id=image-1',
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      tempFile,
      maxBytes: 1024,
      fetchImpl: async () => new Response(Buffer.from('png'), {
        status: 200,
        headers: {
          'Content-Type': 'image/png',
          'Content-Disposition': 'inline; filename="1.png"'
        }
      })
    })

    expect(downloaded.fileName).toBe('1.png')
  })

  it('rejects an Outline attachment response without an authoritative filename', async () => {
    const sample = fixture()
    const tempFile = path.join(sample.root, 'missing-name.tmp')

    await expect(downloadOutlineAttachmentToFile({
      attachmentUrl: '/api/attachments.redirect?id=missing-name',
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      tempFile,
      maxBytes: 1024,
      fetchImpl: async () => new Response(Buffer.from('data'), {
        status: 200,
        headers: { 'Content-Type': 'application/octet-stream' }
      })
    })).rejects.toThrow('Outline 附件响应缺少 Content-Disposition 文件名')
  })

  it('does not fall back to filename when filename* is not UTF-8', async () => {
    const sample = fixture()
    const tempFile = path.join(sample.root, 'non-utf8-name.tmp')

    await expect(downloadOutlineAttachmentToFile({
      attachmentUrl: '/api/attachments.redirect?id=non-utf8-name',
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      tempFile,
      maxBytes: 1024,
      fetchImpl: async () => new Response(Buffer.from('data'), {
        status: 200,
        headers: {
          'Content-Disposition': "attachment; filename=\"safe.txt\"; filename*=ISO-8859-1''unsafe.txt"
        }
      })
    })).rejects.toThrow('Outline 附件响应的 filename* 必须使用 UTF-8')
  })

  it('uploads one attachment as streaming multipart instead of a File buffer', async () => {
    const sample = fixture()
    const attachmentPath = path.join(sample.root, 'large.pdf')
    const fileSize = 4 * 1024 * 1024
    fs.writeFileSync(attachmentPath, Buffer.alloc(fileSize, 7))
    let largestChunk = 0
    let transferredBytes = 0
    const fetchImpl = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, options = {}) => {
      const body = options.body as unknown as AsyncIterable<Uint8Array>
      expect(body).not.toBeInstanceOf(FormData)
      for await (const chunk of body) {
        largestChunk = Math.max(largestChunk, chunk.length)
        transferredBytes += chunk.length
      }
      return new Response(JSON.stringify({
        code: 200,
        data: {
          id: 9,
          fileSize,
          sha256: 'server-hash',
          url: '/api/project-documents/11/attachments/9/download'
        }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    const api = createHttpApi({
      apiBaseUrl: 'http://localhost:5173/api',
      token: 'linear-lite-token'
    })

    await api.uploadAttachment(11, 'outline:doc:attachment:file-1', attachmentPath, 'application/pdf')

    const headers = new Headers(fetchImpl.mock.calls[0]?.[1]?.headers)
    expect(headers.get('Content-Type')).toContain('multipart/form-data; boundary=')
    expect(Number(headers.get('Content-Length'))).toBe(transferredBytes)
    expect(transferredBytes).toBeGreaterThan(fileSize)
    expect(largestChunk).toBeLessThanOrEqual(64 * 1024)
  })

  it('removes a partial temp file when an Outline attachment exceeds the limit', async () => {
    const sample = fixture()
    const tempFile = path.join(sample.root, 'oversized.tmp')
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array(6))
        controller.close()
      }
    })

    await expect(downloadOutlineAttachmentToFile({
      attachmentUrl: '/api/attachments.redirect?id=large',
      outlineBaseUrl: 'http://outline.example',
      token: 'read-only-token',
      tempFile,
      maxBytes: 5,
      fetchImpl: async () => new Response(stream, {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="large.bin"' }
      })
    })).rejects.toThrow('Outline 附件超过 5 字节')
    expect(fs.existsSync(tempFile)).toBe(false)
  })

  it('preflights a complete API subtree, then migrates it sequentially', async () => {
    const sample = fixture()
    const api = new FakeApi()
    const documents = new Map([
      ['jIbDVtIQLv', { title: '安全扫描', markdown: '# 安全扫描' }],
      ['EUEFsRqmJ4', {
        title: '项目开发概述',
        markdown: '# 项目开发概述\n\n![](/api/attachments.redirect?id=settings-1)'
      }],
      ['RZAdPKfrmZ', {
        title: '20260122结果',
        markdown: '# 20260122结果\n\n[report.pdf 1077373](/api/attachments.redirect?id=report-1)\n\n'
          + '[项目开发概述](/doc/project-EUEFsRqmJ4)\n\n'
          + '[范围外文档](/doc/outside-Outside01)\n\n'
          + '[项目开发概述 mention](mention://87eb6b4f-5d18-4781-a970-e26ac16b1568/document/68f202a9-5425-4ae3-9318-4ef37d1bdffc)\n\n'
          + '[重复 mention](mention://87eb6b4f-5d18-4781-a970-e26ac16b1568/document/68f202a9-5425-4ae3-9318-4ef37d1bdffc)\n\n'
          + '[范围外 mention](mention://87eb6b4f-5d18-4781-a970-e26ac16b1568/document/11111111-2222-4333-8444-555555555555)'
      }]
    ])
    let activeDownloads = 0
    let maxActiveDownloads = 0
    const tempFiles: string[] = []
    const mentionCalls: string[] = []
    const documentReadCalls: string[] = []
    const outlineApi = {
      async getDocument(outlineDocumentId: string) {
        documentReadCalls.push(outlineDocumentId)
        const value = documents.get(outlineDocumentId)!
        return { outlineDocumentId, ...value }
      },
      async resolveDocumentMention(documentInternalId: string) {
        mentionCalls.push(documentInternalId)
        if (documentInternalId === '68f202a9-5425-4ae3-9318-4ef37d1bdffc') {
          return {
            internalId: documentInternalId,
            outlineDocumentId: 'EUEFsRqmJ4',
            sourceUrl: 'http://outline.example/doc/project-EUEFsRqmJ4'
          }
        }
        return {
          internalId: documentInternalId,
          outlineDocumentId: 'Outside01',
          sourceUrl: 'http://outline.example/doc/outside-Outside01'
        }
      },
      async downloadAttachment({ attachmentUrl, tempFile }: { attachmentUrl: string; tempFile: string }) {
        activeDownloads += 1
        maxActiveDownloads = Math.max(maxActiveDownloads, activeDownloads)
        tempFiles.push(tempFile)
        const attachmentId = new URL(attachmentUrl).searchParams.get('id')!
        const bytes = attachmentId === 'report-1'
          ? Buffer.alloc(1077373, 7)
          : Buffer.from(attachmentId)
        fs.writeFileSync(tempFile, bytes)
        activeDownloads -= 1
        return {
          tempFile,
          fileName: attachmentId === 'report-1' ? 'report.pdf' : 'settings.xml',
          fileSize: bytes.length,
          sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
          contentType: 'application/octet-stream'
        }
      }
    }
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [
        { outlineDocumentId: 'jIbDVtIQLv', title: '安全扫描', parentOutlineDocumentId: null, sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/scan-jIbDVtIQLv' },
        { outlineDocumentId: 'EUEFsRqmJ4', title: '项目开发概述', parentOutlineDocumentId: 'jIbDVtIQLv', sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/project-EUEFsRqmJ4' },
        { outlineDocumentId: 'RZAdPKfrmZ', title: '20260122结果', parentOutlineDocumentId: 'jIbDVtIQLv', sortOrder: 1,
          sourceUrl: 'http://outline.example/doc/result-RZAdPKfrmZ' }
      ]
    }

    const result = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'jIbDVtIQLv'
    })

    expect(result.createdDocuments).toBe(3)
    expect(result.uploadedAttachments).toBe(2)
    expect(api.attachmentsBySourceId.get('outline:RZAdPKfrmZ:attachment:report-1')?.fileName)
      .toBe('report.pdf')
    expect(maxActiveDownloads).toBe(1)
    expect(new Set(tempFiles).size).toBe(2)
    expect(tempFiles.every(file => !fs.existsSync(file))).toBe(true)
    const childContent = api.documentsByExternalId.get('RZAdPKfrmZ')?.content
    expect(childContent).toContain('/projects/7/documents/')
    expect(childContent).not.toContain('1077373')
    expect(childContent).toContain('http://outline.example/doc/outside-Outside01')
    expect(mentionCalls).toEqual([
      '68f202a9-5425-4ae3-9318-4ef37d1bdffc',
      '11111111-2222-4333-8444-555555555555'
    ])

    documentReadCalls.length = 0
    const batchIdempotent = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'jIbDVtIQLv'
    })
    expect(batchIdempotent).toMatchObject({
      documents: 3,
      migratedDocuments: 3,
      blockedDocuments: 0,
      createdDocuments: 0,
      updatedDocuments: 0,
      uploadedAttachments: 0
    })
    expect(documentReadCalls).toEqual(['jIbDVtIQLv', 'EUEFsRqmJ4', 'RZAdPKfrmZ'])

    documents.set('RZAdPKfrmZ', {
      ...documents.get('RZAdPKfrmZ')!,
      markdown: `${documents.get('RZAdPKfrmZ')!.markdown}\n\n增量更新`
    })
    documentReadCalls.length = 0
    const targeted = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'RZAdPKfrmZ'
    })

    expect(targeted).toMatchObject({
      documents: 1,
      createdDocuments: 0,
      updatedDocuments: 1,
      uploadedAttachments: 0
    })
    expect(documentReadCalls).toEqual(['RZAdPKfrmZ'])
    expect(api.documentsByExternalId.get('RZAdPKfrmZ')?.content).toContain('增量更新')
    expect(api.documentsByExternalId.get('RZAdPKfrmZ')?.content).toContain('/projects/7/documents/')

    documentReadCalls.length = 0
    const idempotent = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'RZAdPKfrmZ'
    })
    expect(idempotent).toMatchObject({
      documents: 1,
      createdDocuments: 0,
      updatedDocuments: 0,
      uploadedAttachments: 0
    })
    expect(documentReadCalls).toEqual(['RZAdPKfrmZ'])
  })

  it('rejects an API subtree whose root is absent from the manifest', async () => {
    const sample = fixture()
    const manifest = {
      ...sample.manifest,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example'
    }

    await expect(migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: new FakeApi(),
      outlineApi: {},
      subtreeRootOutlineDocumentId: 'Missing01'
    })).rejects.toThrow('子树根节点不在迁移清单中: Missing01')
  })

  it('requires every non-target manifest document to have an existing state mapping', async () => {
    const sample = fixture()
    const manifest = {
      ...sample.manifest,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example'
    }

    await expect(migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: new FakeApi(),
      outlineApi: {},
      subtreeRootOutlineDocumentId: 'RZAdPKfrmZ'
    })).rejects.toThrow('子树迁移的清单引用尚未映射: jIbDVtIQLv')
  })

  it('does not treat a non-document mention as a document-link fallback', async () => {
    const sample = fixture()
    const api = new FakeApi()
    const resolveDocumentMention = vi.fn()
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [{
        outlineDocumentId: 'jIbDVtIQLv',
        title: '安全扫描',
        parentOutlineDocumentId: null,
        sortOrder: 0,
        sourceUrl: 'http://outline.example/doc/scan-jIbDVtIQLv'
      }]
    }
    const outlineApi = {
      async getDocument() {
        return {
          outlineDocumentId: 'jIbDVtIQLv',
          title: '安全扫描',
          markdown: '[用户](mention://87eb6b4f-5d18-4781-a970-e26ac16b1568/user/68f202a9-5425-4ae3-9318-4ef37d1bdffc)'
        }
      },
      resolveDocumentMention
    }

    const result = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'jIbDVtIQLv'
    })
    expect(result).toMatchObject({
      documents: 1,
      migratedDocuments: 0,
      blockedDocuments: 1,
      createdDocuments: 0
    })
    expect(fs.readFileSync(sample.blockedReportPath, 'utf8')).toContain('LINK_INVALID')
    expect(resolveDocumentMention).not.toHaveBeenCalled()
  })

  it('records a hard-gated node and continues migrating eligible siblings', async () => {
    const sample = fixture()
    const api = new FakeApi()
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [
        { outlineDocumentId: 'Root000001', title: '批次根', parentOutlineDocumentId: null, sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/root-Root000001' },
        { outlineDocumentId: 'Blocked001', title: '超限附件', parentOutlineDocumentId: 'Root000001', sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/blocked-Blocked001' },
        { outlineDocumentId: 'Dependent1', title: '依赖阻断父级', parentOutlineDocumentId: 'Blocked001', sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/dependent-Dependent1' },
        { outlineDocumentId: 'Eligible01', title: '正常节点', parentOutlineDocumentId: 'Root000001', sortOrder: 1,
          sourceUrl: 'http://outline.example/doc/eligible-Eligible01' }
      ]
    }
    const outlineApi = {
      async getDocument(outlineDocumentId: string) {
        const entry = manifest.documents.find(item => item.outlineDocumentId === outlineDocumentId)!
        return {
          outlineDocumentId,
          title: entry.title,
          markdown: outlineDocumentId === 'Blocked001'
            ? '[large.bin](/api/attachments.redirect?id=large)'
            : outlineDocumentId === 'Dependent1'
              ? '[should-not-download.bin](/api/attachments.redirect?id=dependent)'
            : `# ${entry.title}`
        }
      },
      async downloadAttachment({ attachmentUrl }: { attachmentUrl: string }) {
        const attachmentId = new URL(attachmentUrl).searchParams.get('id')
        if (attachmentId === 'dependent') throw new Error('依赖节点附件不应下载')
        throw new Error('Outline 附件超过 52428800 字节')
      },
      async resolveDocumentMention() {
        throw new Error('unexpected mention')
      }
    }

    const result = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'Root000001'
    })

    expect(result).toMatchObject({
      documents: 4,
      migratedDocuments: 2,
      blockedDocuments: 2,
      createdDocuments: 2
    })
    expect(api.documentsByExternalId.has('Blocked001')).toBe(false)
    expect(api.documentsByExternalId.has('Eligible01')).toBe(true)
    const report = fs.readFileSync(sample.blockedReportPath, 'utf8')
    expect(report).toContain('Blocked001')
    expect(report).toContain('ATTACHMENT_BLOCKED')
    expect(report).toContain('Outline 附件超过 52428800 字节')
    expect(report).toContain('Dependent1')
    expect(report).toContain('PARENT_BLOCKED')
  })

  it('reuses a verified attachment spool across apply and verification passes', async () => {
    const sample = fixture()
    const api = new FakeApi()
    const attachmentCacheDirectory = path.join(sample.root, 'attachment-spool')
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [{
        outlineDocumentId: 'Cached0001',
        title: '缓存节点',
        parentOutlineDocumentId: null,
        sortOrder: 0,
        sourceUrl: 'http://outline.example/doc/cached-Cached0001'
      }]
    }
    let downloads = 0
    const outlineApi = {
      async getDocument() {
        return {
          outlineDocumentId: 'Cached0001',
          title: '缓存节点',
          markdown: '[cache.bin](/api/attachments.redirect?id=cache-1)'
        }
      },
      async downloadAttachment({ tempFile }: { tempFile: string }) {
        downloads += 1
        const bytes = Buffer.from('cache-body')
        fs.writeFileSync(tempFile, bytes, { mode: 0o600 })
        return {
          tempFile,
          fileName: 'cache.bin',
          fileSize: bytes.length,
          sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
          contentType: 'application/octet-stream'
        }
      }
    }

    const first = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      attachmentCacheDirectory,
      subtreeRootOutlineDocumentId: 'Cached0001'
    })
    const second = await migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      attachmentCacheDirectory,
      subtreeRootOutlineDocumentId: 'Cached0001'
    })

    expect(downloads).toBe(1)
    expect(first).toMatchObject({ attachmentBytesDownloaded: 10, attachmentCacheHits: 0 })
    expect(second).toMatchObject({
      createdDocuments: 0,
      updatedDocuments: 0,
      uploadedAttachments: 0,
      attachmentBytesDownloaded: 0,
      attachmentCacheHits: 1
    })
  })

  it('retries transient source failures and never records them as hard-gated nodes', async () => {
    const sample = fixture()
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [{
        outlineDocumentId: 'Network001',
        title: '网络节点',
        parentOutlineDocumentId: null,
        sortOrder: 0,
        sourceUrl: 'http://outline.example/doc/network-Network001'
      }]
    }
    const getDocument = vi.fn(async () => {
      throw new TypeError('fetch failed')
    })
    const progress = vi.fn()

    await expect(migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: new FakeApi(),
      outlineApi: { getDocument },
      subtreeRootOutlineDocumentId: 'Network001',
      transientRetryAttempts: 3,
      transientRetryDelayMs: 0,
      onProgress: progress
    })).rejects.toThrow('fetch failed')

    expect(getDocument).toHaveBeenCalledTimes(3)
    expect(progress.mock.calls.filter(([event]) => event === 'transient_retry')).toHaveLength(2)
    expect(fs.existsSync(sample.blockedReportPath)).toBe(false)
  })

  it('rejects a target tree whose persisted sibling order differs from the catalog', async () => {
    const sample = fixture()
    const api = new FakeApi()
    const manifest = {
      version: 1,
      sourceMode: 'outline-api',
      outlineBaseUrl: 'http://outline.example',
      projectIdentifier: 'JLNX',
      documents: [
        {
          outlineDocumentId: 'OrderRoot1',
          title: '顺序根节点',
          parentOutlineDocumentId: null,
          sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/order-OrderRoot1'
        },
        {
          outlineDocumentId: 'Order00001',
          title: '顺序节点一',
          parentOutlineDocumentId: 'OrderRoot1',
          sortOrder: 0,
          sourceUrl: 'http://outline.example/doc/order-Order00001'
        },
        {
          outlineDocumentId: 'Order00002',
          title: '顺序节点二',
          parentOutlineDocumentId: 'OrderRoot1',
          sortOrder: 1,
          sourceUrl: 'http://outline.example/doc/order-Order00002'
        }
      ]
    }
    api.listDocumentTree = async projectId => (await FakeApi.prototype.listDocumentTree.call(api, projectId))
      .map(document => document.parentDocumentId == null
        ? document
        : { ...document, sortOrder: 1 - document.sortOrder })
    const outlineApi = {
      async getDocument(outlineDocumentId: string) {
        const entry = manifest.documents.find(document => document.outlineDocumentId === outlineDocumentId)!
        return {
          outlineDocumentId,
          title: entry.title,
          markdown: `# ${entry.title}`
        }
      }
    }

    await expect(migrateOutlineApiDocuments({
      manifest,
      statePath: sample.statePath,
      blockedReportPath: sample.blockedReportPath,
      linearLiteApi: api,
      outlineApi,
      subtreeRootOutlineDocumentId: 'OrderRoot1'
    })).rejects.toThrow('目标同级文档顺序与 catalog 不一致: OrderRoot1')
  })

  it('keeps hierarchy, uses numeric project routes, and remains idempotent without local state', async () => {
    const sample = fixture()
    const api = new FakeApi()

    const first = await migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })
    expect(first.createdDocuments).toBe(3)
    expect(api.actualCreatedDocuments).toBe(3)
    expect(api.actualUploadedAttachments).toBe(2)
    const child = api.documentsByExternalId.get('RZAdPKfrmZ')!
    const parent = api.documentsByExternalId.get('jIbDVtIQLv')!
    expect(child.parentDocumentId).toBe(parent.id)
    expect(child.content).toContain(`/projects/7/documents/`)
    expect(child.content).not.toContain('/projects/JLNX/documents/')
    expect(child.content).toContain('http://124.223.84.101:8888/doc/dev-rule-Outside01')

    const second = await migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })
    expect(second.createdDocuments).toBe(0)
    expect(second.uploadedAttachments).toBe(0)
    expect(second.updatedDocuments).toBe(0)

    fs.rmSync(sample.statePath)
    await migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })
    expect(api.actualCreatedDocuments).toBe(3)
    expect(api.actualUploadedAttachments).toBe(2)
  })

  it('fails when a state hit points to a different external binding', async () => {
    const sample = fixture()
    const api = new FakeApi()
    await migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })
    api.documentsByExternalId.get('EUEFsRqmJ4')!.externalSourceId = 'wrong'

    await expect(migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })).rejects.toThrow('目标文档的 Outline 映射不一致')
  })

  it('fails when the server reuses an Outline document under the wrong parent', async () => {
    const sample = fixture()
    const api = new FakeApi()
    await migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })
    fs.rmSync(sample.statePath)
    api.documentsByExternalId.get('RZAdPKfrmZ')!.parentDocumentId = null

    await expect(migrateOutlineDocuments({
      exportDir: sample.exportDir,
      manifest: sample.manifest,
      statePath: sample.statePath,
      api
    })).rejects.toThrow('服务端复用文档的父级与 manifest 不一致')
  })
})
