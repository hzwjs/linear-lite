import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from './i18n'
import boardViewSource from './views/BoardView.vue?raw'
import issueComposerSource from './components/IssueComposer.vue?raw'
import taskEditorSource from './components/TaskEditor.vue?raw'
import taskListViewSource from './components/TaskListView.vue?raw'
import commandPaletteSource from './components/CommandPalette.vue?raw'

describe('workspace i18n wiring', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('routes workspace components through translation helpers', () => {
    expect(boardViewSource).toContain("const { t } = useI18n()")
    expect(boardViewSource).toContain("{{ t('boardView.newIssue') }}")
    expect(issueComposerSource).toContain("const { t } = useI18n()")
    expect(issueComposerSource).toContain(":placeholder=\"t('issueComposer.issueTitlePlaceholder')\"")
    expect(taskEditorSource).toContain("const { t } = useI18n()")
    expect(taskEditorSource).toContain(":placeholder=\"t('taskEditor.issueTitlePlaceholder')\"")
    expect(taskListViewSource).toContain("const { t } = useI18n()")
    expect(taskListViewSource).toContain(":aria-label=\"t('taskList.createIssueInGroup')\"")
    expect(commandPaletteSource).toContain("const { t } = useI18n()")
    expect(commandPaletteSource).toContain("{{ t('commandPalette.noMatches') }}")
  })

  it('exposes translated workspace copy', () => {
    expect(i18n.global.t('boardView.newIssue')).toBe('New issue')
    expect(i18n.global.t('issueComposer.createIssue')).toBe('Create issue')
    expect(i18n.global.t('taskEditor.attachments')).toBe('Attachments')

    i18n.global.locale.value = 'zh-CN'

    expect(i18n.global.t('boardView.newIssue')).toBe('新建任务')
    expect(i18n.global.t('issueComposer.createIssue')).toBe('创建任务')
    expect(i18n.global.t('taskEditor.attachments')).toBe('附件')
  })
})
