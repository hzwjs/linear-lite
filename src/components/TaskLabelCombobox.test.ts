import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TaskLabelCombobox from './TaskLabelCombobox.vue'
import { projectApi } from '../services/api/project'

vi.mock('../services/api/project', () => ({
  projectApi: {
    listLabels: vi.fn()
  }
}))

function flushPromises() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountCombobox(options?: { labels?: { id?: number; name: string }[]; modelValue?: string }) {
  const host = document.createElement('div')
  const sidebar = document.createElement('aside')
  sidebar.className = 'editor-panel'
  document.body.appendChild(sidebar)
  sidebar.appendChild(host)

  const events = {
    updateModelValue: vi.fn(),
    pick: vi.fn(),
    create: vi.fn(),
    remove: vi.fn(),
    deleteDefinition: vi.fn(),
    openChange: vi.fn()
  }

  const app = createApp(TaskLabelCombobox, {
    modelValue: options?.modelValue ?? '',
    labels: options?.labels ?? [{ id: 1, name: '外系统审批' }],
    projectId: 10,
    disabled: false,
    taskId: 'ENG-1',
    placeholder: '添加标签',
    ariaLabel: '添加标签',
    removeLabelAriaLabel: '从任务移除',
    deleteDefinitionAriaLabel: '从项目删除',
    noMatchesText: '没有匹配的标签',
    'onUpdate:modelValue': events.updateModelValue,
    onPick: events.pick,
    onCreate: events.create,
    onRemove: events.remove,
    onDeleteLabelDefinition: events.deleteDefinition,
    onOpenChange: events.openChange
  })
  app.mount(host)
  await nextTick()
  await flushPromises()

  return {
    host,
    sidebar,
    events,
    unmount() {
      app.unmount()
      sidebar.remove()
    }
  }
}

describe('TaskLabelCombobox', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.mocked(projectApi.listLabels).mockResolvedValue([
      { id: 1, name: '外系统审批' },
      { id: 2, name: '运维任务' },
      { id: 3, name: '运维问题' }
    ])
  })

  it('opens the picker from the compact trigger', async () => {
    const view = await mountCombobox()
    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      expect(trigger).toBeTruthy()
      trigger.click()
      await nextTick()
      await flushPromises()

      expect(view.host.querySelector('[role="listbox"]')).toBeTruthy()
      expect(view.host.querySelector('.label-search-input')).toBe(document.activeElement)
    } finally {
      view.unmount()
    }
  })

  it('closes suggestions on outside click', async () => {
    const view = await mountCombobox()
    const outside = document.createElement('button')
    outside.type = 'button'
    outside.textContent = 'outside'
    document.body.appendChild(outside)

    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      outside.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      outside.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.host.querySelector('[role="listbox"]')).toBeNull()
    } finally {
      outside.remove()
      view.unmount()
    }
  })

  it('toggles a suggestion and keeps the picker open for multi-select', async () => {
    const view = await mountCombobox()
    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      const option = Array.from(view.host.querySelectorAll('[role="option"]')).find((el) =>
        el.textContent?.includes('运维任务')
      ) as HTMLElement | undefined
      expect(option).toBeTruthy()
      const mainBtn = option as HTMLButtonElement
      expect(mainBtn).toBeTruthy()

      mainBtn.click()
      await nextTick()
      await flushPromises()

      expect(view.events.pick).toHaveBeenCalledWith({ id: 2, name: '运维任务' })
      expect(projectApi.listLabels).toHaveBeenCalledTimes(1)
      expect(view.host.querySelector('[role="listbox"]')).toBeTruthy()
    } finally {
      view.unmount()
    }
  })

  it('reports the interaction boundary so the editor can batch label saves', async () => {
    const view = await mountCombobox()
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      outside.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await nextTick()

      expect(view.events.openChange.mock.calls.map(([open]) => open)).toEqual([true, false])
    } finally {
      outside.remove()
      view.unmount()
    }
  })

  it('supports keyboard navigation and selection', async () => {
    const view = await mountCombobox({ labels: [] })
    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      const input = view.host.querySelector('.label-search-input') as HTMLInputElement
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.events.pick).toHaveBeenCalledWith({ id: 1, name: '外系统审批' })
      expect(view.host.querySelector('[role="listbox"]')).toBeTruthy()
    } finally {
      view.unmount()
    }
  })

  it('closes suggestions when clicking another control inside the sidebar', async () => {
    const view = await mountCombobox()
    const siblingControl = document.createElement('button')
    siblingControl.type = 'button'
    siblingControl.textContent = 'inside'
    view.sidebar.appendChild(siblingControl)

    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      siblingControl.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      siblingControl.dispatchEvent(new FocusEvent('focusin', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.host.querySelector('[role="listbox"]')).toBeNull()
    } finally {
      siblingControl.remove()
      view.unmount()
    }
  })

  it('emits deleteLabelDefinition when suggestion row delete is clicked', async () => {
    const view = await mountCombobox({ labels: [] })
    try {
      const trigger = view.host.querySelector('.label-trigger') as HTMLButtonElement
      trigger.click()
      await nextTick()
      await flushPromises()

      const del = view.host.querySelector('.label-definition-delete') as HTMLButtonElement
      expect(del).toBeTruthy()
      del.click()
      await nextTick()
      expect(view.events.deleteDefinition).toHaveBeenCalled()
    } finally {
      view.unmount()
    }
  })
})
