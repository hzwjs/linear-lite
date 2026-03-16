import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  SendRegisterCodeRequest
} from './types'

export const authApi = {
  login(body: LoginRequest) {
    return api.post<ApiResponse<LoginResponse>>('/auth/login', body).then(unwrap)
  },
  sendRegisterCode(body: SendRegisterCodeRequest) {
    return api.post<ApiResponse<void>>('/auth/register/send-code', body).then(unwrap)
  },
  register(body: RegisterRequest) {
    return api.post<ApiResponse<LoginResponse>>('/auth/register', body).then(unwrap)
  }
}
