import { lookup } from 'node:dns'
import { request } from 'node:https'
import { BlockList, isIP } from 'node:net'
import { setTimeout as delay } from 'node:timers/promises'

const maxImages = 4
const maxImageBytes = 10 * 1024 * 1024
const maxTotalBytes = 12 * 1024 * 1024
const requestTimeoutMs = 12_000
const totalTimeoutMs = 24_000
const formatError = '图片格式不受支持或文件内容损坏'
const sizeError = '图片超过大小限制'
const imageTypes = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
const transientNetworkErrors = new Set(['ECONNRESET', 'ECONNREFUSED', 'EPIPE', 'EAI_AGAIN', 'ENETUNREACH',
  'EHOSTUNREACH', 'ERR_STREAM_PREMATURE_CLOSE'])

class ImageReadError extends Error {
  constructor(message, retryable = false) {
    super(message)
    this.retryable = retryable
  }
}

function downloadFailure(error) {
  if (error instanceof ImageReadError) return error
  if (error?.name === 'TimeoutError' || error?.code === 'ABORT_ERR' || error?.code === 'ETIMEDOUT')
    return new ImageReadError('图片下载超时', true)
  if (transientNetworkErrors.has(error?.code)) return new ImageReadError('图片下载连接中断', true)
  if (error?.code === 'ENOTFOUND') return new ImageReadError('图片域名无法解析')
  return new ImageReadError('图片下载连接失败')
}

const privateV4 = new BlockList()
const proxyV4 = new BlockList()
proxyV4.addSubnet('198.18.0.0', 15)
for (const [address, bits] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16],
  ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 3],
]) privateV4.addSubnet(address, bits)
const globalV6 = new BlockList()
globalV6.addSubnet('2000::', 3, 'ipv6')
const specialV6 = new BlockList()
for (const [address, bits] of [['2001::', 23], ['2001:db8::', 32], ['2002::', 16], ['3fff::', 20]])
  specialV6.addSubnet(address, bits, 'ipv6')

function isPublicAddress(address) {
  const family = isIP(address)
  if (family === 4) return !privateV4.check(address)
  return family === 6 && !address.includes('%') && globalV6.check(address, 'ipv6') && !specialV6.check(address, 'ipv6')
}

function hasImageSignature(mime, bytes) {
  if (mime === 'image/png') return bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  if (mime === 'image/jpeg') return bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
  if (mime === 'image/gif') return ['GIF87a', 'GIF89a'].includes(bytes.toString('ascii', 0, 6))
  return mime === 'image/webp' && bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP'
}

function downloadImage(src, limit, signal) {
  return new Promise((resolve, reject) => {
    if (!URL.canParse(src)) throw new ImageReadError('图片地址无效')
    const url = new URL(src)
    if (url.protocol !== 'https:' || url.port || url.username || url.password || isIP(url.hostname) || url.hostname.startsWith('['))
      throw new ImageReadError('图片地址必须是公网 HTTPS')
    // 本机代理可能为 R2 公共桶返回 Fake-IP。仅允许标准上传地址使用这段地址。
    const publicR2Upload = /^pub-[a-f0-9]{32}\.r2\.dev$/.test(url.hostname)
      && /^\/task-images\/\d{4}\/\d{2}\/[a-f0-9-]{36}-[A-Za-z0-9._-]+$/.test(url.pathname)
    // 校验 TLS 保持使用原域名。连接只使用此处已核验的 DNS 地址，不再重新解析或跟随跳转。
    const req = request(url, { agent: false, signal, autoSelectFamily: false,
      headers: { Accept: 'image/png,image/jpeg,image/webp,image/gif' },
      lookup(host, options, done) {
        lookup(host, { all: true }, (error, entries) => {
          if (error) return done(error)
          if (!entries.length || entries.some(entry => !isPublicAddress(entry.address)
              && !(publicR2Upload && entry.family === 4 && proxyV4.check(entry.address))))
            return done(new ImageReadError('图片域名解析到非公网地址'))
          const entry = entries[0]
          return options.all ? done(null, [entry]) : done(null, entry.address, entry.family)
        })
      },
    }, response => {
      const mime = response.headers['content-type']?.split(';')[0].trim().toLowerCase()
      const status = response.statusCode
      const error = status !== 200 ? new ImageReadError(`图片服务器返回 HTTP ${status}`, status === 408 || status >= 500)
        : !imageTypes.has(mime) ? new ImageReadError(formatError)
        : Number(response.headers['content-length']) > limit ? new ImageReadError(sizeError) : null
      if (error) {
        response.destroy()
        reject(error)
        return
      }
      const chunks = []
      let bytes = 0
      response.on('data', chunk => {
        bytes += chunk.length
        if (bytes > limit) {
          reject(new ImageReadError(sizeError))
          response.destroy()
        }
        else chunks.push(chunk)
      })
      response.once('error', reject)
      response.once('end', () => bytes ? resolve({ mime, bytes: Buffer.concat(chunks, bytes) }) : reject(new ImageReadError('图片为空')))
    })
    req.once('error', reject)
    req.end()
  })
}

async function readWithRetry(download, src, limit, budget) {
  const read = () => {
    budget.throwIfAborted()
    return download(src, limit, AbortSignal.any([budget, AbortSignal.timeout(requestTimeoutMs)]))
  }
  try {
    return await read()
  } catch (error) {
    const failure = downloadFailure(error)
    if (!failure.retryable || budget.aborted) throw failure
    await delay(300, undefined, { signal: budget })
    return read()
  }
}

/** 仅从已领取任务的 BlockNote 描述解析图片，Agent 无法指定下载地址。 */
export async function taskDescriptionImages(description, download = downloadImage) {
  let blocks
  try { blocks = JSON.parse(description) } catch { return [] }
  if (!Array.isArray(blocks)) return []
  const images = []
  function visit(items) {
    for (const block of items) {
      if (block?.type === 'image' && typeof block.props?.url === 'string' && block.props.url) images.push(block)
      if (Array.isArray(block?.children)) visit(block.children)
    }
  }
  visit(blocks)
  if (!images.length) return []
  const budget = AbortSignal.timeout(totalTimeoutMs)
  let remaining = maxTotalBytes
  const results = []
  for (let i = 0; i < images.length; i++) {
    if (i >= maxImages) {
      results.push({ index: i + 1, error: `本次最多读取 ${maxImages} 张描述图片` })
      break
    }
    try {
      if (remaining <= 0) throw new ImageReadError(sizeError)
      const limit = Math.min(maxImageBytes, remaining)
      const { mime, bytes } = await readWithRetry(download, images[i].props.url, limit, budget)
      if (Buffer.isBuffer(bytes) && bytes.length > limit) throw new ImageReadError(sizeError)
      if (!imageTypes.has(mime) || !Buffer.isBuffer(bytes) || !hasImageSignature(mime, bytes))
        throw new ImageReadError(formatError)
      remaining -= bytes.length
      results.push({ index: i + 1, imageUrl: `data:${mime};base64,${bytes.toString('base64')}` })
    } catch (error) {
      results.push({ index: i + 1, error: downloadFailure(error).message })
    }
  }
  return results
}
