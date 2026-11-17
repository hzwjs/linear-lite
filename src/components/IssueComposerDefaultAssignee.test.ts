import { createApp, defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AssigneeOption, Task } from '../types/domain'
import { i18n } from '../i18n'
import IssueComposer from './IssueComposer.vue'
import { useAssigneeStore } from '../store/assigneeStore'
import { useAuthStore } from '../store/authStore'
import { useProjectStore } from '../store/projectStore'
import { useTaskStore } from '../store/taskStore'

vi.mock('../services/api/auth', () => ({
  authApi: {
    startDocumentAssetSession: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined)
  }
}))

vi.mock('./BlockNoteEditorWrapper.vue', () => ({
  default: defineComponent({
    name: 'BlockNoteEditorStub',
    props: { modelValue: { type: String, default: '' } },
    emits: ['upload-state-change'],
    mounted() {
      this.$emit('upload-state-change', { hasPending: false, hasFailed: false })
    },
    template: '<textarea class="description-stub" :value="modelValue" />'
  })
}))

vi.mock('./ui/CustomSelect.vue', () => ({
  default: defineComponent({ name: 'CustomSelectStub', template: '<button type="button" class="custom-select-stub" />' })
}))

vi.mock('./ui/CustomDatePicker.vue', () => ({
  default: defineComponent({ name: 'CustomDatePickerStub', template: '<button type="button" class="custom-date-stub" />' })
}))

vi.mock('./TaskLabelCombobox.vue', () => ({
  default: defineComponent({
    name: 'TaskLabelComboboxStub',
    props: { labels: { type: Array, default: () => [] } },
    template: '<button type="button" class="label-stub" />'
  })
}))

const SELF_ID = 7
const assignees: AssigneeOption[] = [
  { key: 'user:8', name: '李明', principalType: 'human' },
  { key: `user:${SELF_ID}`, name: '黄志文', principalType: 'human' }
]

function tokenFor(id: number) {
  return `header.${btoa(JSON.stringify({ sub: String(id) }))}.signature`
}

let app: ReturnType<typeof createApp> | null = null

async function mountComposer() {
  localStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setSession(tokenFor(SELF_ID), SELF_ID, '黄志文')
  useProjectStore().setActiveProject(1)
  useAssigneeStore().optionsByProject = { 1: assignees }
  const created: Parameters<ReturnType<typeof useTaskStore>['createTask']>[0][] = []
  const taskStore = useTaskStore()
  vi.spyOn(taskStore, 'createTask').mockImplementation(async (data) => {
    created.push(data)
    return { id: 'LL-1', numericId: 1 } as Task
  })

  const host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(IssueComposer, { open: true })
  app.use(pinia)
  app.use(i18n)
  app.mount(host)
  await nextTick()
  return { created }
}

function triggerLabel(): string | null {
  return document.body.querySelector('.composer-property--assignee .assignee-trigger-label')?.textContent ?? null
}

async function submit(title: string) {
  const titleInput = document.body.querySelector<HTMLInputElement>('.composer-title-input')!
  titleInput.value = title
  titleInput.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  document.body.querySelector<HTMLButtonElement>('.composer-submit')!.click()
  await nextTick()
  await nextTick()
}

beforeEach(() => {
  document.body.innerHTML = ''
})

afterEach(() => {
  app?.unmount()
  app = null
  document.body.innerHTML = ''
})

describe('新建任务面板的默认负责人', () => {
  it('打开面板时负责人已选中当前操作用户，并随任务一起提交', async () => {
    const { created } = await mountComposer()

    expect(triggerLabel()).toBe('黄志文')

    await submit('写周报')

    expect(created).toHaveLength(1)
    expect(created[0]).toMatchObject({ title: '写周报', assigneeKey: `user:${SELF_ID}` })
  })

  it('手动改成未分配后按未分配提交', async () => {
    const { created } = await mountComposer()

    document.body.querySelector<HTMLButtonElement>('.composer-property--assignee .assignee-trigger')!.click()
    await nextTick()
    const unassignedOption = document.querySelectorAll<HTMLButtonElement>('.assignee-popover .assignee-option')[0]!
    unassignedOption.click()
    await nextTick()
    expect(triggerLabel()).toBe('未分配')

    await submit('写周报')

    expect(created[0]).toMatchObject({ assigneeKey: null })
  })
})
