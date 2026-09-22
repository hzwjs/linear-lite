#!/bin/bash
set -euo pipefail

BRIDGE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

if ! command -v node >/dev/null 2>&1; then
  echo "未找到 Node.js，请安装 Node.js 18 或更高版本。"
  exit 1
fi
if ! command -v pi >/dev/null 2>&1; then
  echo "未找到 Pi，请先安装 Pi。"
  exit 1
fi

node "$BRIDGE_DIR/scripts/service.mjs" install
open "http://127.0.0.1:9780/"
