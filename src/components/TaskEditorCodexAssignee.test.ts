import { describe, expect, it } from 'vitest'
import taskEditorSource from './TaskEditor.vue?raw'

describe('TaskEditor Codex assignee interaction', () => {
  it('removes manual Codex execution chrome and keeps assignees on the normal member path', () => {
    expect(taskEditorSource).not.toContain('交给 Codex')
    expect(taskEditorSource).not.toContain('codex-run-panel')
    expect(taskEditorSource).not.toContain('dispatchToCodex')
    expect(taskEditorSource).not.toContain('codexApi')
    expect(taskEditorSource).toContain("import AssigneeSelect from './ui/AssigneeSelect.vue'")
    expect(taskEditorSource).toContain(':users="userList"')
  })

  it('silently refreshes an active Codex task so completed progress reaches the open editor', () => {
    expect(taskEditorSource).toContain("assignee?.userType !== 'codex'")
    expect(taskEditorSource).toContain('store.fetchTaskByKey(props.task.id)')
    expect(taskEditorSource).toContain("if (task.status === 'done') void loadComments({ silent: true })")
  })
})
