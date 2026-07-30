#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  createHttpApi,
  createOutlineApiClient,
  validateOutlineApiManifest
} from './migrate-outline-documents.mjs'
import { readPrivateEnvironmentFile } from './outline-sync.mjs'
import { withTransientRetry } from './outline-sync-session.mjs'

const DEFAULT_API_BASE_URL = 'http://localhost:5173/api'
const DEFAULT_CONCURRENCY = 3

function readPrivateJson(filePath, label) {
  const absolutePath = path.resolve(filePath)
  const stat = fs.lstatSync(absolutePath)
  if (!stat.isFile()) throw new Error(`${label} 必须是普通文件: ${absolutePath}`)
  if ((stat.mode & 0o077) !== 0) throw new Error(`${label} 权限必须为 0600: ${absolutePath}`)
  return JSON.parse(fs.readFileSync(absolutePath, 'utf8'))
}

function writePrivateJson(filePath, value) {
  const absolutePath = path.resolve(filePath)
  fs.mkdirSync(path.dirname(absolutePath), { recursive: true, mode: 0o700 })
  const temporaryPath = `${absolutePath}.tmp`
  fs.writeFileSync(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 })
  fs.renameSync(temporaryPath, absolutePath)
  fs.chmodSync(absolutePath, 0o600)
}

function validateSubtreeSnapshot(snapshot) {
  if (!snapshot || snapshot.version !== 1 || snapshot.sourceMode !== 'outline-api') {
    throw new Error('子树快照必须设置 version=1、sourceMode=outline-api')
  }
  if (!snapshot.projectIdentifier || !snapshot.outlineBaseUrl) {
    throw new Error('子树快照缺少 projectIdentifier 或 outlineBaseUrl')
  }
  if (!Array.isArray(snapshot.documents) || snapshot.documents.length === 0) {
    throw new Error('子树快照 documents 不能为空')
  }
  const ids = new Set()
  for (const entry of snapshot.documents) {
    if (!entry.outlineDocumentId || ids.has(entry.outlineDocumentId)) {
      throw new Error(`子树快照 Outline ID 为空或重复: ${entry.outlineDocumentId ?? ''}`)
    }
    if (!Number.isInteger(entry.sortOrder) || entry.sortOrder < 0) {
      throw new Error(`子树快照 sortOrder 非法: ${entry.outlineDocumentId}`)
    }
    ids.add(entry.outlineDocumentId)
  }
  return snapshot
}

export function compileOutlineSyncCatalog({ snapshot, state, targetTree, sourceDocuments }) {
  validateSubtreeSnapshot(snapshot)
  if (state.version !== 1 || state.projectIdentifier !== snapshot.projectIdentifier
      || !state.documents || typeof state.documents !== 'object') {
    throw new Error('迁移 state 与子树快照项目不一致')
  }

  const snapshotById = new Map(snapshot.documents.map(entry => [entry.outlineDocumentId, entry]))
  const allIds = new Set([...Object.keys(state.documents), ...snapshotById.keys()])
  const targetById = new Map(targetTree.map(document => [document.id, document]))
  const outlineIdByTargetId = new Map(Object.entries(state.documents).map(
    ([outlineDocumentId, documentState]) => [documentState.linearLiteDocumentId, outlineDocumentId]))
  const entries = []

  for (const outlineDocumentId of allIds) {
    const source = sourceDocuments.get(outlineDocumentId)
    if (!source || source.outlineDocumentId !== outlineDocumentId) {
      throw new Error(`缺少精确 Outline 元数据: ${outlineDocumentId}`)
    }
    const documentState = state.documents[outlineDocumentId]
    if (documentState) {
      const target = targetById.get(documentState.linearLiteDocumentId)
      if (!target) throw new Error(`state 文档不在目标活动树中: ${outlineDocumentId}`)
      const parentOutlineDocumentId = target.parentDocumentId == null
        ? null
        : outlineIdByTargetId.get(target.parentDocumentId)
      if (target.parentDocumentId != null && !parentOutlineDocumentId) {
        throw new Error(`受管文档不能挂在未映射目标父级下: ${outlineDocumentId}`)
      }
      entries.push({
        outlineDocumentId,
        title: source.title,
        parentOutlineDocumentId,
        sourceUrl: source.sourceUrl,
        mapped: true,
        sourceOrder: target.sortOrder,
        targetId: target.id
      })
      continue
    }

    const snapshotEntry = snapshotById.get(outlineDocumentId)
    if (!snapshotEntry) throw new Error(`新节点不在子树快照中: ${outlineDocumentId}`)
    if (snapshotEntry.parentOutlineDocumentId != null
        && !allIds.has(snapshotEntry.parentOutlineDocumentId)) {
      throw new Error(`新节点父级不在 state 或快照中: ${outlineDocumentId}`)
    }
    entries.push({
      outlineDocumentId,
      title: source.title,
      parentOutlineDocumentId: snapshotEntry.parentOutlineDocumentId,
      sourceUrl: source.sourceUrl,
      mapped: false,
      sourceOrder: snapshotEntry.sortOrder,
      targetId: Number.MAX_SAFE_INTEGER
    })
  }

  const siblingsByParent = new Map()
  for (const entry of entries) {
    const parentKey = entry.parentOutlineDocumentId ?? '__ROOT__'
    const siblings = siblingsByParent.get(parentKey) ?? []
    siblings.push(entry)
    siblingsByParent.set(parentKey, siblings)
  }
  for (const siblings of siblingsByParent.values()) {
    // 已映射节点保持用户已验收的目标布局；新节点按快照顺序追加，禁止隐式移动历史数据。
    siblings.sort((left, right) => Number(right.mapped) - Number(left.mapped)
      || left.sourceOrder - right.sourceOrder
      || left.targetId - right.targetId
      || left.outlineDocumentId.localeCompare(right.outlineDocumentId))
    siblings.forEach((entry, sortOrder) => { entry.sortOrder = sortOrder })
  }

  const ordered = []
  const emitted = new Set()
  while (ordered.length < entries.length) {
    const before = ordered.length
    for (const entry of entries) {
      if (emitted.has(entry.outlineDocumentId)) continue
      if (entry.parentOutlineDocumentId != null && !emitted.has(entry.parentOutlineDocumentId)) continue
      ordered.push(entry)
      emitted.add(entry.outlineDocumentId)
    }
    if (ordered.length === before) throw new Error('编译后的 catalog 存在父级循环')
  }

  return validateOutlineApiManifest({
    version: 1,
    sourceMode: 'outline-api',
    outlineBaseUrl: snapshot.outlineBaseUrl,
    projectIdentifier: snapshot.projectIdentifier,
    documents: ordered.map(({ mapped: _mapped, sourceOrder: _sourceOrder, targetId: _targetId, ...entry }) => entry)
  })
}

async function mapWithConcurrency(items, concurrency, worker) {
  const results = new Array(items.length)
  let cursor = 0
  async function run() {
    while (cursor < items.length) {
      const index = cursor
      cursor += 1
      results[index] = await worker(items[index])
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, run))
  return results
}

export async function runCatalogCompiler(options, dependencies = {}) {
  const snapshot = validateSubtreeSnapshot(readPrivateJson(options.snapshotPath, '子树快照'))
  const state = readPrivateJson(options.statePath, '迁移 state')
  const outlineAuth = readPrivateEnvironmentFile(options.outlineAuthFile, ['OUTLINE_API_TOKEN'])
  const targetAuth = readPrivateEnvironmentFile(options.targetAuthFile, ['JWT'])
  const outlineApi = (dependencies.createOutlineApi ?? createOutlineApiClient)({
    outlineBaseUrl: snapshot.outlineBaseUrl,
    token: outlineAuth.OUTLINE_API_TOKEN
  })
  const targetApi = (dependencies.createTargetApi ?? createHttpApi)({
    apiBaseUrl: options.apiBaseUrl ?? DEFAULT_API_BASE_URL,
    token: targetAuth.JWT
  })
  const retry = operation => withTransientRetry(operation, {
    attempts: 3,
    delayMs: 250,
    onRetry: () => {}
  })
  const projects = await retry(() => targetApi.listProjects())
  const matches = projects.filter(project => project.identifier === snapshot.projectIdentifier)
  if (matches.length !== 1) throw new Error(`目标项目必须唯一: ${snapshot.projectIdentifier}`)
  const targetTree = await retry(() => targetApi.listDocumentTree(matches[0].id))
  const ids = [...new Set([...Object.keys(state.documents), ...snapshot.documents.map(
    entry => entry.outlineDocumentId)])]
  const sourceList = await mapWithConcurrency(
    ids,
    options.concurrency ?? DEFAULT_CONCURRENCY,
    outlineDocumentId => retry(() => outlineApi.getDocument(outlineDocumentId)))
  const catalog = compileOutlineSyncCatalog({
    snapshot,
    state,
    targetTree,
    sourceDocuments: new Map(sourceList.map(document => [document.outlineDocumentId, document]))
  })
  writePrivateJson(options.outputPath, catalog)
  return { outputPath: path.resolve(options.outputPath), documents: catalog.documents.length }
}

function parseArgs(argv) {
  const allowed = new Set([
    'api-base-url',
    'outline-auth-file',
    'output',
    'snapshot',
    'state',
    'target-auth-file'
  ])
  const values = {}
  for (let index = 0; index < argv.length; index += 2) {
    const argument = argv[index]
    const value = argv[index + 1]
    if (!argument?.startsWith('--') || value == null) throw new Error('catalog 编译参数格式无效')
    const key = argument.slice(2)
    if (!allowed.has(key)) throw new Error(`未知参数: ${argument}`)
    if (values[key] != null) throw new Error(`重复参数: ${argument}`)
    values[key] = value
  }
  for (const key of ['snapshot', 'state', 'outline-auth-file', 'target-auth-file', 'output']) {
    if (!values[key]) throw new Error(`缺少参数: --${key}`)
  }
  return {
    snapshotPath: values.snapshot,
    statePath: values.state,
    outlineAuthFile: values['outline-auth-file'],
    targetAuthFile: values['target-auth-file'],
    outputPath: values.output,
    apiBaseUrl: values['api-base-url']
  }
}

const isMain = process.argv[1] != null
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  runCatalogCompiler(parseArgs(process.argv.slice(2)))
    .then(result => console.log(JSON.stringify(result, null, 2)))
    .catch(error => {
      console.error(error instanceof Error ? error.message : String(error))
      process.exitCode = 1
    })
}
