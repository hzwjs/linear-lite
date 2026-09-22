import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const documentId = 901111
const attachmentId = 77111
const contentHash = '1111111111111111111111111111111111111111111111111111111111111111'
const fixturePath = fileURLToPath(new URL('../../docs/测试/LINEAR-LITE-111/图片夹具.png', import.meta.url))
const fixture = await readFile(fixturePath)
let savedContent = ''
let uploadCount = 0
let assetReadCount = 0
let lastUploadName = ''

function sendJson(response, body, status = 200) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(body))
}

function assetRecord(fileName = '图片夹具.png') {
  return {
    id: attachmentId,
    projectId: 901,
    documentId,
    sourceId: null,
    fileName,
    fileSize: fixture.byteLength,
    contentType: 'image/png',
    sha256: contentHash,
    width: 320,
    height: 180,
    thumbnailUrl: null,
    url: `/api/project-documents/${documentId}/attachments/${attachmentId}/download`,
    createdAt: '2026-09-16T00:00:00Z',
  }
}

function imageAsset() {
  return {
    assetId: attachmentId,
    contentHash,
    width: 320,
    height: 180,
    thumbnailUrl: null,
    originalUrl: `/api/document-assets/${documentId}/${attachmentId}/${contentHash}/original`,
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://127.0.0.1')
  if (request.method === 'POST' && url.pathname === `/api/project-documents/${documentId}/attachments`) {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const multipart = Buffer.concat(chunks).toString('latin1')
    lastUploadName = multipart.match(/filename="([^"]+)"/)?.[1] ?? 'unknown'
    uploadCount++
    sendJson(response, { code: 200, message: 'OK', data: assetRecord(lastUploadName) })
    return
  }

  if (request.method === 'GET' && url.pathname === `/api/document-assets/${documentId}/${attachmentId}/${contentHash}/original`) {
    assetReadCount++
    response.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': fixture.byteLength, 'Cache-Control': 'no-store' })
    response.end(fixture)
    return
  }

  if (request.method === 'GET' && url.pathname === `/api/__harness/documents/${documentId}`) {
    sendJson(response, { content: savedContent, imageAssets: uploadCount > 0 ? [imageAsset()] : [] })
    return
  }

  if (request.method === 'PUT' && url.pathname === `/api/__harness/documents/${documentId}/content`) {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (typeof body.content !== 'string') {
      sendJson(response, { message: 'content must be a string' }, 400)
      return
    }
    savedContent = body.content
    sendJson(response, { code: 200, data: { saved: true } })
    return
  }

  if (request.method === 'GET' && url.pathname === '/api/__harness/status') {
    sendJson(response, { uploadCount, assetReadCount, lastUploadName, savedContent })
    return
  }

  sendJson(response, { message: 'No local mock route matched', path: url.pathname }, 404)
})

server.listen(9080, '127.0.0.1', () => {
  console.log('LINEAR-LITE-111 local mock API listening on 127.0.0.1:9080')
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
