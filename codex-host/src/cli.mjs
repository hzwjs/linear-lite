import os from 'node:os'
import path from 'node:path'
import { loadHostIdentity } from './device.mjs'
import { createHostServer } from './server.mjs'
import { createCollaborationClient } from './api-client.mjs'
import { MacKeychainVault } from './keychain.mjs'
import { runPool } from './pool.mjs'

function parse(args, permitted) {
  const result = {}
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]
    if (!permitted.includes(key) || !args[i + 1] || args[i + 1].startsWith('--') || Object.hasOwn(result, key))
      throw new Error('命令参数无效')
    result[key] = args[i + 1]
  }
  return result
}
async function readSecret() {
  if (process.stdin.isTTY) throw new Error('认证凭据须通过标准输入提供')
  let text = ''
  for await (const chunk of process.stdin) { text += chunk; if (text.length > 16384) throw new Error('认证凭据无效') }
  const token = text.trim()
  if (!token || token.includes('\n') || token.includes('\r')) throw new Error('认证凭据无效')
  return token
}
async function main() {
  const [mode, ...args] = process.argv.slice(2)
  if (mode === 'plan') {
    if (args.length) throw new Error('命令参数无效')
    console.log(JSON.stringify({ cron: '0 1 * * *', timeZone: 'Asia/Shanghai', enabled: false }))
    return
  }
  const options = parse(args, mode === 'serve' ? ['--origin', '--state-dir'] : mode === 'run'
    ? ['--base-url', '--state-dir', '--project-id', '--task-ids', '--slots', '--max-attempts', '--max-duration-ms', '--expires-at'] : [])
  if (!['serve', 'run'].includes(mode)) throw new Error('命令无效')
  const stateDirectory = path.resolve(options['--state-dir'] ?? path.join(os.homedir(), 'Library', 'Application Support', 'Linear-Lite', 'CodexHost'))
  const identity = await loadHostIdentity(stateDirectory)
  if (mode === 'serve') {
    if (!options['--origin']) throw new Error('缺少站点来源')
    const server = createHostServer({ identity, allowedOrigin: options['--origin'] })
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(9781, '127.0.0.1', resolve) })
    console.log(JSON.stringify({ state: 'READY', port: 9781 }))
    for (const name of ['SIGINT', 'SIGTERM']) process.once(name, () => server.close(() => process.exit(0)))
    return
  }
  const token = await readSecret()
  const client = createCollaborationClient({ baseUrl: options['--base-url'], authToken: token })
  const abort = new AbortController()
  for (const name of ['SIGINT', 'SIGTERM']) process.once(name, () => abort.abort())
  const result = await runPool({ client, identity, stateDirectory, vault: new MacKeychainVault(), authToken: token,
    projectId: Number(options['--project-id']), taskIds: (options['--task-ids'] ?? '').split(',').map(Number),
    maxSlots: Number(options['--slots']), maxAttempts: Number(options['--max-attempts']),
    maxDurationMs: Number(options['--max-duration-ms']), expiresAt: options['--expires-at'], signal: abort.signal })
  console.log(JSON.stringify(result))
}
main().catch(() => { console.error('宿主操作失败'); process.exitCode = 1 })
