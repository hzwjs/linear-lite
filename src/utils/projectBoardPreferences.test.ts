import { beforeEach, describe, expect, it } from 'vitest'
import { readProjectBoard, writeProjectBoard } from './projectBoardPreferences'
import { DEFAULT_VIEW_CONFIG } from './viewPreference'

const STORAGE_KEY = 'linear-lite-project-board-v2'

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
        filterStatusList: [],
        filterPriorityList: [],
        filterAssigneeList: [],
        filterLabelIds: [1, 2]
      },
      view: viewCopy()
    })
    expect(readProjectBoard(1)?.filters.filterLabelIds).toEqual([1, 2])
  })

  it('round-trips the list grouping separately from the board grouping', () => {
    const view = {
      ...viewCopy(),
      groupBy: 'priority' as const,
      listGroupBy: 'status' as const
    }
    writeProjectBoard(1, {
      filters: {
        searchQuery: '',
        filterStatusList: [],
        filterPriorityList: [],
        filterAssigneeList: [],
        filterLabelIds: []
      },
      view
    })

    const restored = readProjectBoard(1)?.view
    expect(restored?.groupBy).toBe('priority')
    expect(restored?.listGroupBy).toBe('status')
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
              filterStatusList: [],
              filterPriorityList: [],
              filterAssigneeList: [],
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
              filterStatusList: [],
              filterPriorityList: [],
              filterAssigneeList: [],
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
