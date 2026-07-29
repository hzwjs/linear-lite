import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { ProjectContentSearchResult } from '../../types/search'

export const searchApi = {
  search(query: string): Promise<ProjectContentSearchResult[]> {
    return api
      // Embedding + Qdrant 是远程调用，避免搜索弹窗无限等待。
      .get<ApiResponse<ProjectContentSearchResult[]>>('/search', { params: { query }, timeout: 10000 })
      .then((response) => unwrap(response))
  }
}
