import { createApp, h, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import type { Task } from '../types/domain'
import { taskApi } from '../services/api/task'
import TaskCardPreview from './TaskCardPreview.vue'

const task: Task = { id: 'ENG-1', title: '任务标题', status: 'in_progress', priority: 'high', progressPercent: 36, description: '**详情内容**', createdAt: 0, updatedAt: 0 }

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('TaskCardPreview', () => {
  it('等待 400 毫秒后读取详情，滚动时关闭', async () => {
    vi.useFakeTimers()
    const get = vi.spyOn(taskApi, 'get').mockResolvedValue(task)
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({ render: () => h(TaskCardPreview, { task }, () => h('div', '卡片')) })
    app.use(i18n).mount(host)
    const trigger = host.firstElementChild!
    trigger.dispatchEvent(new MouseEvent('mouseenter'))
    await vi.advanceTimersByTimeAsync(399)
    expect(get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await nextTick()
    expect(get).toHaveBeenCalledWith('ENG-1')
    expect(document.querySelector('.task-preview')?.textContent).toContain('详情内容')
    window.dispatchEvent(new Event('scroll'))
    await nextTick()
    expect(document.querySelector('.task-preview')).toBeNull()
    app.unmount()
  })

  it('离开卡片后请求返回不会打开预览', async () => {
    vi.useFakeTimers()
    let resolve!: (value: Task) => void
    vi.spyOn(taskApi, 'get').mockReturnValue(new Promise((done) => { resolve = done }))
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({ render: () => h(TaskCardPreview, { task }, () => h('div', '卡片')) })
    app.use(i18n).mount(host)
    const trigger = host.firstElementChild!
    trigger.dispatchEvent(new MouseEvent('mouseenter'))
    await vi.advanceTimersByTimeAsync(400)
    trigger.dispatchEvent(new MouseEvent('mouseleave'))
    await vi.advanceTimersByTimeAsync(150)
    resolve(task)
    await nextTick()
    await nextTick()
    expect(document.querySelector('.task-preview')).toBeNull()
    app.unmount()
  })

  it('移入预览时保持显示，进入另一张卡片时关闭', async () => {
    vi.useFakeTimers()
    vi.spyOn(taskApi, 'get').mockResolvedValue(task)
    const host = document.createElement('div')
    document.body.append(host)
    const app = createApp({ render: () => h('div', [
      h(TaskCardPreview, { task }, () => h('div', '第一张')),
      h(TaskCardPreview, { task: { ...task, id: 'ENG-2' } }, () => h('div', '第二张'))
    ]) })
    app.use(i18n).mount(host)
    const [first, second] = Array.from(host.firstElementChild!.children)
    first!.dispatchEvent(new MouseEvent('mouseenter'))
    await vi.advanceTimersByTimeAsync(400)
    first!.dispatchEvent(new MouseEvent('mouseleave'))
    document.querySelector('.task-preview')!.dispatchEvent(new MouseEvent('mouseenter'))
    await vi.advanceTimersByTimeAsync(150)
    expect(document.querySelector('.task-preview')).not.toBeNull()
    second!.dispatchEvent(new MouseEvent('mouseenter'))
    await nextTick()
    expect(document.querySelector('.task-preview')).toBeNull()
    app.unmount()
  })
})
