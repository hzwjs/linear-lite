import { describe, expect, it } from 'vitest'
import { toLabelWriteItem, toLabelWriteItems } from './taskLabelWrite'

describe('taskLabelWrite', () => {
  it('sends positive id for persisted labels', () => {
    expect(toLabelWriteItem({ id: 3, name: 'Bug' })).toEqual({ id: 3 })
  })

  it('sends name for new labels without id', () => {
    expect(toLabelWriteItem({ name: 'New Tag' })).toEqual({ name: 'New Tag' })
  })

  it('falls back to name when optimistic negative id is present', () => {
    expect(toLabelWriteItem({ id: -48291, name: 'New Tag' })).toEqual({ name: 'New Tag' })
  })

  it('maps chip rows and skips empty names', () => {
    expect(
      toLabelWriteItems([
        { id: 1, name: 'Bug' },
        { id: -99, name: 'Fresh' },
        { name: '   ' }
      ])
    ).toEqual([{ id: 1 }, { name: 'Fresh' }])
  })
})
