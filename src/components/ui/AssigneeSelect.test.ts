import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import type { User } from '../../types/domain'
import AssigneeSelect from './AssigneeSelect.vue'

const users: User[] = [
  { id: 1, username: '李明' },
  { id: 2, username: '黄志文' }
]

async function mountSelect(modelValue: string | number = '') {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const onUpdate = vi.fn()
  const app = createApp(AssigneeSelect, {
    modelValue,
    users,
    'onUpdate:modelValue': onUpdate
  })
  app.use(createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: {
      'zh-CN': {
        common: { assignee: '负责人', unassigned: '未分配' },
        assigneeSelect: { searchPlaceholder: '搜索负责人…', noResults: '没有匹配的成员' }
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

    expect(view.onUpdate).toHaveBeenCalledWith(1)
    expect(document.activeElement).toBe(trigger)
    view.app.unmount()
  })

  it('does not emit when the current assignee is selected again', async () => {
    const view = await mountSelect(2)
    view.host.querySelector<HTMLButtonElement>('.assignee-trigger')!.click()
    await nextTick()

    const selected = document.querySelector<HTMLButtonElement>('.assignee-option--selected')!
    selected.click()
    await nextTick()

    expect(view.onUpdate).not.toHaveBeenCalled()
    view.app.unmount()
  })
})
