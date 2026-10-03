import axios, { isAxiosError } from 'axios'
import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { ProjectDocumentAttachment } from '../../types/document'

export type DocumentUploadStage = 'preparing' | 'uploading' | 'retrying' | 'processing'
export interface DocumentUploadProgress { stage: DocumentUploadStage; loaded: number; total: number }
export interface DocumentUploadOptions {
  sessionToken?: string
  signal?: AbortSignal
  onSession?: (sessionToken: string) => void
  onProgress?: (progress: DocumentUploadProgress) => void
}
interface UploadSession {
  sessionToken: string
  partSize: number
  status: 'uploading' | 'uploaded' | 'processing' | 'completed'
  parts: Array<{ partNumber: number; size: number }>
  attachment: ProjectDocumentAttachment | null
}

export function uploadCanceled(): DOMException { return new DOMException('Upload canceled', 'AbortError') }

function checkCanceled(signal?: AbortSignal) {
  if (signal?.aborted) throw uploadCanceled()
}

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  checkCanceled(signal)
  return new Promise((resolve, reject) => {
    const cancel = () => { clearTimeout(timer); reject(uploadCanceled()) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', cancel); resolve() }, ms)
    signal?.addEventListener('abort', cancel, { once: true })
  })
}

function retryable(error: unknown): boolean {
  if (!isAxiosError(error) || axios.isCancel(error)) return false
  const status = error.response?.status
  return status == null || status === 408 || status === 429 || status >= 500
}

export async function abortDocumentAttachmentUpload(documentId: number, sessionToken: string): Promise<void> {
  await api.delete(`/project-documents/${documentId}/attachment-uploads`, { data: { sessionToken }, timeout: 15_000 })
}

export async function uploadDocumentAttachment(
  documentId: number, file: File, options: DocumentUploadOptions = {},
): Promise<ProjectDocumentAttachment> {
  const endpoint = `/project-documents/${documentId}/attachment-uploads`
  const request = { signal: options.signal, timeout: 15_000 }
  const progress = (stage: DocumentUploadStage, loaded: number) => options.onProgress?.({ stage, loaded, total: file.size })
  progress('preparing', 0)
  checkCanceled(options.signal)
  let session: UploadSession
  try {
    session = options.sessionToken == null
      ? unwrap(await api.post<ApiResponse<UploadSession>>(endpoint, {
          fileName: file.name, fileSize: file.size, contentType: file.type,
        }, request))
      : unwrap(await api.post<ApiResponse<UploadSession>>(`${endpoint}/status`, { sessionToken: options.sessionToken }, request))
    options.onSession?.(session.sessionToken)
    if (!Number.isSafeInteger(session.partSize) || session.partSize <= 0) throw new Error('Invalid upload part size')
    const body = { sessionToken: session.sessionToken }

    if (session.status === 'uploading') {
      const count = Math.ceil(file.size / session.partSize)
      const loadedByPart = new Map<number, number>()
      const done = new Set<number>()
      for (const part of session.parts) {
        const expected = Math.min(session.partSize, file.size - (part.partNumber - 1) * session.partSize)
        if (part.partNumber < 1 || part.partNumber > count || part.size !== expected) {
          throw new Error('Uploaded part does not match this file')
        }
        done.add(part.partNumber)
        loadedByPart.set(part.partNumber, part.size)
      }
      const report = (stage: DocumentUploadStage) => progress(stage, [...loadedByPart.values()].reduce((sum, size) => sum + size, 0))
      report('uploading')
      const queue = Array.from({ length: count }, (_, index) => index + 1).filter(number => !done.has(number))
      const attempt = new AbortController()
      const cancelAttempt = () => attempt.abort()
      options.signal?.addEventListener('abort', cancelAttempt, { once: true })
      let firstFailure: unknown
      const worker = async () => {
        while (queue.length > 0 && !attempt.signal.aborted) {
          const number = queue.shift()!
          const start = (number - 1) * session.partSize
          const chunk = file.slice(start, Math.min(start + session.partSize, file.size))
          for (let retry = 0; retry < 3; retry++) {
            try {
              checkCanceled(attempt.signal)
              const form = new FormData()
              form.append('sessionToken', session.sessionToken)
              form.append('file', chunk, file.name)
              await api.put(`${endpoint}/parts/${number}`, form, {
                timeout: 180_000,
                signal: attempt.signal,
                headers: { 'Content-Type': 'multipart/form-data' },
                onUploadProgress: event => {
                  const ratio = event.total != null && event.total > 0 ? event.loaded / event.total : 0
                  loadedByPart.set(number, Math.min(chunk.size, Math.floor(chunk.size * ratio)))
                  report('uploading')
                },
              })
              loadedByPart.set(number, chunk.size)
              report('uploading')
              break
            } catch (error) {
              loadedByPart.set(number, 0)
              if (retry === 2 || !retryable(error) || attempt.signal.aborted) throw error
              report('retrying')
              await wait(1000 * 2 ** retry, attempt.signal)
            }
          }
        }
      }
      try {
        await Promise.all(Array.from({ length: Math.min(3, queue.length) }, async () => {
          try { await worker() }
          catch (error) {
            if (firstFailure == null) firstFailure = error
            attempt.abort()
            throw error
          }
        }).map(promise => promise.catch(() => undefined)))
        if (firstFailure != null) throw firstFailure
        checkCanceled(options.signal)
      } finally {
        options.signal?.removeEventListener('abort', cancelAttempt)
      }
    }

    progress('processing', file.size)
    if (session.status !== 'completed') {
      session = unwrap(await api.post<ApiResponse<UploadSession>>(`${endpoint}/complete`, body, request))
    }
    while (session.status !== 'completed') {
      await wait(1000, options.signal)
      session = unwrap(await api.post<ApiResponse<UploadSession>>(`${endpoint}/status`, body, request))
      // 服务进程重启后，R2 中的已传文件仍可发起确认；轮询不能只等待内存任务恢复。
      if (session.status === 'uploaded' || session.status === 'uploading') {
        session = unwrap(await api.post<ApiResponse<UploadSession>>(`${endpoint}/complete`, body, request))
      }
    }
    if (session.attachment == null) throw new Error('Upload confirmation is missing attachment metadata')
    return session.attachment
  } catch (error) {
    if (options.signal?.aborted || axios.isCancel(error)) throw uploadCanceled()
    throw error
  }
}
