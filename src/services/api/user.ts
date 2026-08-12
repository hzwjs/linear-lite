import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { User } from '../../types/domain'

interface ApiUser {
  id: number
  username: string
  avatar_url?: string
  principal_type?: 'human' | 'agent'
  agent_key?: string | null
}

function toUser(u: ApiUser): User {
  return {
    id: u.id,
    username: u.username,
    ...(u.avatar_url != null && { avatar_url: u.avatar_url }),
    ...(u.principal_type != null && { principalType: u.principal_type }),
    ...(u.agent_key != null && { agentKey: u.agent_key })
  }
}

export const userApi = {
  list(): Promise<User[]> {
    return api
      .get<ApiResponse<ApiUser[]>>('/users')
      .then((res) => unwrap(res).map(toUser))
  }
}
