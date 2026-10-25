import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import type { AssigneeOption } from '../../types/domain'
import AssigneeSelect from './AssigneeSelect.vue'

const assignees: AssigneeOption[] = [
  { key: 'user:1', name: '李明', principalType: 'human' },
  { key: 'user:2', name: '黄志文', principalType: 'human' },
  { key: 'external:1', name: '李明', principalType: 'external' }
]

async function mountSelect(
  modelValue: string | null = '',
  selectAssignees: AssigneeOption[] = assignees,
  selectedName?: { key: string; name: string }
) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const onUpdate = vi.fn()
  const app = createApp(AssigneeSelect, {
    modelValue,
    assignees: selectAssignees,
    selectedName,
    'onUpdate:modelValue': onUpdate
  })
  app.use(createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: {
      'zh-CN': {
        common: { assignee: '负责人', unassigned: '未分配' },
        assigneeSelect: { searchPlaceholder: '搜索负责人…', noResults: '没有匹配的成员', external: '外部' }
      }
    }
  }))
  app.mount(host)
  await nextTick()
  return { host, app, onUpdate }
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('AssigneeSelect', () => {
  it('does not mount the member search panel until opened and releases it on close', async () => {
    const view = await mountSelect()
    const trigger = view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!
    expect(document.querySelector('.assignee-popover')).toBeNull()

    trigger.click()
    await nextTick()
    expect(document.querySelector('.assignee-popover')).not.toBeNull()
    expect(document.activeElement).toBe(document.querySelector('.assignee-search-input'))

    document.querySelector<HTMLInputElement>('.assignee-search-input')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
    )
    await nextTick()
    expect(document.querySelector('.assignee-popover')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    view.app.unmount()
  })

  it('opens with focused search and filters the member list', async () => {
    const view = await mountSelect()
    view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!.click()
    await nextTick()

    const search = document.querySelector<HTMLInputElement>('.assignee-search-input')!
    expect(document.activeElement).toBe(search)
    search.value = '黄'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    const labels = [...document.querySelectorAll('.assignee-option-label')].map((node) => node.textContent)
    expect(labels).toEqual(['黄志文'])
    view.app.unmount()
  })

  it('selects by keyboard and returns focus to the trigger', async () => {
    const view = await mountSelect()
    const trigger = view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!
    trigger.click()
    await nextTick()

    const search = document.querySelector<HTMLInputElement>('.assignee-search-input')!
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await nextTick()

    expect(view.onUpdate).toHaveBeenCalledWith('user:1')
    expect(document.activeElement).toBe(trigger)
    view.app.unmount()
  })

  it('does not emit when the current assignee is selected again', async () => {
    const view = await mountSelect('user:2')
    view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!.click()
    await nextTick()

    const selected = document.querySelector<HTMLButtonElement>('.assignee-option--selected')!
    selected.click()
    await nextTick()

    expect(view.onUpdate).not.toHaveBeenCalled()
    view.app.unmount()
  })

  it('separates an external person with the same name from a system user', async () => {
    const view = await mountSelect('', [
      ...assignees,
      { key: 'user:3', name: 'Pi', principalType: 'agent' }
    ])
    view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!.click()
    await nextTick()

    const labels = [...document.querySelectorAll('.assignee-option-label')].map((node) => node.textContent)
    expect(labels).toEqual(['未分配', '李明', '黄志文', '李明'])
    const externalBadge = document.querySelector('.assignee-option-type')
    expect(externalBadge?.textContent).toBe('外部')
    view.app.unmount()
  })

  it('uses the DTO name only for the matching canonical key', async () => {
    const view = await mountSelect('external:missing', [], { key: 'external:missing', name: '导入负责人' })
    expect(view.host.querySelector('.assignee-trigger-label')?.textContent).toBe('导入负责人')
    view.app.unmount()
  })

  it('does not use a different key name when the selected option name is empty', async () => {
    const view = await mountSelect('external:missing', [
      { key: 'external:missing', name: '' },
      { key: 'external:other', name: '其他负责人' }
    ], { key: 'external:missing', name: 'DTO 负责人' })
    expect(view.host.querySelector('.assignee-trigger-label')?.textContent).toBe('')
    view.app.unmount()
  })

})
