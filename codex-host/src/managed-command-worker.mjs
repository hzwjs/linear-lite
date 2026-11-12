import { spawn } from 'node:child_process'

// 此进程必须由宿主在已验证的沙箱内启动；它本身不构成沙箱。
const limit = 16 * 1024
let input = ''
process.stdin.setEncoding('utf8')
for await (const chunk of process.stdin) {
  input += chunk
  if (Buffer.byteLength(input) > 128 * 1024) process.exit(2)
}

let request
try { request = JSON.parse(input) } catch { process.exit(2) }
if (!request || !Array.isArray(request.argv) || !request.argv.length
    || request.argv.some((value, index) => typeof value !== 'string' || (index === 0 && !value) || value.includes('\0'))
    || typeof request.cwd !== 'string' || !request.cwd.startsWith('/')) process.exit(2)

const child = spawn(request.argv[0], request.argv.slice(1), {
  cwd: request.cwd, env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, stdio: ['ignore', 'pipe', 'pipe'],
})
child.stdout.setEncoding('utf8')
child.stderr.setEncoding('utf8')
let stdout = '', stderr = '', truncated = false, failed = false
function collect(current, chunk) {
  const text = chunk.toString('utf8')
  if (current.length + text.length > limit) truncated = true
  return (current + text).slice(0, limit)
}
child.stdout.on('data', chunk => { stdout = collect(stdout, chunk) })
child.stderr.on('data', chunk => { stderr = collect(stderr, chunk) })
child.on('error', () => { failed = true })
child.on('close', (code, signal) => {
  process.stdout.write(JSON.stringify({ exitCode: Number.isSafeInteger(code) ? code : null,
    signal: typeof signal === 'string' ? signal : null, stdout, stderr, truncated, failed }))
})
