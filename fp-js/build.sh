#!/bin/sh
# 压缩防指纹脚本 → app/src/main/assets/fingerprint.min.js（需要 terser：npm i terser）
set -e
cd "$(dirname "$0")"
TERSER=${TERSER:-/workspace/tools/fpjs/node_modules/.bin/terser}
"$TERSER" fingerprint.js --compress passes=2 --mangle --ecma 2017 --comments false -o ../app/src/main/assets/fingerprint.min.js
node --check ../app/src/main/assets/fingerprint.min.js 2>/dev/null || true
wc -c fingerprint.js ../app/src/main/assets/fingerprint.min.js
