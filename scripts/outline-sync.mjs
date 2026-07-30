#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  createHttpApi,
  createOutlineApiClient,
  migrateOutlineApiDocuments,
  validateOutlineApiManifest
} from './migrate-outline-documents.mjs'

const DEFAULT_API_BASE_URL = 'http://localhost:5173/api'
const DEFAULT_MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024
const DEFAULT_PREFLIGHT_CONCURRENCY = 3

const DEFAULT_DEPENDENCIES = {
  createLinearLiteApi: createHttpApi,
  createOutlineApi: createOutlineApiClient,
  migrate: migrateOutlineApiDocuments,
  now: () => new Date()
}

export function readPrivateEnvironmentFile(filePath, requiredKeys) {
  const absolutePath = path.resolve(filePath)
  const stat = fs.lstatSync(absolutePath)
  if (!stat.isFile()) throw new Error(`认证文件必须是普通文件: ${absolutePath}`)
  if ((stat.mode & 0o077) !== 0) {
    throw new Error(`认证文件权限必须为 0600: ${absolutePath}`)
  }

  const values = new Map()
  for (const [index, rawLine] of fs.readFileSync(absolutePath, 'utf8').split(/\r?\n/).entries()) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const separator = rawLine.indexOf('=')
    if (separator <= 0) throw new Error(`认证文件第 ${index + 1} 行格式无效: ${absolutePath}`)
    const key = rawLine.slice(0, separator).trim()
    const value = rawLine.slice(separator + 1).trim()
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      throw new Error(`认证文件第 ${index + 1} 行变量名无效: ${absolutePath}`)
    }
    if (values.has(key)) throw new Error(`认证文件包含重复变量 ${key}: ${absolutePath}`)
    values.set(key, value)
  }

  const selected = {}
  for (const key of requiredKeys) {
    const value = values.get(key)
    if (!value) throw new Error(`认证文件缺少 ${key}: ${absolutePath}`)
    selected[key] = value
  }
  return selected
}

function ensurePrivateDirectory(directoryPath) {
  const absolutePath = path.resolve(directoryPath)
  fs.mkdirSync(absolutePath, { recursive: true, mode: 0o700 })
  if (!fs.lstatSync(absolutePath).isDirectory()) {
    throw new Error(`批次运行路径必须是目录: ${absolutePath}`)
  }
  fs.chmodSync(absolutePath, 0o700)
  return absolutePath
}

function validateStateFile(statePath) {
  const absolutePath = path.resolve(statePath)
  if (!fs.existsSync(absolutePath)) return
  const stat = fs.lstatSync(absolutePath)
  if (!stat.isFile()) throw new Error(`迁移 state 必须是普通文件: ${absolutePath}`)
  if ((stat.mode & 0o077) !== 0) throw new Error(`迁移 state 权限必须为 0600: ${absolutePath}`)
}

function writePrivateJson(filePath, value) {
  const absolutePath = path.resolve(filePath)
  const temporaryPath = `${absolutePath}.tmp`
  fs.writeFileSync(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 })
  fs.renameSync(temporaryPath, absolutePath)
}

function createEventJournal(filePath, runId, now) {
  if (fs.existsSync(filePath)) fs.chmodSync(filePath, 0o600)
  return (event, details = {}) => {
    const record = {
      timestamp: now().toISOString(),
      runId,
      event,
      ...details
    }
    fs.appendFileSync(filePath, `${JSON.stringify(record)}\n`, { mode: 0o600 })
    fs.chmodSync(filePath, 0o600)
  }
}

function acquireStateLock(statePath, runId, now) {
  const absoluteStatePath = path.resolve(statePath)
  fs.mkdirSync(path.dirname(absoluteStatePath), { recursive: true })
  const lockPath = `${absoluteStatePath}.lock`
  let descriptor
  try {
    descriptor = fs.openSync(lockPath, 'wx', 0o600)
    fs.writeFileSync(descriptor, `${JSON.stringify({ runId, pid: process.pid, startedAt: now().toISOString() })}\n`)
  } catch (error) {
    if (error?.code === 'EEXIST') throw new Error(`迁移 state 已被其他批次锁定: ${lockPath}`)
    throw error
  } finally {
    if (descriptor != null) fs.closeSync(descriptor)
  }
  return () => fs.rmSync(lockPath, { force: true })
}

function assertIdempotent(firstPass, verificationPass) {
  if (verificationPass.documents !== firstPass.documents
      || verificationPass.migratedDocuments !== firstPass.migratedDocuments
      || verificationPass.blockedDocuments !== firstPass.blockedDocuments
      || verificationPass.createdDocuments !== 0
      || verificationPass.updatedDocuments !== 0
      || verificationPass.uploadedAttachments !== 0) {
    throw new Error('幂等复跑失败：必须保持相同批次范围且 created/updated/uploaded 全部为 0')
  }
}

function defaultRunDirectory(projectIdentifier, subtreeRootOutlineDocumentId, now) {
  const timestamp = now().toISOString().replaceAll(':', '-').replace(/\.\d{3}Z$/, 'Z')
  return path.resolve(
    'docs/migrations/runs',
    `${timestamp}-${projectIdentifier}-${subtreeRootOutlineDocumentId}`)
}

export async function runOutlineSync(options, dependencies = {}) {
  const deps = { ...DEFAULT_DEPENDENCIES, ...dependencies }
  const manifestPath = path.resolve(options.catalogPath)
  const manifest = validateOutlineApiManifest(
    JSON.parse(fs.readFileSync(manifestPath, 'utf8')))
  validateStateFile(options.statePath)
  const runDirectory = ensurePrivateDirectory(options.runDirectory
    ?? defaultRunDirectory(manifest.projectIdentifier, options.subtreeRootOutlineDocumentId, deps.now))
  const runId = path.basename(runDirectory)
  const resultPath = path.join(runDirectory, 'result.json')
  const blockedReportPath = path.join(runDirectory, 'blocked.md')
  const journalPath = path.join(runDirectory, 'events.ndjson')
  const journal = createEventJournal(journalPath, runId, deps.now)
  const releaseStateLock = acquireStateLock(options.statePath, runId, deps.now)

  try {
    // 凭据只从权限 0600 的显式文件读取，绝不从进程环境或其他字段回退。
    const outlineAuth = readPrivateEnvironmentFile(
      options.outlineAuthFile, ['OUTLINE_API_TOKEN'])
    const targetAuth = readPrivateEnvironmentFile(options.targetAuthFile, ['JWT'])
    const linearLiteApi = deps.createLinearLiteApi({
      apiBaseUrl: options.apiBaseUrl ?? DEFAULT_API_BASE_URL,
      token: targetAuth.JWT
    })
    const outlineApi = deps.createOutlineApi({
      outlineBaseUrl: manifest.outlineBaseUrl,
      token: outlineAuth.OUTLINE_API_TOKEN
    })
    const migrationOptions = {
      manifest,
      statePath: path.resolve(options.statePath),
      blockedReportPath,
      linearLiteApi,
      outlineApi,
      subtreeRootOutlineDocumentId: options.subtreeRootOutlineDocumentId,
      maxAttachmentBytes: options.maxAttachmentBytes ?? DEFAULT_MAX_ATTACHMENT_BYTES,
      preflightConcurrency: options.preflightConcurrency ?? DEFAULT_PREFLIGHT_CONCURRENCY
    }

    journal('batch_started', {
      projectIdentifier: manifest.projectIdentifier,
      subtreeRootOutlineDocumentId: options.subtreeRootOutlineDocumentId,
      catalogPath: manifestPath
    })
    const runPass = pass => deps.migrate({
      ...migrationOptions,
      onProgress: (event, details) => journal('migration_progress', {
        pass,
        migrationEvent: event,
        details
      })
    })
    const firstPass = await runPass('apply')
    journal('apply_completed', { result: firstPass })

    // 同一进程、同一参数立即复跑，避免人工遗漏幂等验收。
    const verificationPass = await runPass('verify')
    assertIdempotent(firstPass, verificationPass)
    journal('idempotency_verified', { result: verificationPass })

    const result = {
      version: 1,
      status: firstPass.blockedDocuments === 0 ? 'completed' : 'completed_with_blocked',
      runId,
      projectIdentifier: manifest.projectIdentifier,
      subtreeRootOutlineDocumentId: options.subtreeRootOutlineDocumentId,
      firstPass,
      verificationPass,
      artifacts: {
        blockedReportPath,
        journalPath,
        resultPath
      }
    }
    writePrivateJson(resultPath, result)
    journal('batch_completed', { status: result.status })
    return result
  } catch (error) {
    const failed = {
      version: 1,
      status: 'failed',
      runId,
      projectIdentifier: manifest.projectIdentifier,
      subtreeRootOutlineDocumentId: options.subtreeRootOutlineDocumentId,
      error: error instanceof Error ? error.message : String(error)
    }
    writePrivateJson(resultPath, failed)
    journal('batch_failed', { error: failed.error })
    throw error
  } finally {
    releaseStateLock()
  }
}

export function parseCliArgs(argv) {
  if (argv[0] !== 'run') throw new Error(usage())
  const allowed = new Set([
    'api-base-url',
    'catalog',
    'max-attachment-bytes',
    'outline-auth-file',
    'preflight-concurrency',
    'run-dir',
    'state',
    'subtree-root-id',
    'target-auth-file'
  ])
  const values = {}
  for (let index = 1; index < argv.length; index += 2) {
    const argument = argv[index]
    const value = argv[index + 1]
    if (!argument?.startsWith('--') || value == null || value.startsWith('--')) {
      throw new Error(`参数格式无效: ${argument ?? ''}\n\n${usage()}`)
    }
    const key = argument.slice(2)
    if (!allowed.has(key)) throw new Error(`未知参数: ${argument}`)
    if (values[key] != null) throw new Error(`重复参数: ${argument}`)
    values[key] = value
  }
  for (const key of ['catalog', 'state', 'subtree-root-id', 'outline-auth-file', 'target-auth-file']) {
    if (!values[key]) throw new Error(`缺少参数: --${key}`)
  }
  const preflightConcurrency = parsePositiveInteger(
    values['preflight-concurrency'], DEFAULT_PREFLIGHT_CONCURRENCY, '--preflight-concurrency')
  const maxAttachmentBytes = parsePositiveInteger(
    values['max-attachment-bytes'], DEFAULT_MAX_ATTACHMENT_BYTES, '--max-attachment-bytes')
  return {
    catalogPath: values.catalog,
    statePath: values.state,
    subtreeRootOutlineDocumentId: values['subtree-root-id'],
    outlineAuthFile: values['outline-auth-file'],
    targetAuthFile: values['target-auth-file'],
    runDirectory: values['run-dir'],
    apiBaseUrl: values['api-base-url'],
    preflightConcurrency,
    maxAttachmentBytes
  }
}

function parsePositiveInteger(value, defaultValue, optionName) {
  if (value == null) return defaultValue
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new Error(`${optionName} 必须是正整数`)
  }
  return parsed
}

function usage() {
  return [
    '用法: outline-sync run',
    '  --catalog <JSON> --subtree-root-id <ID> --state <JSON>',
    '  --outline-auth-file <0600 ENV> --target-auth-file <0600 ENV>',
    '  [--run-dir <DIR>] [--api-base-url <URL>]',
    '  [--preflight-concurrency <N>] [--max-attachment-bytes <N>]'
  ].join('\n')
}

const isMain = process.argv[1] != null
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isMain) {
  runOutlineSync(parseCliArgs(process.argv.slice(2)))
    .then(result => console.log(JSON.stringify(result, null, 2)))
    .catch(error => {
      console.error(error instanceof Error ? error.message : String(error))
      process.exitCode = 1
    })
}
