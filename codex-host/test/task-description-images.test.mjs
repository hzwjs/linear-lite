import test from 'node:test'
import assert from 'node:assert/strict'
import { taskDescriptionImages } from '../src/task-description-images.mjs'

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X8AAAAABJRU5ErkJggg==', 'base64')
const image = url => ({ type: 'image', props: { url } })

test('claimed description images become ordered native image input with independent failure reporting', async () => {
  const description = JSON.stringify([
    { type: 'paragraph', props: { url: 'https://example.com/not-an-image' }, children: [image('https://example.com/a.png')] },
    image('https://example.com/invalid.png'),
    image('https://example.com/b.png'),
  ])
  const read = []
  const results = await taskDescriptionImages(description, async src => {
    read.push(src)
    return { mime: 'image/png', bytes: src.includes('invalid') ? Buffer.from('not PNG') : png }
  })
  assert.deepEqual(read, ['https://example.com/a.png', 'https://example.com/invalid.png', 'https://example.com/b.png'])
  assert.equal(results[0].imageUrl, `data:image/png;base64,${png.toString('base64')}`)
  assert.equal(results[1].error, '图片格式不受支持或文件内容损坏')
  assert.equal(results[2].index, 3)
  assert.equal(results[2].imageUrl, results[0].imageUrl)
})

test('a transport interruption retries the same image, with a fresh request signal', async () => {
  const src = 'https://example.com/a.png'
  const attempts = []
  const results = await taskDescriptionImages(JSON.stringify([image(src)]), async (url, limit, signal) => {
    attempts.push({ url, limit, signal })
    if (attempts.length === 1) throw Object.assign(new Error('internal network address'), { code: 'ECONNRESET' })
    return { mime: 'image/png', bytes: png }
  })
  assert.equal(attempts.length, 2)
  assert.ok(attempts.every(attempt => attempt.url === src && attempt.limit === 10 * 1024 * 1024))
  assert.notEqual(attempts[0].signal, attempts[1].signal)
  assert.equal(results[0].imageUrl, `data:image/png;base64,${png.toString('base64')}`)
})

test('persistent connection errors stop after two attempts and redact internal details', async () => {
  let attempts = 0
  const results = await taskDescriptionImages(JSON.stringify([image('https://example.com/a.png')]), async () => {
    attempts++
    throw Object.assign(new Error('internal network address'), { code: 'ECONNRESET' })
  })
  assert.equal(attempts, 2)
  assert.deepEqual(results, [{ index: 1, error: '图片下载连接中断' }])
})

test('size and image format errors do not trigger retries', async () => {
  const attempted = []
  const results = await taskDescriptionImages(JSON.stringify([image('https://example.com/large'), image('https://example.com/html')]),
    async (url, limit) => {
      attempted.push(url)
      return url.endsWith('/large') ? { mime: 'image/png', bytes: Buffer.alloc(limit + 1) } : { mime: 'text/html', bytes: png }
    })
  assert.equal(attempted.length, 2)
  assert.deepEqual(results, [
    { index: 1, error: '图片超过大小限制' },
    { index: 2, error: '图片格式不受支持或文件内容损坏' },
  ])
})

test('description URL cannot access loopback, link-local or insecure sources', async () => {
  const results = await taskDescriptionImages(JSON.stringify([
    image('https://127.0.0.1/secret'), image('https://[::1]/secret'),
    image('https://localhost/secret'), image('http://example.com/picture.png'),
  ]))
  assert.equal(results.length, 4)
  assert.ok(results.every(result => result.error && !result.imageUrl))
})
