import { spawn } from 'node:child_process'

const SERVICE = 'linear-lite-codex-collaboration'
const SAFE_VALUE = /^[A-Za-z0-9._:-]+$/

function runSecurity(args, stdin) {
  return new Promise((resolve, reject) => {
    const child = spawn('/usr/bin/security', args, { stdio: ['pipe', 'pipe', 'pipe'] })
    const output = []
    child.stdout.on('data', chunk => output.push(chunk))
    child.stderr.resume()
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('钥匙串操作超时')) }, 10000)
    child.once('error', () => { clearTimeout(timer); reject(new Error('钥匙串操作失败')) })
    child.once('close', code => { clearTimeout(timer); code === 0 ? resolve(Buffer.concat(output).toString('utf8').trim()) : reject(new KeychainError(code)) })
    child.stdin.end(stdin)
  })
}

/** 凭据只经过 stdin 与钥匙串，不进入参数、环境或宿主运行记录。 */
export class MacKeychainVault {
  constructor(run = runSecurity) { this.run = run }
  async put(account, secret) {
    validate(account, secret)
    const command = `add-generic-password -U -s ${SERVICE} -a ${account} -w ${secret}\n`
    // security -i 每行缓冲区为 4096 字节；截断会写入不完整的值。
    if (Buffer.byteLength(command, 'utf8') >= 4096) throw new Error('钥匙串凭据过长')
    await this.run(['-i'], command)
    if (await this.get(account) !== secret) throw new Error('钥匙串保存校验失败')
  }
  async get(account) {
    validate(account)
    const value = await this.run(['find-generic-password', '-s', SERVICE, '-a', account, '-w'])
    if (!value || !SAFE_VALUE.test(value)) throw new Error('钥匙串凭据无效')
    return value
  }
  async remove(account) {
    validate(account)
    await this.run(['delete-generic-password', '-s', SERVICE, '-a', account])
  }
  async removeIfPresent(account) {
    try { await this.remove(account); return true }
    catch (error) { if (error instanceof KeychainError && error.code === 44) return false; throw error }
  }
}

export class KeychainError extends Error {
  constructor(code) { super('钥匙串操作失败'); this.code = code }
}

function validate(account, secret) {
  if (typeof account !== 'string' || !account || account.length > 200 || !SAFE_VALUE.test(account)
      || (secret !== undefined && (typeof secret !== 'string' || !secret || !SAFE_VALUE.test(secret)))) {
    throw new Error('钥匙串参数无效')
  }
}
