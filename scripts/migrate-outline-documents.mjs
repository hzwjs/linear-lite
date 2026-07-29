/**
 * 将 Outline 文档迁移到 Linear Lite 项目文档。默认使用只读 API 在线源模式，
 * 不创建 collection/workspace export job。
 *
 * 文档和附件都只使用 manifest 中的 Outline 稳定 ID 建立映射，不按标题或文件名猜测。
 * 在线模式示例：
 *   OUTLINE_BASE_URL=http://outline.example OUTLINE_API_TOKEN=<read-only-token> JWT=<token> \
 *     node scripts/migrate-outline-documents.mjs \
 *     --manifest docs/migrations/outline-jlnx-api-pilot-manifest.json \
 *     --document-id <outline-url-id> \
 *     --state /tmp/jlnx-pilot-state.json
 *
 * 旧导出目录兼容模式：
 *   JWT=<token> node scripts/migrate-outline-documents.mjs \
 *     --export-dir /tmp/outline-export \
 *     --manifest /tmp/jlnx-pilot.json \
 *     --state /tmp/jlnx-pilot-state.json
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'
import { BlockNoteEditor } from '@blocknote/core'

const DEFAULT_API_BASE_URL = 'http://localhost:5173/api'
const DEFAULT_MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024
const MARKDOWN_LINK_PATTERN = /(!?\[[^\]]*]\()(<[^>\n]+>|[^)\n]+)(\))/g
const OUTLINE_DOCUMENT_MENTION_PATTERN = /^mention:\/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/document\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i

let blockNoteEditor

function ensureBlockNoteEditor() {
  if (blockNoteEditor) return blockNoteEditor
  const dom = new JSDOM('<!doctype html><html><body></body></html>')
  const globals = {
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    Node: dom.window.Node,
    HTMLElement: dom.window.HTMLElement,
    getComputedStyle: dom.window.getComputedStyle
  }
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, name, { value, configurable: true })
  }
  blockNoteEditor = BlockNoteEditor.create()
  return blockNoteEditor
}

export function convertMarkdownToBlockNote(markdown, outlineDocumentId) {
  const namespace = typeof outlineDocumentId === 'string' ? outlineDocumentId.trim() : ''
  if (!namespace) throw new Error('Markdown 转换必须提供 Outline 文档 ID')
  const normalizedMarkdown = normalizeOutlineLiteralNewlines(markdown)
  const blocks = ensureBlockNoteEditor().tryParseMarkdownToBlocks(normalizedMarkdown)
  // 覆盖 BlockNote 的随机 ID；持久化完整 Block JSON，同时保证幂等重跑字节稳定。
  return JSON.stringify(assignStableBlockIds(blocks, namespace))
}

function normalizeOutlineLiteralNewlines(markdown) {
  const lines = String(markdown).split(/(\r?\n)/)
  let fence = null
  let inlineCodeTicks = null

  return lines.map(part => {
    if (part === '\n' || part === '\r\n') return part

    if (fence) {
      const closingFence = part.match(/^ {0,3}(`+|~+)\s*$/)
      if (closingFence?.[1]?.[0] === fence.marker
          && closingFence[1].length >= fence.length) {
        fence = null
      }
      return part
    }

    if (inlineCodeTicks == null) {
      if (/^[\t ]*\\[\t ]*$/.test(part)) {
        // Outline 用单反斜杠占位的空行不应渲染出可见字符；只清空该行，换行分隔符仍由外层保留。
        return ''
      }
      const openingFence = part.match(/^ {0,3}(`{3,}|~{3,})(.*)$/)
      if (openingFence && !(openingFence[1][0] === '`' && openingFence[2].includes('`'))) {
        fence = { marker: openingFence[1][0], length: openingFence[1].length }
        return part
      }
    }

    let normalized = ''
    for (let index = 0; index < part.length;) {
      if (part[index] === '`') {
        let end = index + 1
        while (part[end] === '`') end += 1
        const tickCount = end - index
        if (inlineCodeTicks == null) inlineCodeTicks = tickCount
        else if (inlineCodeTicks === tickCount) inlineCodeTicks = null
        normalized += part.slice(index, end)
        index = end
        continue
      }

      if (inlineCodeTicks == null && (part[index] === ' ' || part[index] === '\t')) {
        let end = index + 1
        while (part[end] === ' ' || part[end] === '\t') end += 1
        const whitespace = part.slice(index, end)
        const hasTextBefore = /\S/.test(normalized)
        const hasTextAfter = end < part.length && /\S/.test(part[end])
        if (!hasTextBefore || !hasTextAfter) normalized += whitespace
        else if (/\S$/.test(normalized)) normalized += ' '
        // 若字面量 `\n` 已贡献分隔空格，则丢弃紧随其后的重复水平空白。
        index = end
        continue
      }

      const isSingleLiteralNewline = inlineCodeTicks == null
        && part[index] === '\\'
        && part[index + 1] === 'n'
        && part[index - 1] !== '\\'
      if (isSingleLiteralNewline) {
        // Outline 正文偶尔把视觉换行序列化为字面量 `\n`；仅普通文本转为空格，代码内容保持原样。
        normalized += ' '
        index += 2
        continue
      }

      normalized += part[index]
      index += 1
    }
    return normalized
  }).join('')
}

function assignStableBlockIds(blocks, namespace, parentPath = []) {
  return blocks.map((block, index) => {
    const blockPath = [...parentPath, index]
    const { id: _generatedId, children, ...rest } = block
    return {
      id: stableBlockUuid(namespace, blockPath),
      ...rest,
      children: Array.isArray(children)
        ? assignStableBlockIds(children, namespace, blockPath)
        : []
    }
  })
}

function stableBlockUuid(outlineDocumentId, blockPath) {
  const bytes = crypto.createHash('sha256')
    .update(`linear-lite:outline:${outlineDocumentId}:block:${blockPath.join('.')}`)
    .digest()
    .subarray(0, 16)
  // 使用 UUID v4/variant 位布局；内容仍由 Outline ID + 块路径确定，不依赖随机数。
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function validateManifest(manifest, exportDir) {
  if (!manifest || manifest.version !== 1) {
    throw new Error('manifest.version 必须为 1')
  }
  if (!manifest.projectIdentifier || !String(manifest.projectIdentifier).trim()) {
    throw new Error('manifest.projectIdentifier 不能为空')
  }
  if (!Array.isArray(manifest.documents) || manifest.documents.length === 0) {
    throw new Error('manifest.documents 不能为空')
  }

  const ids = new Set()
  const paths = new Set()
  for (const document of manifest.documents) {
    if (!document.outlineDocumentId || !String(document.outlineDocumentId).trim()) {
      throw new Error('每篇文档都必须提供 outlineDocumentId')
    }
    if (ids.has(document.outlineDocumentId)) {
      throw new Error(`outlineDocumentId 重复: ${document.outlineDocumentId}`)
    }
    ids.add(document.outlineDocumentId)
    if (!document.title || !String(document.title).trim()) {
      throw new Error(`文档 ${document.outlineDocumentId} 的 title 不能为空`)
    }
    if (!document.markdownPath || path.isAbsolute(document.markdownPath)) {
      throw new Error(`文档 ${document.outlineDocumentId} 必须提供导出目录内的 markdownPath`)
    }
    const normalizedPath = normalizeExportPath(document.markdownPath)
    if (paths.has(normalizedPath)) {
      throw new Error(`markdownPath 重复: ${normalizedPath}`)
    }
    paths.add(normalizedPath)
    const absolutePath = resolveInsideExport(exportDir, normalizedPath)
    if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) {
      throw new Error(`Markdown 文件不存在: ${normalizedPath}`)
    }
    if (document.parentOutlineDocumentId != null
        && !manifest.documents.some(item => item.outlineDocumentId === document.parentOutlineDocumentId)) {
      throw new Error(`父文档不在迁移清单中: ${document.parentOutlineDocumentId}`)
    }
    if (!Number.isInteger(document.sortOrder) || document.sortOrder < 0) {
      throw new Error(`文档 ${document.outlineDocumentId} 的 sortOrder 必须是非负整数`)
    }
  }
  return manifest
}

export function createHttpApi({
  apiBaseUrl = DEFAULT_API_BASE_URL,
  token,
  identity,
  password
}) {
  let bearerToken = token

  async function getToken() {
    if (bearerToken) return bearerToken
    if (!identity || !password) {
      throw new Error('请设置 JWT，或同时设置 IDENTITY 和 PASSWORD')
    }
    const response = await fetch(`${apiBaseUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identity, password })
    })
    const body = await readApiBody(response)
    if (!body.data?.token) throw new Error('登录响应中缺少 token')
    bearerToken = body.data.token
    return bearerToken
  }

  async function request(apiPath, options = {}) {
    const authToken = await getToken()
    const headers = new Headers(options.headers)
    headers.set('Authorization', `Bearer ${authToken}`)
    if (options.body != null && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    const response = await fetch(`${apiBaseUrl}${apiPath}`, { ...options, headers })
    return readApiBody(response)
  }

  return {
    async listProjects() {
      return (await request('/projects')).data
    },
    async getDocument(documentId) {
      return (await request(`/project-documents/${documentId}`)).data
    },
    async createDocument(projectId, body) {
      return (await request(`/projects/${projectId}/documents`, {
        method: 'POST',
        body: JSON.stringify(body)
      })).data
    },
    async updateDocument(documentId, body) {
      return (await request(`/project-documents/${documentId}`, {
        method: 'PUT',
        body: JSON.stringify(body)
      })).data
    },
    async uploadAttachment(documentId, sourceId, absolutePath, contentType, fileName = path.basename(absolutePath)) {
      const boundary = `linear-lite-${crypto.randomUUID()}`
      const escapedFileName = fileName.replace(/["\r\n]/g, '_')
      const prefix = Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="sourceId"\r\n\r\n${sourceId}\r\n`
        + `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${escapedFileName}"\r\n`
        + `Content-Type: ${contentType}\r\n\r\n`)
      const suffix = Buffer.from(`\r\n--${boundary}--\r\n`)
      const fileSize = fs.statSync(absolutePath).size
      // Node fetch 的 streaming request body 只保留当前 chunk，不把整个附件读入内存。
      const body = Readable.from((async function* multipartBody() {
        yield prefix
        for await (const chunk of fs.createReadStream(absolutePath)) yield chunk
        yield suffix
      })())
      return (await request(`/project-documents/${documentId}/attachments`, {
        method: 'POST',
        body,
        duplex: 'half',
        headers: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
          'Content-Length': String(prefix.length + fileSize + suffix.length)
        }
      })).data
    }
  }
}

export function createOutlineApiClient({ outlineBaseUrl, token, fetchImpl = fetch }) {
  if (!outlineBaseUrl || !/^https?:\/\//i.test(outlineBaseUrl)) {
    throw new Error('OUTLINE_BASE_URL 必须是 http(s) 地址')
  }
  if (!token || !String(token).trim()) {
    throw new Error('OUTLINE_API_TOKEN 不能为空')
  }
  const baseUrl = new URL(outlineBaseUrl)

  async function fetchDocumentInfo(id) {
    const response = await fetchImpl(new URL('/api/documents.info', baseUrl), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ id }),
      redirect: 'error'
    })
    return (await readOutlineApiBody(response)).data
  }

  return {
    async getDocument(outlineDocumentId) {
      const document = await fetchDocumentInfo(outlineDocumentId)
      if (!document || document.urlId !== outlineDocumentId) {
        throw new Error(`Outline 返回了错误的文档 ID: ${outlineDocumentId}`)
      }
      if (typeof document.title !== 'string' || !document.title.trim()) {
        throw new Error(`Outline 文档标题为空: ${outlineDocumentId}`)
      }
      if (typeof document.text !== 'string') {
        throw new Error(`Outline 文档正文不是 Markdown: ${outlineDocumentId}`)
      }
      return {
        outlineDocumentId: document.urlId,
        title: document.title.trim(),
        markdown: document.text
      }
    },

    async resolveDocumentMention(documentInternalId) {
      const document = await fetchDocumentInfo(documentInternalId)
      if (!document || document.id !== documentInternalId) {
        throw new Error(`Outline mention 返回了错误的内部文档 ID: ${documentInternalId}`)
      }
      if (typeof document.urlId !== 'string' || !document.urlId.trim()) {
        throw new Error(`Outline mention 文档缺少 urlId: ${documentInternalId}`)
      }
      if (typeof document.url !== 'string' || !document.url.trim()) {
        throw new Error(`Outline mention 文档缺少 url: ${documentInternalId}`)
      }
      const sourceUrl = new URL(document.url, baseUrl)
      // mention 的范围外链接只接受 Outline 自身 `/doc/` 路径，禁止跨源或猜测 slug。
      if (sourceUrl.origin !== baseUrl.origin || !sourceUrl.pathname.startsWith('/doc/')) {
        throw new Error(`Outline mention 文档 URL 非法: ${documentInternalId}`)
      }
      return {
        internalId: document.id,
        outlineDocumentId: document.urlId,
        sourceUrl: sourceUrl.toString()
      }
    },

    async downloadAttachment({ attachmentUrl, tempFile, maxBytes }) {
      return downloadOutlineAttachmentToFile({
        attachmentUrl,
        outlineBaseUrl: baseUrl.toString(),
        token,
        tempFile,
        maxBytes,
        fetchImpl
      })
    }
  }
}

async function readOutlineApiBody(response) {
  let body
  try {
    body = await response.json()
  } catch {
    throw new Error(`Outline API 返回非 JSON 响应: HTTP ${response.status}`)
  }
  if (!response.ok || body.ok === false || body.data == null) {
    throw new Error(body.error || body.message || `Outline API 请求失败: HTTP ${response.status}`)
  }
  return body
}

export async function downloadOutlineAttachmentToFile({
  attachmentUrl,
  outlineBaseUrl,
  token,
  tempFile,
  maxBytes,
  fetchImpl = fetch
}) {
  const outlineOrigin = new URL(outlineBaseUrl).origin
  let currentUrl = new URL(attachmentUrl, outlineBaseUrl)
  let response
  for (let redirectCount = 0; redirectCount <= 5; redirectCount += 1) {
    const headers = {}
    // Bearer 只发送到 Outline 自身；对象存储签名 URL 不接收 Outline token。
    if (currentUrl.origin === outlineOrigin) headers.Authorization = `Bearer ${token}`
    response = await fetchImpl(currentUrl, { headers, redirect: 'manual' })
    if (response.status < 300 || response.status >= 400) break
    const location = response.headers.get('location')
    if (!location) throw new Error('Outline 附件跳转缺少 Location')
    currentUrl = new URL(location, currentUrl)
    if (!/^https?:$/.test(currentUrl.protocol)) {
      throw new Error(`拒绝非 HTTP(S) 附件跳转: ${currentUrl.protocol}`)
    }
  }
  if (!response || !response.ok || !response.body) {
    throw new Error(`Outline 附件下载失败: HTTP ${response?.status ?? 'unknown'}`)
  }
  const fileName = outlineAttachmentFileNameFromContentDisposition(
    response.headers.get('content-disposition'))

  let size = 0
  const digest = crypto.createHash('sha256')
  const meter = new Transform({
    transform(chunk, _encoding, callback) {
      size += chunk.length
      if (size > maxBytes) {
        callback(new Error(`Outline 附件超过 ${maxBytes} 字节`))
        return
      }
      digest.update(chunk)
      callback(null, chunk)
    }
  })
  try {
    await pipeline(
      Readable.fromWeb(response.body),
      meter,
      fs.createWriteStream(tempFile, { flags: 'w', mode: 0o600 }))
  } catch (error) {
    fs.rmSync(tempFile, { force: true })
    throw error
  }
  return {
    tempFile,
    fileName,
    fileSize: size,
    sha256: digest.digest('hex'),
    contentType: response.headers.get('content-type') || 'application/octet-stream'
  }
}

async function readApiBody(response) {
  let body
  try {
    body = await response.json()
  } catch {
    throw new Error(`API 返回非 JSON 响应: HTTP ${response.status}`)
  }
  if (!response.ok || body.code !== 200) {
    throw new Error(body.message || `API 请求失败: HTTP ${response.status}`)
  }
  return body
}

export async function migrateOutlineDocuments({
  exportDir,
  manifest,
  statePath,
  api,
  dryRun = false,
  maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES
}) {
  const absoluteExportDir = path.resolve(exportDir)
  validateManifest(manifest, absoluteExportDir)
  const orderedDocuments = orderDocuments(manifest.documents)
  const inventory = orderedDocuments.map(document => inspectSourceDocument(
    document, absoluteExportDir, maxAttachmentBytes))

  if (dryRun) {
    return {
      dryRun: true,
      documents: inventory.length,
      attachments: inventory.reduce((sum, item) => sum + item.assets.length, 0),
      totalAttachmentBytes: inventory.reduce(
        (sum, item) => sum + item.assets.reduce((assetSum, asset) => assetSum + asset.size, 0), 0)
    }
  }
  if (!statePath) throw new Error('实际迁移必须显式提供 --state')
  if (!api) throw new Error('实际迁移必须提供 API 客户端')

  const state = loadState(statePath, manifest.projectIdentifier)
  const projects = await api.listProjects()
  const matches = projects.filter(project => project.identifier === manifest.projectIdentifier)
  if (matches.length !== 1) {
    throw new Error(`必须唯一定位项目 identifier=${manifest.projectIdentifier}，实际匹配 ${matches.length} 个`)
  }
  const project = matches[0]
  let createdDocuments = 0
  let updatedDocuments = 0
  let uploadedAttachments = 0

  // 第一遍只按 Outline ID 建立目标文档映射；初始正文与创建操作原子提交。
  for (const source of inventory) {
    const documentState = state.documents[source.outlineDocumentId]
    const parentId = source.parentOutlineDocumentId == null
      ? null
      : requireMappedDocumentId(state, source.parentOutlineDocumentId)
    if (documentState) {
      const existing = await api.getDocument(documentState.linearLiteDocumentId)
      if (existing.projectId !== project.id) {
        throw new Error(`状态中的文档不属于目标项目: ${source.outlineDocumentId}`)
      }
      requireOutlineBinding(existing, source.outlineDocumentId)
      if (existing.parentDocumentId !== parentId) {
        throw new Error(`目标文档父级与 manifest 不一致: ${source.outlineDocumentId}`)
      }
      documentState.version = existing.version
      continue
    }
    const initialContent = convertMarkdownToBlockNote(source.markdown, source.outlineDocumentId)
    const created = await api.createDocument(project.id, {
      parentDocumentId: parentId,
      title: source.title,
      content: initialContent,
      externalSource: 'outline',
      externalSourceId: source.outlineDocumentId
    })
    requireOutlineBinding(created, source.outlineDocumentId)
    if (created.parentDocumentId !== parentId) {
      throw new Error(`服务端复用文档的父级与 manifest 不一致: ${source.outlineDocumentId}`)
    }
    state.documents[source.outlineDocumentId] = {
      linearLiteDocumentId: created.id,
      version: created.version,
      title: source.title,
      contentSha256: sha256(initialContent),
      attachments: {}
    }
    saveState(statePath, state)
    createdDocuments += 1
  }

  const selectedBySourceUrl = new Map()
  const selectedByMarkdownPath = new Map()
  for (const document of manifest.documents) {
    if (document.sourceUrl) {
      selectedBySourceUrl.set(normalizeUrl(document.sourceUrl), document.outlineDocumentId)
    }
    selectedByMarkdownPath.set(normalizeExportPath(document.markdownPath), document.outlineDocumentId)
  }

  // 第二遍上传资源并重写链接；每完成一个附件和一篇正文就立即原子保存状态。
  for (const source of inventory) {
    const documentState = state.documents[source.outlineDocumentId]
    const rewritten = await rewriteMarkdownLinks({
      markdown: source.markdown,
      source,
      exportDir: absoluteExportDir,
      manifest,
      projectId: project.id,
      state,
      statePath,
      documentState,
      selectedBySourceUrl,
      selectedByMarkdownPath,
      api,
      maxAttachmentBytes,
      onAttachmentUploaded: () => { uploadedAttachments += 1 }
    })
    if (rewritten.includes('/api/attachments.redirect')) {
      throw new Error(`文档仍包含 Outline 附件地址: ${source.outlineDocumentId}`)
    }
    const content = convertMarkdownToBlockNote(rewritten, source.outlineDocumentId)
    const contentHash = sha256(content)
    if (documentState.contentSha256 === contentHash && documentState.title === source.title) {
      continue
    }
    const updated = await api.updateDocument(documentState.linearLiteDocumentId, {
      expectedVersion: documentState.version,
      title: source.title,
      content
    })
    documentState.version = updated.version
    documentState.title = source.title
    documentState.contentSha256 = contentHash
    saveState(statePath, state)
    updatedDocuments += 1
  }

  return {
    dryRun: false,
    projectId: project.id,
    documents: inventory.length,
    createdDocuments,
    updatedDocuments,
    uploadedAttachments
  }
}

export function validateOutlineApiManifest(manifest) {
  if (!manifest || manifest.version !== 1 || manifest.sourceMode !== 'outline-api') {
    throw new Error('在线迁移 manifest 必须设置 version=1、sourceMode=outline-api')
  }
  if (!manifest.projectIdentifier || !String(manifest.projectIdentifier).trim()) {
    throw new Error('manifest.projectIdentifier 不能为空')
  }
  if (!manifest.outlineBaseUrl || !/^https?:\/\//i.test(manifest.outlineBaseUrl)) {
    throw new Error('manifest.outlineBaseUrl 必须是 http(s) 地址')
  }
  if (!Array.isArray(manifest.documents) || manifest.documents.length === 0) {
    throw new Error('manifest.documents 不能为空')
  }
  const ids = new Set()
  for (const document of manifest.documents) {
    if (!document.outlineDocumentId || ids.has(document.outlineDocumentId)) {
      throw new Error(`Outline 文档 ID 为空或重复: ${document.outlineDocumentId ?? ''}`)
    }
    ids.add(document.outlineDocumentId)
    if (!document.title || !String(document.title).trim()) {
      throw new Error(`文档 ${document.outlineDocumentId} 必须提供期望标题`)
    }
    if (!document.sourceUrl || !/^https?:\/\//i.test(document.sourceUrl)) {
      throw new Error(`文档 ${document.outlineDocumentId} 必须提供 sourceUrl`)
    }
    if (!Number.isInteger(document.sortOrder) || document.sortOrder < 0) {
      throw new Error(`文档 ${document.outlineDocumentId} 的 sortOrder 必须是非负整数`)
    }
  }
  for (const document of manifest.documents) {
    if (document.parentOutlineDocumentId != null && !ids.has(document.parentOutlineDocumentId)) {
      throw new Error(`父文档不在迁移清单中: ${document.parentOutlineDocumentId}`)
    }
  }
  return manifest
}

export async function migrateOutlineApiDocuments({
  manifest,
  statePath,
  linearLiteApi,
  outlineApi,
  targetOutlineDocumentId,
  maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES
}) {
  validateOutlineApiManifest(manifest)
  if (!statePath) throw new Error('在线迁移必须显式提供 --state')
  if (!linearLiteApi || !outlineApi) throw new Error('在线迁移缺少 API 客户端')

  const state = loadState(statePath, manifest.projectIdentifier)
  const targetDocuments = targetOutlineDocumentId == null
    ? manifest.documents
    : manifest.documents.filter(document => document.outlineDocumentId === targetOutlineDocumentId)
  if (targetDocuments.length === 0) {
    throw new Error(`目标文档不在迁移清单中: ${targetOutlineDocumentId}`)
  }
  const targetOutlineIds = new Set(targetDocuments.map(document => document.outlineDocumentId))
  const referenceOutlineIds = manifest.documents
    .map(document => document.outlineDocumentId)
    .filter(outlineDocumentId => !targetOutlineIds.has(outlineDocumentId))
  // 单节点模式下，清单其余节点只提供父子和链接映射，禁止隐式迁移或按标题猜测。
  for (const outlineDocumentId of referenceOutlineIds) {
    if (!state.documents[outlineDocumentId]) {
      throw new Error(`单节点迁移的清单引用尚未映射: ${outlineDocumentId}`)
    }
  }
  const projects = await linearLiteApi.listProjects()
  const matches = projects.filter(project => project.identifier === manifest.projectIdentifier)
  if (matches.length !== 1) {
    throw new Error(`必须唯一定位项目 identifier=${manifest.projectIdentifier}，实际匹配 ${matches.length} 个`)
  }
  const project = matches[0]
  const orderedDocuments = orderDocuments(targetDocuments, referenceOutlineIds)
  const selectedBySourceUrl = new Map(manifest.documents.map(document => [
    normalizeUrl(document.sourceUrl), document.outlineDocumentId
  ]))
  const selectedOutlineIds = new Set(manifest.documents.map(document => document.outlineDocumentId))
  const mentionReferenceCache = new Map()
  let createdDocuments = 0
  let updatedDocuments = 0
  let uploadedAttachments = 0
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'outline-api-migration-'))
  const tempFile = path.join(tempDirectory, 'attachment.tmp')

  try {
    // 第一遍逐篇读取后立即丢弃 Markdown，只建立服务端 Outline ID 映射。
    for (const entry of orderedDocuments) {
      const parentId = entry.parentOutlineDocumentId == null
        ? null
        : requireMappedDocumentId(state, entry.parentOutlineDocumentId)
      const documentState = state.documents[entry.outlineDocumentId]
      if (documentState) {
        const existing = await linearLiteApi.getDocument(documentState.linearLiteDocumentId)
        requireExistingTarget(existing, project.id, entry.outlineDocumentId, parentId)
        documentState.version = existing.version
        continue
      }

      const source = await outlineApi.getDocument(entry.outlineDocumentId)
      requireExpectedOutlineTitle(source, entry)
      const initialContent = convertMarkdownToBlockNote(source.markdown, entry.outlineDocumentId)
      const created = await linearLiteApi.createDocument(project.id, {
        parentDocumentId: parentId,
        title: source.title,
        content: initialContent,
        externalSource: 'outline',
        externalSourceId: entry.outlineDocumentId
      })
      requireExistingTarget(created, project.id, entry.outlineDocumentId, parentId)
      state.documents[entry.outlineDocumentId] = {
        linearLiteDocumentId: created.id,
        version: created.version,
        title: source.title,
        contentSha256: sha256(initialContent),
        attachments: {}
      }
      saveState(statePath, state)
      createdDocuments += 1
    }

    // 第二遍仍逐篇处理；附件严格串行下载到同一个临时文件并在上传后立即删除。
    for (const entry of orderedDocuments) {
      const source = await outlineApi.getDocument(entry.outlineDocumentId)
      requireExpectedOutlineTitle(source, entry)
      const documentState = state.documents[entry.outlineDocumentId]
      const rewritten = await rewriteOutlineApiMarkdown({
        markdown: source.markdown,
        source,
        outlineBaseUrl: manifest.outlineBaseUrl,
        projectId: project.id,
        state,
        statePath,
        documentState,
        selectedBySourceUrl,
        selectedOutlineIds,
        mentionReferenceCache,
        outlineApi,
        linearLiteApi,
        tempFile,
        maxAttachmentBytes,
        onAttachmentUploaded: () => { uploadedAttachments += 1 }
      })
      if (rewritten.includes('/api/attachments.redirect')) {
        throw new Error(`文档仍包含 Outline 附件地址: ${entry.outlineDocumentId}`)
      }
      const content = convertMarkdownToBlockNote(rewritten, entry.outlineDocumentId)
      const contentHash = sha256(content)
      if (documentState.contentSha256 === contentHash && documentState.title === source.title) continue
      const updated = await linearLiteApi.updateDocument(documentState.linearLiteDocumentId, {
        expectedVersion: documentState.version,
        title: source.title,
        content
      })
      documentState.version = updated.version
      documentState.title = source.title
      documentState.contentSha256 = contentHash
      saveState(statePath, state)
      updatedDocuments += 1
    }
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true })
  }

  return {
    projectId: project.id,
    documents: orderedDocuments.length,
    createdDocuments,
    updatedDocuments,
    uploadedAttachments
  }
}

function requireExpectedOutlineTitle(source, entry) {
  if (source.title !== entry.title) {
    throw new Error(`Outline 文档标题与 manifest 不一致: ${entry.outlineDocumentId}`)
  }
}

function requireExistingTarget(document, projectId, outlineDocumentId, parentId) {
  if (document.projectId !== projectId) {
    throw new Error(`目标文档不属于 JLNX 项目: ${outlineDocumentId}`)
  }
  requireOutlineBinding(document, outlineDocumentId)
  if (document.parentDocumentId !== parentId) {
    throw new Error(`目标文档父级与 manifest 不一致: ${outlineDocumentId}`)
  }
}

async function rewriteOutlineApiMarkdown(context) {
  return replaceAsync(context.markdown, MARKDOWN_LINK_PATTERN, async (full, prefix, destination, suffix) => {
    const { href, titleSuffix, angled } = splitDestination(destination)
    const rewritten = await rewriteOutlineApiHref(href, context)
    const rewrittenPrefix = rewritten.attachmentFileName == null
      ? prefix
      : `${prefix.slice(0, prefix.indexOf('[') + 1)}${rewritten.attachmentFileName}](`
    return `${rewrittenPrefix}${angled ? `<${rewritten.href}>` : rewritten.href}${titleSuffix}${suffix}`
  })
}

async function rewriteOutlineApiHref(href, context) {
  if (isOutlineAttachmentHref(href, context.outlineBaseUrl)) {
    const attachmentUrl = new URL(href, context.outlineBaseUrl)
    const attachmentId = attachmentUrl.searchParams.get('id')
    if (!attachmentId) throw new Error(`Outline 附件地址缺少 id: ${href}`)
    const sourceId = `outline:${context.source.outlineDocumentId}:attachment:${attachmentId}`
    const downloaded = await context.outlineApi.downloadAttachment({
      attachmentUrl: attachmentUrl.toString(),
      tempFile: context.tempFile,
      maxBytes: context.maxAttachmentBytes
    })
    try {
      const fileName = downloaded.fileName
      const existing = context.documentState.attachments[sourceId]
      if (existing) {
        if (existing.sha256 !== downloaded.sha256 || existing.fileSize !== downloaded.fileSize) {
          throw new Error(`同一来源附件内容已变化: ${sourceId}`)
        }
        return { href: existing.url, attachmentFileName: fileName }
      }
      const uploaded = await context.linearLiteApi.uploadAttachment(
        context.documentState.linearLiteDocumentId,
        sourceId,
        downloaded.tempFile,
        downloaded.contentType,
        fileName)
      if (uploaded.sha256 !== downloaded.sha256 || uploaded.fileSize !== downloaded.fileSize) {
        throw new Error(`Linear Lite 附件校验失败: ${sourceId}`)
      }
      context.documentState.attachments[sourceId] = {
        attachmentId: uploaded.id,
        url: uploaded.url,
        fileSize: uploaded.fileSize,
        sha256: uploaded.sha256
      }
      saveState(context.statePath, context.state)
      context.onAttachmentUploaded()
      return { href: uploaded.url, attachmentFileName: fileName }
    } finally {
      fs.rmSync(context.tempFile, { force: true })
    }
  }
  if (href.startsWith('#') || href.startsWith('mailto:') || href.startsWith('tel:')) return { href }
  const documentMention = OUTLINE_DOCUMENT_MENTION_PATTERN.exec(href)
  if (documentMention) {
    const documentInternalId = documentMention[1]
    let referencePromise = context.mentionReferenceCache.get(documentInternalId)
    if (!referencePromise) {
      // 只按 mention 内部 UUID 查询一次；缓存不保存正文或附件。
      referencePromise = context.outlineApi.resolveDocumentMention(documentInternalId)
      context.mentionReferenceCache.set(documentInternalId, referencePromise)
    }
    const reference = await referencePromise
    return { href: context.selectedOutlineIds.has(reference.outlineDocumentId)
      ? documentUrl(context.state, reference.outlineDocumentId, context.projectId)
      : reference.sourceUrl }
  }
  if (/^https?:\/\//i.test(href)) {
    const selectedId = context.selectedBySourceUrl.get(normalizeUrl(href))
    return { href: selectedId ? documentUrl(context.state, selectedId, context.projectId) : href }
  }
  if (href.startsWith('/doc/')) {
    const absoluteOutlineUrl = new URL(href, context.outlineBaseUrl).toString()
    const selectedId = context.selectedBySourceUrl.get(normalizeUrl(absoluteOutlineUrl))
    return { href: selectedId
      ? documentUrl(context.state, selectedId, context.projectId)
      : absoluteOutlineUrl }
  }
  throw new Error(`在线 Outline 文档包含无法解析的相对链接: ${href}`)
}

function outlineAttachmentFileNameFromContentDisposition(contentDisposition) {
  if (!contentDisposition) {
    throw new Error('Outline 附件响应缺少 Content-Disposition 文件名')
  }

  const utf8Match = /(?:^|;)\s*filename\*=UTF-8''([^;]*)/i.exec(contentDisposition)
  let fileName
  if (utf8Match) {
    try {
      fileName = decodeURIComponent(utf8Match[1])
    } catch {
      throw new Error('Outline 附件响应的 UTF-8 filename* 非法')
    }
  } else {
    if (/(?:^|;)\s*filename\*/i.test(contentDisposition)) {
      throw new Error('Outline 附件响应的 filename* 必须使用 UTF-8')
    }
    // Outline 对纯 ASCII 文件名只返回 filename；非 ASCII 文件名必须走上面的 UTF-8 filename*。
    const asciiMatch = /(?:^|;)\s*filename="([\x20-\x21\x23-\x5b\x5d-\x7e]+)"/i.exec(contentDisposition)
    if (!asciiMatch) {
      throw new Error('Outline 附件响应缺少 Content-Disposition 文件名')
    }
    fileName = asciiMatch[1]
  }

  // 响应头是在线迁移的唯一文件名来源；拒绝路径和控制字符，避免把服务端文件名当成本地路径。
  if (!fileName || fileName !== fileName.trim() || /[\\/\u0000-\u001f\u007f]/.test(fileName)) {
    throw new Error('Outline 附件响应的文件名非法')
  }
  return fileName
}

function isOutlineAttachmentHref(href, outlineBaseUrl) {
  try {
    const value = new URL(href, outlineBaseUrl)
    return value.origin === new URL(outlineBaseUrl).origin
      && value.pathname === '/api/attachments.redirect'
  } catch {
    return false
  }
}

function inspectSourceDocument(document, exportDir, maxAttachmentBytes) {
  const markdownPath = normalizeExportPath(document.markdownPath)
  const absoluteMarkdownPath = resolveInsideExport(exportDir, markdownPath)
  const markdown = fs.readFileSync(absoluteMarkdownPath, 'utf8')
  const assets = collectLocalAssets(markdown, absoluteMarkdownPath, exportDir)
  for (const asset of assets) {
    if (asset.size > maxAttachmentBytes) {
      throw new Error(`附件超过 ${maxAttachmentBytes} 字节: ${asset.exportPath}`)
    }
  }
  return {
    outlineDocumentId: document.outlineDocumentId,
    parentOutlineDocumentId: document.parentOutlineDocumentId ?? null,
    title: document.title.trim(),
    sourceUrl: document.sourceUrl,
    sortOrder: document.sortOrder,
    markdownPath,
    absoluteMarkdownPath,
    markdown,
    assets
  }
}

export function collectLocalAssets(markdown, absoluteMarkdownPath, exportDir) {
  const assets = new Map()
  for (const match of markdown.matchAll(MARKDOWN_LINK_PATTERN)) {
    const { href } = splitDestination(match[2])
    if (!isRelativeFileReference(href)) continue
    const absolutePath = resolveMarkdownReference(absoluteMarkdownPath, href, exportDir)
    if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) continue
    if (path.extname(absolutePath).toLowerCase() === '.md') continue
    const exportPath = normalizeExportPath(path.relative(exportDir, absolutePath))
    assets.set(exportPath, {
      absolutePath,
      exportPath,
      size: fs.statSync(absolutePath).size,
      sha256: sha256(fs.readFileSync(absolutePath))
    })
  }
  return [...assets.values()]
}

async function rewriteMarkdownLinks(context) {
  return replaceAsync(context.markdown, MARKDOWN_LINK_PATTERN, async (full, prefix, destination, suffix) => {
    const { href, titleSuffix, angled } = splitDestination(destination)
    const rewrittenHref = await rewriteHref(href, context)
    const serializedHref = angled ? `<${rewrittenHref}>` : rewrittenHref
    return `${prefix}${serializedHref}${titleSuffix}${suffix}`
  })
}

async function rewriteHref(href, context) {
  if (href.startsWith('#') || href.startsWith('mailto:') || href.startsWith('tel:')) return href
  if (/^https?:\/\//i.test(href)) {
    const selectedId = context.selectedBySourceUrl.get(normalizeUrl(href))
    if (selectedId) return documentUrl(context.state, selectedId, context.projectId)
    // 范围外的绝对 Outline 文档链接保持源地址，不生成不可用的本地链接。
    return href
  }
  if (!isRelativeFileReference(href)) return href

  const absolutePath = resolveMarkdownReference(context.source.absoluteMarkdownPath, href, context.exportDir)
  const exportPath = normalizeExportPath(path.relative(context.exportDir, absolutePath))
  if (path.extname(absolutePath).toLowerCase() === '.md') {
    const selectedId = context.selectedByMarkdownPath.get(exportPath)
    if (selectedId) return documentUrl(context.state, selectedId, context.projectId)
    const externalUrl = context.manifest.externalDocumentUrls?.[exportPath]
    if (!externalUrl) {
      throw new Error(`范围外 Markdown 链接缺少 externalDocumentUrls 映射: ${exportPath}`)
    }
    return externalUrl
  }
  if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) {
    throw new Error(`本地附件不存在: ${exportPath}`)
  }
  const size = fs.statSync(absolutePath).size
  if (size > context.maxAttachmentBytes) {
    throw new Error(`附件超过 ${context.maxAttachmentBytes} 字节: ${exportPath}`)
  }
  const sourceId = `outline:${context.source.outlineDocumentId}:${exportPath}`
  const fileHash = sha256(fs.readFileSync(absolutePath))
  const existing = context.documentState.attachments[sourceId]
  if (existing) {
    if (existing.sha256 !== fileHash || existing.fileSize !== size) {
      throw new Error(`同一来源附件内容已变化: ${sourceId}`)
    }
    return existing.url
  }

  const uploaded = await context.api.uploadAttachment(
    context.documentState.linearLiteDocumentId,
    sourceId,
    absolutePath,
    contentTypeFor(absolutePath))
  context.documentState.attachments[sourceId] = {
    attachmentId: uploaded.id,
    url: uploaded.url,
    fileSize: uploaded.fileSize,
    sha256: uploaded.sha256
  }
  saveState(context.statePath, context.state)
  context.onAttachmentUploaded()
  return uploaded.url
}

function orderDocuments(documents, preEmittedOutlineIds = []) {
  const pending = [...documents]
  const ordered = []
  const emitted = new Set(preEmittedOutlineIds)
  while (pending.length > 0) {
    const ready = pending
      .filter(document => document.parentOutlineDocumentId == null
        || emitted.has(document.parentOutlineDocumentId))
      .sort((a, b) => {
        const parentA = a.parentOutlineDocumentId ?? ''
        const parentB = b.parentOutlineDocumentId ?? ''
        return parentA.localeCompare(parentB) || a.sortOrder - b.sortOrder
      })
    if (ready.length === 0) throw new Error('文档父子关系存在循环')
    for (const document of ready) {
      ordered.push(document)
      emitted.add(document.outlineDocumentId)
      pending.splice(pending.indexOf(document), 1)
    }
  }
  return ordered
}

function requireMappedDocumentId(state, outlineDocumentId) {
  const mapping = state.documents[outlineDocumentId]
  if (!mapping) throw new Error(`父文档尚未映射: ${outlineDocumentId}`)
  return mapping.linearLiteDocumentId
}

function requireOutlineBinding(document, outlineDocumentId) {
  if (document.externalSource !== 'outline' || document.externalSourceId !== outlineDocumentId) {
    throw new Error(`目标文档的 Outline 映射不一致: ${outlineDocumentId}`)
  }
}

function documentUrl(state, outlineDocumentId, projectId) {
  const id = requireMappedDocumentId(state, outlineDocumentId)
  return `/projects/${projectId}/documents/${id}`
}

function loadState(statePath, projectIdentifier) {
  if (!fs.existsSync(statePath)) {
    return { version: 1, projectIdentifier, documents: {} }
  }
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  if (state.version !== 1 || state.projectIdentifier !== projectIdentifier || !state.documents) {
    throw new Error('迁移状态与当前 manifest 不匹配')
  }
  return state
}

function saveState(statePath, state) {
  const absolutePath = path.resolve(statePath)
  fs.mkdirSync(path.dirname(absolutePath), { recursive: true })
  const temporaryPath = `${absolutePath}.tmp`
  fs.writeFileSync(temporaryPath, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 })
  fs.renameSync(temporaryPath, absolutePath)
}

function splitDestination(destination) {
  if (destination.startsWith('<')) {
    const end = destination.indexOf('>')
    return {
      href: destination.slice(1, end),
      titleSuffix: destination.slice(end + 1),
      angled: true
    }
  }
  const whitespace = destination.search(/\s/)
  if (whitespace < 0) return { href: destination, titleSuffix: '', angled: false }
  return {
    href: destination.slice(0, whitespace),
    titleSuffix: destination.slice(whitespace),
    angled: false
  }
}

function isRelativeFileReference(href) {
  return !href.startsWith('/')
    && !href.startsWith('#')
    && !/^[a-z][a-z0-9+.-]*:/i.test(href)
}

function resolveMarkdownReference(markdownPath, href, exportDir) {
  const cleanHref = decodeURIComponent(href.split('#')[0].split('?')[0])
  return resolveInsideExport(exportDir, path.relative(exportDir, path.resolve(path.dirname(markdownPath), cleanHref)))
}

function resolveInsideExport(exportDir, relativePath) {
  const root = path.resolve(exportDir)
  const resolved = path.resolve(root, relativePath)
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    throw new Error(`路径越出 Outline 导出目录: ${relativePath}`)
  }
  return resolved
}

function normalizeExportPath(value) {
  return String(value).replaceAll('\\', '/').replace(/^\.\/+/, '')
}

function normalizeUrl(value) {
  const parsed = new URL(value)
  parsed.hash = ''
  parsed.search = ''
  return parsed.toString().replace(/\/$/, '')
}

function contentTypeFor(filePath) {
  const extension = path.extname(filePath).toLowerCase()
  const types = {
    '.gif': 'image/gif',
    '.jpeg': 'image/jpeg',
    '.jpg': 'image/jpeg',
    '.pdf': 'application/pdf',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.webp': 'image/webp',
    '.xml': 'application/xml'
  }
  return types[extension] ?? 'application/octet-stream'
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

async function replaceAsync(value, pattern, replacer) {
  const matches = [...value.matchAll(pattern)]
  let cursor = 0
  let output = ''
  for (const match of matches) {
    output += value.slice(cursor, match.index)
    output += await replacer(...match)
    cursor = match.index + match[0].length
  }
  return output + value.slice(cursor)
}

function parseArgs(argv) {
  const values = {}
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--dry-run') {
      values.dryRun = true
      continue
    }
    if (!argument.startsWith('--')) throw new Error(`未知参数: ${argument}`)
    const value = argv[index + 1]
    if (!value || value.startsWith('--')) throw new Error(`参数缺少值: ${argument}`)
    values[argument.slice(2)] = value
    index += 1
  }
  return values
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.manifest) {
    throw new Error('用法: --manifest <JSON> --state <JSON>（旧目录模式另需 --export-dir）')
  }
  const manifest = JSON.parse(fs.readFileSync(path.resolve(args.manifest), 'utf8'))
  const api = args.dryRun && manifest.sourceMode !== 'outline-api' ? null : createHttpApi({
    apiBaseUrl: process.env.API_BASE_URL || DEFAULT_API_BASE_URL,
    token: process.env.JWT,
    identity: process.env.IDENTITY,
    password: process.env.PASSWORD
  })
  const maxAttachmentBytes = Number(
    process.env.DOCUMENT_ATTACHMENT_MAX_BYTES || DEFAULT_MAX_ATTACHMENT_BYTES)
  let result
  if (manifest.sourceMode === 'outline-api') {
    if (args.dryRun) throw new Error('在线模式不支持无校验 dry-run；请对固定3篇执行幂等试迁移')
    const configuredBaseUrl = process.env.OUTLINE_BASE_URL
    if (configuredBaseUrl !== manifest.outlineBaseUrl) {
      throw new Error('OUTLINE_BASE_URL 必须与 manifest.outlineBaseUrl 完全一致')
    }
    const outlineApi = createOutlineApiClient({
      outlineBaseUrl: configuredBaseUrl,
      token: process.env.OUTLINE_API_TOKEN
    })
    result = await migrateOutlineApiDocuments({
      manifest,
      statePath: args.state,
      linearLiteApi: api,
      outlineApi,
      targetOutlineDocumentId: args['document-id'],
      maxAttachmentBytes
    })
  } else {
    if (args['document-id']) throw new Error('--document-id 仅支持 Outline API 在线模式')
    if (!args['export-dir']) throw new Error('旧目录模式必须提供 --export-dir')
    result = await migrateOutlineDocuments({
      exportDir: args['export-dir'],
      manifest,
      statePath: args.state,
      api,
      dryRun: Boolean(args.dryRun),
      maxAttachmentBytes
    })
  }
  console.log(JSON.stringify(result, null, 2))
}

const isMain = process.argv[1] != null
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  main().catch(error => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  })
}
