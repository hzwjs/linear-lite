import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type { LoginRequest, LoginResponse } from './types'

export const authApi = {
  login(body: LoginRequest) {
    return api.post<ApiResponse<LoginResponse>>('/auth/login', body).then(unwrap)
  }
}
