import { onBeforeUnmount, reactive, ref } from 'vue'
import { documentApi } from '../../services/api/documents'
import { toApiError } from '../../services/api'
import {
  abortDocumentAttachmentUpload, uploadCanceled,
  type DocumentUploadProgress, type DocumentUploadStage,
} from '../../services/api/documentAttachmentUpload'
import type { ProjectDocumentAttachment } from '../../types/document'
import { randomClientId } from '../../utils/clientId'

export interface DocumentAttachmentUploadJob {
  id: string
  documentId: number
  file: File
  state: DocumentUploadStage | 'failed'
  loaded: number
  error: string
  sessionToken?: string
  controller: AbortController
  settled: boolean
  resolve: (attachment: ProjectDocumentAttachment) => void
  reject: (error: unknown) => void
}

export function useDocumentAttachmentUploads() {
  const jobs = ref<DocumentAttachmentUploadJob[]>([])
  let disposed = false
  const remove = (job: DocumentAttachmentUploadJob) => {
    jobs.value = jobs.value.filter(item => item.id !== job.id)
  }

  async function run(job: DocumentAttachmentUploadJob) {
    job.state = 'preparing'
    job.error = ''
    job.controller = new AbortController()
    try {
      const attachment = await documentApi.uploadAttachment(job.documentId, job.file, {
        sessionToken: job.sessionToken,
        signal: job.controller.signal,
        onSession: token => { job.sessionToken = token },
        onProgress: (progress: DocumentUploadProgress) => {
          job.state = progress.stage
          job.loaded = progress.loaded
        },
      })
      if (disposed) {
        await documentApi.deleteAttachment(attachment.documentId, attachment.id)
        job.reject(uploadCanceled())
      } else {
        job.resolve(attachment)
      }
      job.settled = true
      remove(job)
    } catch (error) {
      if (job.settled) return
      job.state = 'failed'
      job.error = toApiError(error).message
      // File 和会话保留在内存；用户重试时用 R2 的分片清单恢复传输。
      if (disposed) cancel(job)
    }
  }

  function upload(documentId: number, file: File): Promise<ProjectDocumentAttachment> {
    return new Promise((resolve, reject) => {
      const job = reactive<DocumentAttachmentUploadJob>({
        id: randomClientId(), documentId, file, state: 'preparing', loaded: 0, error: '',
        controller: new AbortController(), settled: false, resolve, reject,
      })
      jobs.value.push(job)
      void run(job)
    })
  }

  function retry(job: DocumentAttachmentUploadJob) {
    if (job.state === 'failed' && !job.settled) void run(job)
  }

  function cancel(job: DocumentAttachmentUploadJob) {
    if (job.settled || job.state === 'processing') return
    job.settled = true
    job.controller.abort()
    if (job.sessionToken != null) {
      void abortDocumentAttachmentUpload(job.documentId, job.sessionToken).catch(() => {
        // 取消请求遇到断网时，服务端过期清理负责终止遗留分片。
      })
    }
    job.reject(uploadCanceled())
    remove(job)
  }

  onBeforeUnmount(() => {
    disposed = true
    for (const job of jobs.value) {
      // 确认阶段不能中断数据库写入；完成后删除未插入正文的附件。
      if (job.state !== 'processing') cancel(job)
    }
  })
  return { jobs, upload, retry, cancel }
}
