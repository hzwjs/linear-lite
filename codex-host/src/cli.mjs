import os from 'node:os'
import path from 'node:path'
import { startHost } from './daemon.mjs'

function parse(args) {
  const result = {}
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]
    if (key !== '--state-dir' || !args[i + 1] || args[i + 1].startsWith('--') || Object.hasOwn(result, key))
      throw new Error('命令参数无效')
    result[key] = args[i + 1]
  }
  return result
}
async function main() {
  const [mode, ...args] = process.argv.slice(2)
  if (mode === 'plan') {
    if (args.length) throw new Error('命令参数无效')
    console.log(JSON.stringify({ cron: '0 1 * * *', timeZone: 'Asia/Shanghai', enabled: false }))
    return
  }
  if (mode !== 'serve') throw new Error('命令无效')
  const options = parse(args)
  const stateDirectory = path.resolve(options['--state-dir'] ?? path.join(os.homedir(), 'Library', 'Application Support', 'Linear-Lite', 'CodexHost'))
  const host = await startHost({ stateDirectory })
  console.log(JSON.stringify({ state: 'READY', port: host.server.address().port }))
  for (const name of ['SIGINT', 'SIGTERM']) process.once(name, () => {
    host.close().then(() => process.exit(0), () => process.exit(1))
  })
}
main().catch(() => { console.error('宿主操作失败'); process.exitCode = 1 })
