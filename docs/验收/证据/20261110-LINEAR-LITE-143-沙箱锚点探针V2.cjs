const fs = require('fs'), os = require('os'), path = require('path')
const { spawn } = require('child_process'), readline = require('readline')
;(async () => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'll143-anchor-'))
  const root = path.join(base, 'root'), held = path.join(base, 'held'), outside = path.join(base, 'outside')
  try {
    fs.mkdirSync(root); fs.mkdirSync(outside)
    const original = fs.statSync(root)
    fs.renameSync(root, held); fs.symlinkSync(outside, root)
    const q = JSON.stringify
    const profile = `(version 1)(allow default)(deny network*)(deny process-exec)
      (allow process-exec (literal ${q(process.execPath)}) (literal "/bin/cp"))
      (deny file-read-data)(deny file-write*)
      (allow file-read-data (literal "/") (literal "/dev/null") (literal "/dev/fd/3") (literal "/dev/fd/4") (literal "/bin/cp") (literal ${q(process.execPath)}) (subpath ${q(root)}))
      (allow file-write* (literal "/dev/fd/4") (subpath ${q(root)}))`
    const code = 'const fs=require("fs"),rl=require("readline").createInterface({input:process.stdin});console.log("ready");let n=0;rl.on("line",()=>{if(n++===0){const s=fs.statSync(process.argv[1]);console.log(s.ino===Number(process.argv[2])?"root-confirmed":"bad-root")}else{try{fs.writeFileSync(process.argv[1]+"/probe.txt","x");console.log("WRITE_SUCCEEDED")}catch(e){console.log("WRITE_"+e.code)}process.exit(0)}})'
    const child = spawn('/usr/bin/sandbox-exec', ['-p', profile, process.execPath, '-e', code, root, String(original.ino)], { env: { OPENSSL_CONF: '/dev/null' }, stdio: ['pipe', 'pipe', 'pipe'] })
    const lines = readline.createInterface({ input: child.stdout })
    let stage = 0
    for await (const line of lines) {
      if (stage++ === 0) { fs.unlinkSync(root); fs.renameSync(held, root); child.stdin.write('check\n') }
      else if (stage === 2) { console.log(line); fs.renameSync(root, held); fs.symlinkSync(outside, root); child.stdin.write('write\n') }
      else console.log(line)
    }
    console.log(JSON.stringify({ outsideFile: fs.existsSync(path.join(outside, 'probe.txt')) }))
  } finally { fs.rmSync(base, { recursive: true, force: true }) }
})().catch(error => { console.error(error.message); process.exit(1) })
