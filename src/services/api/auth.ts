import { api, unwrap } from './index'
import type { ApiResponse } from './types'
import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  ResetPasswordRequest,
  SendPasswordResetCodeRequest,
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
  },
  sendPasswordResetCode(body: SendPasswordResetCodeRequest) {
    return api.post<ApiResponse<void>>('/auth/password-reset/send-code', body).then(unwrap)
  },
  resetPassword(body: ResetPasswordRequest) {
    return api.post<ApiResponse<void>>('/auth/password-reset', body).then(unwrap)
  },
  /** 清理文档图片资源的路径级 Cookie；失败不阻塞本地登出。 */
  logout() {
    return api.post<ApiResponse<void>>('/auth/logout').then(unwrap)
  },
  /** 为已登录会话补发文档图片资源 Cookie。 */
  startDocumentAssetSession() {
    return api.post<ApiResponse<void>>('/auth/document-asset-session').then(unwrap)
  }
}
