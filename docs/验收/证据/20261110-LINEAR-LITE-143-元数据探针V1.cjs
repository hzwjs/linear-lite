const fs = require('fs'), os = require('os'), path = require('path')
const { execFileSync, spawnSync } = require('child_process')
const base = fs.mkdtempSync(path.join(os.tmpdir(), 'll143-meta-'))
try {
  const source = path.join(base, 'source'), target = path.join(base, 'target')
  fs.writeFileSync(source, 'content', { mode: 0o755 })
  execFileSync('/usr/bin/xattr', ['-w', 'com.linearlite.fixture', 'sample', source])
  execFileSync('/bin/chmod', ['+a', 'everyone allow read,readattr,readextattr,readsecurity', source])
  execFileSync('/bin/chmod', ['+a', 'everyone deny writeextattr', source])
  const acl = file => execFileSync('/bin/ls', ['-le', file], { encoding: 'utf8' }).match(/^\s*\d+:.*$/gm)
  const sourceAcl = acl(source)
  const sourceFd = fs.openSync(source, 'r'), targetFd = fs.openSync(target, 'wx+', 0o600)
  const copied = spawnSync('/bin/cp', ['-p', '/dev/fd/3', '/dev/fd/4'], { stdio: ['ignore', 'pipe', 'pipe', sourceFd, targetFd], env: {} })
  if (copied.status !== 0) throw new Error('cp failed')
  const copyMode = fs.fstatSync(targetFd).mode & 0o7777
  fs.fchmodSync(targetFd, fs.fstatSync(sourceFd).mode & 0o7777)
  fs.closeSync(sourceFd); fs.closeSync(targetFd)
  console.log(JSON.stringify({ copyMode, restoredMode: fs.statSync(target).mode & 0o7777,
    sourceAcl, targetAcl: acl(target), xattr: execFileSync('/usr/bin/xattr', ['-p', 'com.linearlite.fixture', target], { encoding: 'utf8' }).trim() }))
} finally { fs.rmSync(base, { recursive: true, force: true }) }
