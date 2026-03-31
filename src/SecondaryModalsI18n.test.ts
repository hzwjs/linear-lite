import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from './i18n'
import createProjectModalSource from './components/CreateProjectModal.vue?raw'
import projectSettingsModalSource from './components/ProjectSettingsModal.vue?raw'
import taskImportModalSource from './components/TaskImportModal.vue?raw'
import taskImageNodeViewSource from './components/TaskImageNodeView.vue?raw'
import tiptapEditorSource from './components/TiptapEditor.vue?raw'

describe('secondary modals use i18n helpers', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('routes project modals through translators', () => {
    expect(createProjectModalSource).toContain("const { t } = useI18n()")
    expect(createProjectModalSource).toContain("{{ t('projectModal.title') }}")
    expect(createProjectModalSource).toContain(":placeholder=\"t('projectModal.form.namePlaceholder')\"")
    expect(projectSettingsModalSource).toContain("const { t } = useI18n()")
    expect(projectSettingsModalSource).toContain("{{ t('projectSettingsModal.title') }}")
    expect(projectSettingsModalSource).toContain("{{ t('projectSettingsModal.inviteTitle') }}")
  })

  it('routes the task import modal through translation keys', () => {
    expect(taskImportModalSource).toContain(":aria-label=\"t('taskImportModal.ariaLabel')\"")
    expect(taskImportModalSource).toContain("{{ t('taskImportModal.title') }}")
    expect(taskImportModalSource).toContain("{{ t('taskImportModal.dropzone.title') }}")
    expect(taskImportModalSource).toContain("{{ t('taskImportModal.dropzone.copy') }}")
    expect(taskImportModalSource).toContain("{{ t('taskImportModal.mapping.unmapped') }}")
    expect(taskImportModalSource).toContain("t('taskImportModal.footer.importIssues')")
  })

  it('uses translation helpers in the task image node view', () => {
    expect(taskImageNodeViewSource).toContain("const { t } = useI18n()")
    expect(taskImageNodeViewSource).toContain("{{ isUploading ? t('attachments.uploading') : errorMessage }}")
    expect(taskImageNodeViewSource).toContain("{{ t('common.retry') }}")
    expect(taskImageNodeViewSource).toContain("{{ t('common.remove') }}")
  })

  it('uses translation helpers in the editor fallback copy', () => {
    expect(tiptapEditorSource).toContain("const resolvedPlaceholder = computed(() => props.placeholder || t('editor.placeholder'))")
    expect(tiptapEditorSource).toContain("placeholder: () => resolvedPlaceholder.value")
    expect(tiptapEditorSource).toContain("alt: file.name || t('taskImage.altFallback')")
    expect(tiptapEditorSource).toContain("errorMessage: error instanceof Error ? error.message : t('attachments.uploadFailed')")
  })
})

describe('secondary modal translations exist in catalogs', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('exposes the new keys in both locales', () => {
    expect(i18n.global.t('projectModal.title')).toBe('New project')
    expect(
      i18n.global.t('projectSettingsModal.deleteConfirm', { name: 'Foo' })
    ).toBe('Delete project \"Foo\" and all its tasks? This cannot be undone.')
    expect(i18n.global.t('projectSettingsModal.invitePlaceholder')).toBe('name@example.com')
    expect(i18n.global.t('taskImportModal.dropzone.copy')).toBe(
      'Required template columns: `title`, `importId`. Optional: `parentImportId`, `description`, `status`, `priority`, `assignee`, `dueDate`.'
    )
    expect(i18n.global.t('taskImportModal.footer.importIssues')).toBe('Import issues')
    expect(i18n.global.t('attachments.uploading')).toBe('Uploading...')
    expect(i18n.global.t('taskImage.altFallback')).toBe('image')
    expect(i18n.global.t('editor.placeholder')).toBe('Write something...')
    expect(i18n.global.t('taskImportModal.fields.importId')).toBe('Import ID')

    i18n.global.locale.value = 'zh-CN'

    expect(i18n.global.t('projectModal.title')).toBe('新建项目')
    expect(
      i18n.global.t('projectSettingsModal.deleteConfirm', { name: 'Foo' })
    ).toBe('删除项目“Foo”及其所有任务？该操作无法撤销。')
    expect(i18n.global.t('projectSettingsModal.invitePlaceholder')).toBe('name@example.com')
    expect(i18n.global.t('taskImportModal.dropzone.copy')).toBe(
      '必填模板列：`title`、`importId`。可选：`parentImportId`、`description`、`status`、`priority`、`assignee`、`dueDate`。'
    )
    expect(i18n.global.t('taskImportModal.footer.importIssues')).toBe('导入任务')
    expect(i18n.global.t('attachments.uploading')).toBe('上传中...')
    expect(i18n.global.t('taskImage.altFallback')).toBe('图片')
    expect(i18n.global.t('editor.placeholder')).toBe('输入内容…')
    expect(i18n.global.t('taskImportModal.fields.importId')).toBe('导入 ID')
  })
})
