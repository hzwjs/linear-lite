import { api, toApiError, unwrap } from './index'
import type { ApiResponse } from './types'

export interface RtCa {
  id: number
  status: 'active' | 'retiring' | 'retired'
  publicKey: string
  fingerprint: string
  createdAt: string
}

export interface RtProfile {
  id: number
  profileId: string
  loginUser: string
  authType: 'CERTIFICATE' | 'PASSWORD'
  hasPassword: boolean
  maxTtlSeconds: number
  permitPty: boolean
  grantUserIds: number[]
}

export interface RtTarget {
  id: number
  targetId: string
  alias: string
  environment: string
  host: string
  port: number
  hostKey: string
  hostKeyFingerprint: string
  hostKeyRev: number
  profiles: RtProfile[]
}

export interface RtOverview {
  /** 服务端是否配置了 RT_CA_KEK；为 false 时不能创建 CA。 */
  enabled: boolean
  projectIdentifier: string
  cas: RtCa[]
  targets: RtTarget[]
}

export interface RtTargetInput {
  targetId: string
  alias: string
  environment: string
  host: string
  port: number
  hostKey: string
}

export interface RtProfileInput {
  profileId: string
  loginUser: string
  authType: 'CERTIFICATE' | 'PASSWORD'
  password: string | null
  maxTtlSeconds: number
  permitPty: boolean
}

export interface RtCertificateRecord {
  serial: number
  userId: number
  targetId: string
  profileId: string
  principal: string
  keyFingerprint: string
  validAfter: string
  validBefore: string
  clientIp: string
  createdAt: string
}

export interface RtAuditRecord {
  id: number
  actorId: number
  action: string
  subject: string
  beforeJson: string | null
  afterJson: string | null
  createdAt: string
}

const base = (projectId: number) => `/projects/${projectId}/remote-terminal`


/** 后端错误文案（403/409/422）原样展示给项目创建者。 */
function call<T>(request: Promise<T>): Promise<T> {
  return request.catch((e: unknown) => { throw toApiError(e) })
}

function asArray<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : []
}

export const remoteTerminalApi = {
  overview(projectId: number): Promise<RtOverview> {
    return call(api.get<ApiResponse<RtOverview>>(base(projectId)).then((res) => {
      const data = unwrap(res)
      return { ...data, cas: asArray(data.cas), targets: asArray(data.targets).map((t) => ({ ...t, profiles: asArray(t.profiles) })) }
    }))
  },
  createCa(projectId: number): Promise<RtCa> {
    return call(api.post<ApiResponse<RtCa>>(`${base(projectId)}/ca`).then(unwrap))
  },
  rotateCa(projectId: number): Promise<RtCa> {
    return call(api.post<ApiResponse<RtCa>>(`${base(projectId)}/ca/rotate`).then(unwrap))
  },
  retireCa(projectId: number, caId: number): Promise<void> {
    return call(api.post<ApiResponse<null>>(`${base(projectId)}/ca/${caId}/retire`).then((res) => { unwrap(res) }))
  },
  createTarget(projectId: number, input: RtTargetInput): Promise<RtTarget> {
    return call(api.post<ApiResponse<RtTarget>>(`${base(projectId)}/targets`, input).then(unwrap))
  },
  updateTarget(projectId: number, targetRef: number, input: RtTargetInput): Promise<RtTarget> {
    return call(api.put<ApiResponse<RtTarget>>(`${base(projectId)}/targets/${targetRef}`, input).then(unwrap))
  },
  deleteTarget(projectId: number, targetRef: number): Promise<void> {
    return call(api.delete<ApiResponse<null>>(`${base(projectId)}/targets/${targetRef}`).then((res) => { unwrap(res) }))
  },
  /** 接入脚本是纯文本，不走 ApiResponse 包装。 */
  accessScript(projectId: number, targetRef: number): Promise<string> {
    return call(api
      .get<string>(`${base(projectId)}/targets/${targetRef}/access-script`, { responseType: 'text', transformResponse: (data) => data })
      .then((res) => res.data))
  },
  createProfile(projectId: number, targetRef: number, input: RtProfileInput): Promise<RtProfile> {
    return call(api.post<ApiResponse<RtProfile>>(`${base(projectId)}/targets/${targetRef}/profiles`, input).then(unwrap))
  },
  updateProfile(projectId: number, profileRef: number, input: RtProfileInput): Promise<RtProfile> {
    return call(api.put<ApiResponse<RtProfile>>(`${base(projectId)}/profiles/${profileRef}`, input).then(unwrap))
  },
  deleteProfile(projectId: number, profileRef: number): Promise<void> {
    return call(api.delete<ApiResponse<null>>(`${base(projectId)}/profiles/${profileRef}`).then((res) => { unwrap(res) }))
  },
  setGrants(projectId: number, profileRef: number, userIds: number[]): Promise<number[]> {
    return call(api.put<ApiResponse<number[]>>(`${base(projectId)}/profiles/${profileRef}/grants`, { userIds }).then((res) => asArray(unwrap(res))))
  },
  certificates(projectId: number, limit = 50): Promise<RtCertificateRecord[]> {
    return call(api.get<ApiResponse<RtCertificateRecord[]>>(`${base(projectId)}/certificates`, { params: { limit } }).then((res) => asArray(unwrap(res))))
  },
  auditEvents(projectId: number, limit = 50): Promise<RtAuditRecord[]> {
    return call(api.get<ApiResponse<RtAuditRecord[]>>(`${base(projectId)}/audit`, { params: { limit } }).then((res) => asArray(unwrap(res))))
  },
}
