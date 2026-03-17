import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from './i18n'
import slashMenuSource from './components/TiptapSlashMenu.vue?raw'
import datePickerSource from './components/ui/CustomDatePicker.vue?raw'
import customSelectSource from './components/ui/CustomSelect.vue?raw'
import codeBlockLinearViewSource from './components/CodeBlockLinearView.vue?raw'
import taskStoreSource from './store/taskStore.ts?raw'
import { buildTaskImportPreview, parseTaskImportFile } from './utils/taskImport'

describe('shared ui wiring uses i18n helpers', () => {
  it('routes slash menu, date picker, and task store fallbacks through translators', () => {
    expect(slashMenuSource).toContain("const { t } = useI18n()")
    expect(slashMenuSource).toContain("t('editor.slashMenu.heading1')")
    expect(slashMenuSource).toContain(":aria-label=\"t('editor.slashMenu.ariaLabel')\"")

    expect(datePickerSource).toContain("const { t, locale } = useI18n()")
    expect(datePickerSource).toContain("t('datePicker.placeholder')")
    expect(datePickerSource).toContain("t('datePicker.dialogAria')")
    expect(datePickerSource).toContain("t('datePicker.today')")

    expect(customSelectSource).toContain("const { t } = useI18n()")
    expect(customSelectSource).toContain("t('select.placeholder')")
    expect(customSelectSource).toContain("t('select.searchAria')")

    expect(codeBlockLinearViewSource).toContain("const { t } = useI18n()")
    expect(codeBlockLinearViewSource).toContain("t('editor.codeBlock.languageAria')")
    expect(codeBlockLinearViewSource).toContain("t('editor.codeBlock.copyAria')")

    expect(taskStoreSource).toContain("translate('taskStore.errors.loadFailed'")
    expect(taskStoreSource).toContain("translate('taskStore.errors.noProject'")
    expect(taskStoreSource).toContain("translate('taskStore.errors.createFailed'")
    expect(taskStoreSource).toContain("translate('taskStore.errors.updateFailed'")
  })
})

describe('frontend-generated import errors localize across locales', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('returns english preview validation messages by default', () => {
    const preview = buildTaskImportPreview(
      [{ Title: '', 'Import ID': '' }],
      { mapping: { title: 'Title', importId: 'Import ID' }, users: [] }
    )

    expect(preview.rowErrors.map((error) => error.message)).toEqual([
      'Title is required.',
      'Import ID is required.'
    ])
  })

  it('returns localized zh-CN preview validation messages after locale switch', () => {
    i18n.global.locale.value = 'zh-CN'

    const preview = buildTaskImportPreview(
      [{ Title: '', 'Import ID': '' }],
      { mapping: { title: 'Title', importId: 'Import ID' }, users: [] }
    )

    expect(preview.rowErrors.map((error) => error.message)).toEqual([
      '标题不能为空。',
      '导入 ID 不能为空。'
    ])
  })

  it('localizes parse errors as well', async () => {
    i18n.global.locale.value = 'zh-CN'

    await expect(
      parseTaskImportFile(new File(['x'], 'tasks.txt', { type: 'text/plain' }))
    ).rejects.toThrow('仅支持 .csv 和 .xlsx 文件。')
  })
})

describe('shared ui translation keys exist in both locales', () => {
  it('exposes english and chinese copy for shared ui surfaces', () => {
    i18n.global.locale.value = 'en'
    expect(i18n.global.t('editor.slashMenu.heading1')).toBe('Heading 1')
    expect(i18n.global.t('datePicker.today')).toBe('Today')
    expect(i18n.global.t('select.placeholder')).toBe('Select…')
    expect(i18n.global.t('editor.codeBlock.copyAria')).toBe('Copy code')
    expect(i18n.global.t('taskStore.errors.noProject')).toBe('No project selected.')

    i18n.global.locale.value = 'zh-CN'
    expect(i18n.global.t('editor.slashMenu.heading1')).toBe('一级标题')
    expect(i18n.global.t('datePicker.today')).toBe('今天')
    expect(i18n.global.t('select.placeholder')).toBe('选择…')
    expect(i18n.global.t('editor.codeBlock.copyAria')).toBe('复制代码')
    expect(i18n.global.t('taskStore.errors.noProject')).toBe('未选择项目。')
  })
})
