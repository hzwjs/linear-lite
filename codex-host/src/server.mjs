import http from 'node:http'
import { HostError, validateProjectDirectory } from './device.mjs'

export const HOST_PORT = 9781

/** 只提供本机身份与目录核验；执行写入接口禁止复用此 Origin 信任边界。 */
export function createHostServer({ identity, allowedOrigin, port = HOST_PORT }) {
  const origin = new URL(allowedOrigin)
  if (!['http:', 'https:'].includes(origin.protocol) || origin.origin !== allowedOrigin)
    throw new Error('宿主来源须为单个HTTP或HTTPS origin')
  return http.createServer(async (request, response) => {
    const send = (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
      response.end(JSON.stringify(body))
    }
    try {
      const expectedPort = port === 0 ? request.socket.localPort : port
      if (request.headers.host !== `127.0.0.1:${expectedPort}` || request.headers.origin !== allowedOrigin)
        throw new HostError(403, 'ORIGIN_DENIED', '宿主请求来源无效')
      response.setHeader('Access-Control-Allow-Origin', allowedOrigin)
      response.setHeader('Vary', 'Origin')
      if (request.method === 'OPTIONS') {
        const method = request.headers['access-control-request-method']
        const headers = (request.headers['access-control-request-headers'] ?? '').toLowerCase().split(',').map(x => x.trim()).filter(Boolean)
        if (!['GET', 'POST'].includes(method) || headers.some(value => value !== 'content-type'))
          throw new HostError(403, 'PREFLIGHT_DENIED', '宿主预检请求无效')
        response.setHeader('Access-Control-Allow-Methods', 'GET, POST')
        response.setHeader('Access-Control-Allow-Headers', 'Content-Type')
        if (request.headers['access-control-request-private-network'] === 'true')
          response.setHeader('Access-Control-Allow-Private-Network', 'true')
        response.writeHead(204); response.end(); return
      }
      if (request.method === 'GET' && request.url === '/identity') { send(200, identity); return }
      if (request.method !== 'POST' || request.url !== '/validate-directory')
        throw new HostError(404, 'ROUTE_NOT_FOUND', '宿主操作不存在')
      if (request.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json')
        throw new HostError(415, 'CONTENT_TYPE_INVALID', '宿主请求须为JSON')
      const chunks = []; let bytes = 0
      for await (const chunk of request) {
        bytes += chunk.length
        if (bytes > 16384) throw new HostError(413, 'BODY_TOO_LARGE', '宿主请求过大')
        chunks.push(chunk)
      }
      let input
      try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { throw new HostError(400, 'PARAMETER_INVALID', '目录参数JSON无效') }
      send(200, await validateProjectDirectory(identity, input))
    } catch (error) {
      const expected = error instanceof HostError
      send(expected ? error.status : 500, {
        code: expected ? error.code : 'HOST_ERROR', message: expected ? error.message : '宿主请求失败',
      })
    }
  })
}
