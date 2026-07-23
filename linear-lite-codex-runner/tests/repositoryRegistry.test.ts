import { describe, expect, it } from 'vitest'
import { RepositoryRegistry } from '../src/repositoryRegistry.js'
import { sanitizeEvent } from '../src/eventSanitizer.js'

describe('RepositoryRegistry', () => {
  const registry = new RepositoryRegistry({ linear: { path: '/tmp/linear', remoteIdentity: 'github.com/example/linear', defaultBranch: 'main' } })
  it('只按固定 repositoryKey 解析本地路径', () => {
    expect(registry.get('linear').path).toBe('/tmp/linear')
    expect(() => registry.get('/tmp/attacker')).toThrow('REPOSITORY_NOT_REGISTERED')
  })
})

describe('sanitizeEvent', () => {
  it('拒绝凭据和绝对路径', () => {
    expect(sanitizeEvent('status_changed', { authorization: 'secret' })).toBeNull()
    expect(sanitizeEvent('file_changed', { path: '/Users/me/a' })).toBeNull()
  })
  it('接受裁剪后的普通事件', () => expect(sanitizeEvent('status_changed', { status: 'running' })).toContain('running'))
})
