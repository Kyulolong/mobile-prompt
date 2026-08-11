#!/bin/bash
# Bundle the alignment engine for JavaScriptCore.
# The built artifact (MobilePrompt/Engine/engine.js) is committed, so Xcode
# builds never need node — rerun this only after editing engine-src/.
set -euo pipefail
cd "$(dirname "$0")"

ESBUILD="/Users/teaminpact/develop/auto_prompt/node_modules/.bin/esbuild"
if [ ! -x "$ESBUILD" ]; then ESBUILD="npx -y esbuild"; fi

$ESBUILD glue.ts --bundle --format=iife --target=es2020 \
  --outfile="../MobilePrompt/Engine/engine.js"
echo "OK: $(wc -c < ../MobilePrompt/Engine/engine.js) bytes"
