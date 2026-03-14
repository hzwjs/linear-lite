import { describe, expect, it } from 'vitest'
import { getSubtaskProgressDisplay } from './subtaskProgress'

describe('getSubtaskProgressDisplay', () => {
  it('hides the pill when there are no subtasks', () => {
    expect(getSubtaskProgressDisplay()).toEqual({
      visible: false,
      countText: '',
      progress: 0,
      completed: false
    })
  })

  it('returns count text and normalized progress for active subtasks', () => {
    expect(getSubtaskProgressDisplay(1, 4)).toEqual({
      visible: true,
      countText: '1/4',
      progress: 0.25,
      completed: false
    })
  })

  it('clamps progress and marks the pill as completed when all subtasks are done', () => {
    expect(getSubtaskProgressDisplay(5, 3)).toEqual({
      visible: true,
      countText: '5/3',
      progress: 1,
      completed: true
    })
  })

  it('treats negative completed counts as zero progress', () => {
    expect(getSubtaskProgressDisplay(-2, 3)).toEqual({
      visible: true,
      countText: '-2/3',
      progress: 0,
      completed: false
    })
  })
})
