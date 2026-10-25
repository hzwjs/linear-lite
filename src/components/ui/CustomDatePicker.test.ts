import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import CustomDatePicker from './CustomDatePicker.vue'

describe('CustomDatePicker', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('mounts the calendar only while open and releases it after Escape', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const onUpdate = vi.fn()
    const app = createApp(CustomDatePicker, {
      modelValue: '',
      'onUpdate:modelValue': onUpdate
    })
    app.use(i18n)
    app.mount(host)

    try {
      const trigger = host.querySelector<HTMLButtonElement>('.custom-date-picker-trigger')!
      expect(document.querySelector('.custom-date-picker-panel')).toBeNull()

      trigger.click()
      await nextTick()
      expect(document.querySelector('.custom-date-picker-panel')).not.toBeNull()
      expect(document.querySelectorAll('.custom-date-picker-panel .calendar-day:not(.pad)').length).toBeGreaterThan(27)

      document.querySelector<HTMLElement>('.custom-date-picker-panel')!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
      )
      await nextTick()
      expect(document.querySelector('.custom-date-picker-panel')).toBeNull()
      expect(document.activeElement).toBe(trigger)
      expect(onUpdate).not.toHaveBeenCalled()
    } finally {
      app.unmount()
    }
  })
})
