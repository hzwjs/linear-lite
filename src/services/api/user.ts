import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { User } from '../../types/domain'

interface ApiUser {
  id: number
  username: string
  avatar_url?: string
}

function toUser(u: ApiUser): User {
  return {
    id: u.id,
    username: u.username,
    ...(u.avatar_url != null && { avatar_url: u.avatar_url })
  }
}

export const userApi = {
  list(): Promise<User[]> {
    return api
      .get<ApiResponse<ApiUser[]>>('/users')
      .then((res) => unwrap(res).map(toUser))
  }
}
