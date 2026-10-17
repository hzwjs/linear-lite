<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import BlockNoteEditorWrapper from './BlockNoteEditorWrapper.vue'
import type { BlockNoteCollaboration } from './BlockNoteEditorReact'
import { documentApi } from '../services/api/documents'
import { toApiError } from '../services/api'
import type { DocumentImageAsset, ProjectDocumentAttachment } from '../types/document'
import DocumentAttachmentUploadList from './documents/DocumentAttachmentUploadList.vue'
import { useDocumentAttachmentUploads } from './documents/useDocumentAttachmentUploads'
import { readLocalPastedImage, type PastedImageSource } from './documentImagePaste'

const { t } = useI18n()
const imagePasteLabels = computed(() => ({
  source: t('documents.pastedImageSource'), importing: t('documents.imageImporting'),
}))

const props = withDefaults(
  defineProps<{
    modelValue: string
    documentId: number
    pasteFileAsLink?: boolean
    readonly?: boolean
    placeholder?: string
    mentionMembers?: Array<{ id: number; label: string }>
    mentionDocuments?: Array<{ id: number; title: string; projectId: number }>
    /** 文档图片资源清单：正文图片身份的唯一解析来源。 */
    imageAssets?: DocumentImageAsset[]
    collaboration?: BlockNoteCollaboration
    /** 附件上传失败的占位文本模板，`{name}` 替换为文件名。 */
    fileUploadFailedText?: string
  }>(),
  {
    readonly: false,
    placeholder: '',
    imageAssets: () => [],
    mentionMembers: () => [],
    mentionDocuments: () => []
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: []
  focus: []
  ready: []
}>()

const editorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)
const attachmentUploads = useDocumentAttachmentUploads()
const uploadJobs = attachmentUploads.jobs
// 本次打开期间新上传/复制的图片资源；服务端清单未刷新前先本地补全，避免出现无地址占位。
const sessionAssets = ref<DocumentImageAsset[]>([])

const mergedImageAssets = computed<DocumentImageAsset[]>(() => {
  const byId = new Map<number, DocumentImageAsset>()
  for (const asset of props.imageAssets) byId.set(asset.assetId, asset)
  for (const asset of sessionAssets.value) byId.set(asset.assetId, asset)
  return [...byId.values()]
})

function toDocumentImageAsset(attachment: ProjectDocumentAttachment): DocumentImageAsset {
  return {
    assetId: attachment.id,
    contentHash: attachment.sha256,
    width: attachment.width,
    height: attachment.height,
    thumbnailUrl: attachment.thumbnailUrl,
    originalUrl: `/api/document-assets/${attachment.documentId}/${attachment.id}/${attachment.sha256}/original`
  }
}

async function handleUploadFile(file: File): Promise<string> {
  if (file.type.startsWith('image/')) {
    throw new Error('Document images must be uploaded as image assets')
  }
  // 文档附件必须走文档专属接口，普通图片上传接口不会创建附件元数据。
  const attachment = await attachmentUploads.upload(props.documentId, file)
  if (attachment.contentType?.startsWith('image/')) {
    throw new Error('Document images must use the image asset upload callback')
  }
  return attachment.url
}

async function handleUploadImageAsset(file: File): Promise<DocumentImageAsset> {
  const attachment = await attachmentUploads.upload(props.documentId, file)
  if (!attachment.contentType?.startsWith('image/')) {
    throw new Error('The uploaded document attachment is not an image')
  }
  const asset = toDocumentImageAsset(attachment)
  sessionAssets.value = [...sessionAssets.value, asset]
  return asset
}

async function handleImportPastedImage(source: PastedImageSource, signal: AbortSignal): Promise<DocumentImageAsset> {
  const documentId = props.documentId
  let attachment: ProjectDocumentAttachment
  try {
    if (source.kind === 'asset') {
      attachment = await documentApi.cloneAttachment(documentId, source.assetId, signal)
    } else if (source.kind === 'url' && /^https?:\/\//i.test(source.url)) {
      attachment = await documentApi.importImage(documentId, source.url, signal)
    } else {
      const file = await readLocalPastedImage(source, signal)
      attachment = await documentApi.uploadAttachment(documentId, file, { signal })
    }
  } catch (error) {
    throw toApiError(error)
  }
  if (signal.aborted || props.documentId !== documentId) throw new Error(t('documents.imageImportCanceled'))
  if (attachment.documentId !== documentId || !attachment.contentType?.startsWith('image/')) {
    throw new Error(t('documents.imageImportFailed'))
  }
  const asset = toDocumentImageAsset(attachment)
  sessionAssets.value = [...sessionAssets.value, asset]
  return asset
}

watch(
  () => props.documentId,
  () => { sessionAssets.value = [] }
)

function focus() {
  editorRef.value?.focus()
}

function removeAttachmentLink(href: string): boolean {
  return editorRef.value?.removeAttachmentLink(href) ?? false
}

function getSerializedContent(): string {
  return editorRef.value?.getSerializedContent() ?? '[]'
}

defineExpose({ focus, removeAttachmentLink, getSerializedContent })
</script>

<template>
  <DocumentAttachmentUploadList :jobs="uploadJobs" @retry="attachmentUploads.retry" @cancel="attachmentUploads.cancel" />
  <BlockNoteEditorWrapper
    ref="editorRef"
    class="structured-document-editor"
    :model-value="modelValue"
    :upload-file="handleUploadFile"
    :upload-image-asset="handleUploadImageAsset"
    :import-pasted-image="handleImportPastedImage"
    :image-paste-labels="imagePasteLabels"
    :document-image-menu-label="$t('documents.insertImage')"
    :image-upload-type-unsupported-text="$t('documents.imageFileTypeUnsupported')"
    :document-id="documentId"
    :image-assets="mergedImageAssets"
    :collaboration="collaboration"
    :paste-file-as-link="pasteFileAsLink"
    :file-upload-failed-text="fileUploadFailedText"
    :readonly="readonly"
    :placeholder="placeholder"
    :mention-members="mentionMembers"
    :mention-documents="mentionDocuments"
    :mention-members-group-text="$t('documents.mentionMembersGroup')"
    :mention-documents-group-text="$t('documents.mentionDocumentsGroup')"
    :mention-menu-no-matches-text="$t('documents.mentionNoMatches')"
    :mention-menu-loading-text="$t('common.loading')"
    :min-height="320"
    block-chrome
    @update:model-value="emit('update:modelValue', $event)"
    @blur="emit('blur')"
    @focus="emit('focus')"
    @ready="emit('ready')"
  />
</template>

<style scoped>
.structured-document-editor {
  min-height: 320px;
  background: transparent;
}

.structured-document-editor :deep(.bn-editor) {
  padding-inline: 0 !important;
  font-size: 15px;
  line-height: 1.65;
}

</style>
