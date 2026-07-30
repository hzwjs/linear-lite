import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

export function createAttachmentSpool(directoryPath) {
  const directory = path.resolve(directoryPath)
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 })
  fs.chmodSync(directory, 0o700)

  return {
    filePath(sourceId) {
      return path.join(directory, sha256(sourceId))
    },

    load(tempFile, sourceId, sourceSha256) {
      const metadataPath = `${tempFile}.json`
      if (!fs.existsSync(tempFile) || !fs.existsSync(metadataPath)) return null
      try {
        const metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8'))
        const bytes = fs.readFileSync(tempFile)
        if (metadata.version !== 1
            || metadata.sourceId !== sourceId
            || metadata.sourceSha256 !== sourceSha256
            || metadata.fileSize !== bytes.length
            || metadata.sha256 !== sha256(bytes)) {
          throw new Error('附件缓存校验失败')
        }
        return {
          tempFile,
          fileName: metadata.fileName,
          fileSize: metadata.fileSize,
          sha256: metadata.sha256,
          contentType: metadata.contentType
        }
      } catch {
        // 损坏缓存从唯一 spool 路径删除，随后重新走源端下载门禁。
        fs.rmSync(tempFile, { force: true })
        fs.rmSync(metadataPath, { force: true })
        return null
      }
    },

    save(downloaded, sourceId, sourceSha256) {
      const metadataPath = `${downloaded.tempFile}.json`
      const temporaryPath = `${metadataPath}.tmp`
      const metadata = {
        version: 1,
        sourceId,
        sourceSha256,
        fileName: downloaded.fileName,
        fileSize: downloaded.fileSize,
        sha256: downloaded.sha256,
        contentType: downloaded.contentType
      }
      fs.writeFileSync(temporaryPath, `${JSON.stringify(metadata)}\n`, { mode: 0o600 })
      fs.renameSync(temporaryPath, metadataPath)
    }
  }
}

export function isTransientMigrationError(error) {
  const messages = []
  let current = error
  while (current && !messages.includes(String(current.message ?? current))) {
    messages.push(String(current.message ?? current))
    current = current.cause
  }
  const message = messages.join(' | ')
  return /fetch failed|terminated|ECONNRESET|ECONNREFUSED|EPIPE|ETIMEDOUT|ENETUNREACH|EAI_AGAIN|socket hang up|HTTP (408|425|429|5\d\d)\b/i.test(message)
}

export function isFatalMigrationError(error) {
  return /HTTP (401|403)\b/i.test(String(error?.message ?? error))
}

export async function withTransientRetry(operation, { attempts, delayMs, onRetry }) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await operation()
    } catch (error) {
      if (!isTransientMigrationError(error) || attempt >= attempts) throw error
      const waitMs = delayMs * (2 ** (attempt - 1))
      onRetry({ attempt, nextAttempt: attempt + 1, waitMs })
      if (waitMs > 0) await new Promise(resolve => setTimeout(resolve, waitMs))
    }
  }
}

export function verifyTargetTree({
  projectId,
  manifestDocuments,
  targetDocuments,
  state,
  targetTree,
  requireMappedDocumentId
}) {
  const targetById = new Map(targetTree.map(document => [document.id, document]))
  const mappedByParent = new Map()
  for (const entry of manifestDocuments) {
    if (!state.documents[entry.outlineDocumentId]) continue
    const parentKey = entry.parentOutlineDocumentId ?? '__ROOT__'
    const siblings = mappedByParent.get(parentKey) ?? []
    siblings.push(entry)
    mappedByParent.set(parentKey, siblings)
  }
  for (const siblings of mappedByParent.values()) {
    siblings.sort((left, right) => left.sortOrder - right.sortOrder)
  }

  const verifiedParents = new Set()
  for (const entry of targetDocuments) {
    const documentState = state.documents[entry.outlineDocumentId]
    const actual = targetById.get(documentState.linearLiteDocumentId)
    if (!actual) throw new Error(`目标文档不在活动树中: ${entry.outlineDocumentId}`)
    const expectedParentId = entry.parentOutlineDocumentId == null
      ? null
      : requireMappedDocumentId(state, entry.parentOutlineDocumentId)
    if (actual.projectId !== projectId || actual.parentDocumentId !== expectedParentId) {
      throw new Error(`目标文档层级或顺序与 catalog 不一致: ${entry.outlineDocumentId}`)
    }
    verifiedParents.add(entry.parentOutlineDocumentId ?? '__ROOT__')
  }

  // 用户自建文档不在迁移 state 中；只比较受管文档的相对顺序，不能用绝对 sortOrder 覆盖用户数据。
  for (const parentKey of verifiedParents) {
    const expectedEntries = mappedByParent.get(parentKey)
    const expectedIds = expectedEntries.map(
      entry => state.documents[entry.outlineDocumentId].linearLiteDocumentId)
    const expectedIdSet = new Set(expectedIds)
    const expectedParentId = parentKey === '__ROOT__'
      ? null
      : requireMappedDocumentId(state, parentKey)
    const actualIds = targetTree
      .filter(document => document.projectId === projectId
        && document.parentDocumentId === expectedParentId
        && expectedIdSet.has(document.id))
      .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id)
      .map(document => document.id)
    if (actualIds.length !== expectedIds.length
        || actualIds.some((id, index) => id !== expectedIds[index])) {
      throw new Error(`目标同级文档顺序与 catalog 不一致: ${parentKey}`)
    }
  }
}
