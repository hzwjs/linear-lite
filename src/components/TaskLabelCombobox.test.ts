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
    remove: vi.fn()
  }

  const app = createApp(TaskLabelCombobox, {
    modelValue: options?.modelValue ?? '',
    labels: options?.labels ?? [{ id: 1, name: '外系统审批' }],
    projectId: 10,
    disabled: false,
    sidebarRoot: sidebar,
    taskId: 'ENG-1',
    placeholder: '添加标签',
    ariaLabel: '添加标签',
    removeLabelAriaLabel: '移除标签',
    'onUpdate:modelValue': events.updateModelValue,
    onPick: events.pick,
    onCreate: events.create,
    onRemove: events.remove
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

  it('opens suggestions on focus and keeps them open for combobox interaction', async () => {
    const view = await mountCombobox()
    try {
      const input = view.host.querySelector('input') as HTMLInputElement
      expect(input).toBeTruthy()

      input.focus()
      input.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      expect(view.host.querySelector('[role="listbox"]')).toBeTruthy()

      const editor = view.host.querySelector('.prop-label-editor') as HTMLElement
      expect(editor).toBeTruthy()
      editor.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.host.querySelector('[role="listbox"]')).toBeTruthy()
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
      const input = view.host.querySelector('input') as HTMLInputElement
      input.focus()
      input.dispatchEvent(new FocusEvent('focus'))
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

  it('picks a suggestion on mousedown and closes the list', async () => {
    const view = await mountCombobox()
    try {
      const input = view.host.querySelector('input') as HTMLInputElement
      input.focus()
      input.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      const suggestion = Array.from(view.host.querySelectorAll('[role="option"]')).find((el) =>
        el.textContent?.includes('运维任务')
      ) as HTMLElement | undefined
      expect(suggestion).toBeTruthy()

      suggestion!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.events.pick).toHaveBeenCalledWith({ id: 2, name: '运维任务' })
      expect(view.host.querySelector('[role="listbox"]')).toBeNull()
    } finally {
      view.unmount()
    }
  })

  it('commits first suggestion on Enter when input is empty', async () => {
    const view = await mountCombobox({ labels: [] })
    try {
      const input = view.host.querySelector('input') as HTMLInputElement
      input.focus()
      input.dispatchEvent(new FocusEvent('focus'))
      await nextTick()
      await flushPromises()

      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
      await nextTick()
      await flushPromises()

      expect(view.events.pick).toHaveBeenCalledWith({ id: 1, name: '外系统审批' })
      expect(view.host.querySelector('[role="listbox"]')).toBeNull()
    } finally {
      view.unmount()
    }
  })
})
  it('closes suggestions when clicking another control inside the sidebar', async () => {
    const view = await mountCombobox()
    const siblingControl = document.createElement('button')
    siblingControl.type = 'button'
    siblingControl.textContent = 'inside'
    view.sidebar.appendChild(siblingControl)

    try {
      const input = view.host.querySelector('input') as HTMLInputElement
      input.focus()
      input.dispatchEvent(new FocusEvent('focus'))
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
