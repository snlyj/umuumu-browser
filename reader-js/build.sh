#!/bin/sh
# 打包阅读模式脚本 → app/src/main/assets/reader/（需要 terser：/workspace/tools/fpjs/node_modules/.bin/terser）
# Readability.js / Readability-readerable.js 来自 https://github.com/mozilla/readability tag 0.6.0（Apache-2.0）
set -e
cd "$(dirname "$0")"
TERSER=${TERSER:-/workspace/tools/fpjs/node_modules/.bin/terser}
OUT=../app/src/main/assets/reader
mkdir -p "$OUT" build-tmp
# var module：让 Readability 末尾的 `module.exports = …` 不会碰到网页自己的全局变量
{ echo '(function(){var module;'; cat Readability.js; cat extract.js; echo '})()'; } > build-tmp/extract.js
{ echo '(function(){var module;'; cat Readability-readerable.js; cat check.js; echo '})()'; } > build-tmp/check.js
for f in extract check; do
  "$TERSER" build-tmp/$f.js --compress passes=2,negate_iife=false,expression=true --mangle --ecma 2017 --comments false --parse bare_returns -o "$OUT/$f.min.js"
  node --check "$OUT/$f.min.js"
done
cp LICENSE-readability.md "$OUT/LICENSE-readability.md"
cp LICENSE-APACHE-2.0.txt "$OUT/LICENSE-APACHE-2.0.txt"
rm -rf build-tmp
wc -c "$OUT"/*
