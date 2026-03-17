import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import { getPriorityLabel, getStatusLabel } from './enumLabels'

describe('enum label helpers', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('returns the English labels by default', () => {
    expect(getStatusLabel('in_progress')).toBe('In Progress')
    expect(getPriorityLabel('urgent')).toBe('Urgent')
  })

  it('returns Chinese labels after switching locale', () => {
    i18n.global.locale.value = 'zh-CN'
    expect(getStatusLabel('in_progress')).toBe('进行中')
    expect(getPriorityLabel('low')).toBe('低')
  })

  it('falls back to the raw value when there is no translation', () => {
    expect(getStatusLabel('not-a-status')).toBe('not-a-status')
  })
})
