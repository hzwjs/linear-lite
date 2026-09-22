import { AxiosError } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { agentApi, type AgentTaskStatus } from './agent'
import { api, toApiError } from './index'

describe('local Pi turn request', () => {
  afterEach(() => vi.restoreAllMocks())

  it('limits a turn submission so the interface cannot wait forever', async () => {
    const status: AgentTaskStatus = {
      executionId: 'execution-1',
      jobId: 9,
      sessionStatus: 'running',
      jobStatus: 'queued',
      sourceType: 'turn',
      errorMessage: null,
      updatedAt: null,
      hasSubmittedTurn: true,
    }
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { code: 200, data: status } })

    await expect(agentApi.submitTurn('EASYGO-1', 'execution-1', 'request-1', '查询天气')).resolves.toEqual(status)
    expect(post).toHaveBeenCalledWith('/tasks/EASYGO-1/local-pi/turns', {
      executionId: 'execution-1',
      idempotencyKey: 'request-1',
      prompt: '查询天气',
    }, { timeout: 15000 })
  })

  it('turns an Axios timeout into a retry instruction', () => {
    expect(toApiError(new AxiosError('timeout', 'ECONNABORTED')).message).toBe('请求超时，请检查网络后重试')
  })
})
