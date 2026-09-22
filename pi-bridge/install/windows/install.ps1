$ErrorActionPreference = 'Stop'
$BridgeDirectory = Resolve-Path (Join-Path $PSScriptRoot '..\..')

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js 18 or later is required.'
}
if (-not (Get-Command pi -ErrorAction SilentlyContinue)) {
    throw 'Pi is required. Install Pi before installing Bridge.'
}

node (Join-Path $BridgeDirectory 'scripts\service.mjs') install
Start-Process 'http://127.0.0.1:9780/'
