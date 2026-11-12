export const HOST_PROTOCOL_VERSION = 3
export const HOST_PROTOCOL_HEADER = 'X-Linear-Lite-Host-Protocol'
export const HOST_API_PREFIX = `/api/collaboration-host/v${HOST_PROTOCOL_VERSION}`
export const HOST_PROTOCOL_MISMATCH = '宿主与服务器的协作协议不匹配'

export function hostProtocolMismatch(response) {
  const supported = response.headers.get(HOST_PROTOCOL_HEADER) === String(HOST_PROTOCOL_VERSION)
  return response.status === 426 || (!supported && (response.ok || response.status === 404))
}
