import { beforeEach, describe, expect, it } from 'vitest'
import { readProjectBoard, writeProjectBoard } from './projectBoardPreferences'
import { DEFAULT_VIEW_CONFIG } from './viewPreference'

const STORAGE_KEY = 'linear-lite-project-board-v1'

function viewCopy() {
  return {
    ...DEFAULT_VIEW_CONFIG,
    visibleProperties: [...DEFAULT_VIEW_CONFIG.visibleProperties]
  }
}

describe('projectBoardPreferences', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('round-trips filterLabelIds', () => {
    writeProjectBoard(1, {
      filters: {
        searchQuery: '',
        filterStatus: null,
        filterPriority: null,
        filterAssignee: null,
        filterAssigneeUsernameNorm: null,
        filterLabelIds: [1, 2]
      },
      view: viewCopy()
    })
    expect(readProjectBoard(1)?.filters.filterLabelIds).toEqual([1, 2])
  })

  it('defaults missing filterLabelIds to empty array', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        v: 1,
        byProject: {
          '1': {
            filters: {
              searchQuery: '',
              filterStatus: null,
              filterPriority: null,
              filterAssignee: null,
              filterAssigneeUsernameNorm: null
            },
            view: DEFAULT_VIEW_CONFIG
          }
        }
      })
    )
    expect(readProjectBoard(1)?.filters.filterLabelIds).toEqual([])
  })

  it('parses only finite numeric label ids', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        v: 1,
        byProject: {
          '1': {
            filters: {
              searchQuery: '',
              filterStatus: null,
              filterPriority: null,
              filterAssignee: null,
              filterAssigneeUsernameNorm: null,
              filterLabelIds: [1, '2', 'bad', null, 3]
            },
            view: DEFAULT_VIEW_CONFIG
          }
        }
      })
    )
    expect(readProjectBoard(1)?.filters.filterLabelIds).toEqual([1, 2, 3])
  })
})
