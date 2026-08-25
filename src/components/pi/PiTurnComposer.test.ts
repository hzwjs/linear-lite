import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PiTurnComposer from './PiTurnComposer.vue'
import type { PiSettingsState } from '../../services/api/agent'

const settings: PiSettingsState = {
  executionId: 'exec-1',
  current: { model: { provider: 'anthropic', modelId: 'sonnet', label: 'Claude Sonnet' }, thinkingLevel: 'high' },
  models: [
    { provider: 'anthropic', modelId: 'sonnet', label: 'Claude Sonnet' },
    { provider: 'openai', modelId: 'gpt-5', label: 'GPT-5' },
  ],
  thinkingLevels: ['low', 'high'],
}

const unmounts: Array<() => void> = []
afterEach(() => { while (unmounts.length) unmounts.pop()?.() })

function mountComposer(overrides: Partial<InstanceType<typeof PiTurnComposer>['$props']> = {}) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(PiTurnComposer, {
    modelValue: '', active: false, canSubmit: true, submitting: false, canceling: false,
    settings, settingsLoading: false, settingsEnabled: true, ...overrides,
  })
  app.mount(host)
  unmounts.push(() => { app.unmount(); host.remove() })
  return host
}

describe('PiTurnComposer settings menu', () => {
  it('opens existing settings without issuing another read request', async () => {
    const readSettings = vi.fn()
    const host = mountComposer({ onReadSettings: readSettings })
    host.querySelector<HTMLButtonElement>('.pi-settings-trigger')?.click()
    await nextTick()
    expect(host.querySelector('.pi-settings-menu')).not.toBeNull()
    expect(readSettings).not.toHaveBeenCalled()
  })

  it('requests settings only when the composer has no current Pi state', async () => {
    const readSettings = vi.fn()
    const host = mountComposer({ settings: null, onReadSettings: readSettings })
    host.querySelector<HTMLButtonElement>('.pi-settings-trigger')?.click()
    await nextTick()
    expect(readSettings).toHaveBeenCalledTimes(1)
  })

  it('closes the menu immediately after model or thinking selection', async () => {
    const setModel = vi.fn()
    const setThinkingLevel = vi.fn()
    const host = mountComposer({ onSetModel: setModel, onSetThinkingLevel: setThinkingLevel })
    const trigger = host.querySelector<HTMLButtonElement>('.pi-settings-trigger')!
    trigger.click()
    await nextTick()
    host.querySelectorAll<HTMLButtonElement>('.pi-settings-menu__row')[0].click()
    await nextTick()
    host.querySelectorAll<HTMLButtonElement>('.pi-settings-menu__option')[1].click()
    await nextTick()
    expect(setModel).toHaveBeenCalledWith(settings.models[1])
    expect(host.querySelector('.pi-settings-menu')).toBeNull()

    trigger.click()
    await nextTick()
    host.querySelectorAll<HTMLButtonElement>('.pi-settings-menu__row')[1].click()
    await nextTick()
    host.querySelectorAll<HTMLButtonElement>('.pi-settings-menu__option')[0].click()
    await nextTick()
    expect(setThinkingLevel).toHaveBeenCalledWith('low')
    expect(host.querySelector('.pi-settings-menu')).toBeNull()
  })
})
