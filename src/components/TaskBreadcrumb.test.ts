import { describe, expect, it } from 'vitest'
import taskEditorSource from './TaskEditor.vue?raw'

describe('task editor breadcrumb navigation', () => {
  it('supports project and parent task navigation in breadcrumb', () => {
    expect(taskEditorSource).toContain('@click="navigateToProject"')
    expect(taskEditorSource).toContain('v-if="parentTask"')
    expect(taskEditorSource).toContain('@click="navigateToParentTask"')
    expect(taskEditorSource).toContain('String(t.numericId) === parentNumericId')
    expect(taskEditorSource).toContain('{{ parentBreadcrumbLabel }}')
    expect(taskEditorSource).toContain('<span class="editor-breadcrumb-current">{{ task?.id }}</span>')
    expect(taskEditorSource).not.toContain('class="editor-parent-link"')
  })
})
