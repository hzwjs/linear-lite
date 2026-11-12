const fs = require('node:fs')
const fsp = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { spawn } = require('node:child_process')

async function run() {
  const base = await fsp.realpath(await fsp.mkdtemp(path.join(os.tmpdir(), 'linear-lite-144-probe-')))
  const root = path.join(base, 'project')
  const privateState = path.join(base, 'private')
  const outside = path.join(base, 'outside')
  await fsp.mkdir(root)
  await fsp.mkdir(privateState)
  await fsp.writeFile(outside, 'sentinel')
  await fsp.writeFile(path.join(privateState, 'credential'), 'fixture-private')
  await fsp.link(path.join(privateState, 'credential'), path.join(root, 'alias'))
  const quote = value => JSON.stringify(value)
  const profile = `(version 1)(allow default)(deny network*)(deny file-write*)(allow file-write* (subpath ${quote(root)}))(deny file-read* file-write* (subpath ${quote(privateState)}))`
  const shell = text => new Promise((resolve, reject) => {
    const child = spawn('/usr/bin/sandbox-exec', ['-p', profile, '/bin/sh', '-c', text], { stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = '', stderr = ''
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.on('error', reject)
    child.on('close', code => resolve({ code, stdout, stderr }))
  })
  try {
    const normal = await shell(`printf ok > '${root}/ordinary'`)
    const directPrivate = await shell(`cat '${privateState}/credential'`)
    const aliasPrivate = await shell(`cat '${root}/alias'`)
    await fsp.unlink(path.join(root, 'alias'))
    const racing = shell(`while [ ! -e '${root}/go' ]; do sleep .02; done; printf changed > '${root}/late-alias'`)
    await new Promise(resolve => setTimeout(resolve, 150))
    await fsp.link(outside, path.join(root, 'late-alias'))
    await fsp.writeFile(path.join(root, 'go'), '')
    const late = await racing
    const result = {
      platform: process.platform,
      ordinaryProjectWriteAllowed: normal.code === 0,
      directPrivateReadDenied: directPrivate.code !== 0,
      privateHardlinkReadAllowed: aliasPrivate.code === 0 && aliasPrivate.stdout === 'fixture-private',
      lateHardlinkWriteAllowed: late.code === 0,
      outsideSentinelChanged: await fsp.readFile(outside, 'utf8') === 'changed',
    }
    console.log(JSON.stringify(result, null, 2))
    if (!result.ordinaryProjectWriteAllowed || !result.directPrivateReadDenied || !result.privateHardlinkReadAllowed || !result.lateHardlinkWriteAllowed || !result.outsideSentinelChanged) process.exitCode = 1
  } finally {
    await fsp.rm(base, { recursive: true, force: true })
  }
}
run().catch(error => { console.error(error.message); process.exitCode = 1 })
