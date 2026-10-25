#!/bin/bash
# Download the sherpa-onnx Android library and the on-device speech models
# into the :speechmodels asset pack. They are too large for git (the Korean
# encoder alone is 121 MB), so this runs once after cloning.
# Source: k2-fsa/sherpa-onnx (Apache-2.0).
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="2.0.0"
AAR="app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
if [ ! -f "$AAR" ]; then
  echo "downloading sherpa-onnx $SHERPA_VERSION AAR…"
  mkdir -p app/libs
  curl -L --progress-bar -o "$AAR" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
fi

DEST="speechmodels/src/main/assets/models"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
KO="sherpa-onnx-streaming-zipformer-korean-2024-06-16"
EN="sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"

if [ -f "$DEST/ko/encoder.int8.onnx" ] && [ -f "$DEST/en/encoder.int8.onnx" ]; then
  echo "models already present in $DEST"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() { # name lang
  local name="$1" lang="$2"
  echo "downloading $name…"
  curl -L --progress-bar -o "$TMP/$name.tar.bz2" "$BASE/$name.tar.bz2"
  tar xjf "$TMP/$name.tar.bz2" -C "$TMP"
  mkdir -p "$DEST/$lang"
  # int8 encoder/joiner, fp32 decoder — the combination sherpa-onnx's own
  # Android sample uses for these models.
  cp "$TMP/$name/encoder-epoch-99-avg-1.int8.onnx" "$DEST/$lang/encoder.int8.onnx"
  cp "$TMP/$name/decoder-epoch-99-avg-1.onnx" "$DEST/$lang/decoder.onnx"
  cp "$TMP/$name/joiner-epoch-99-avg-1.int8.onnx" "$DEST/$lang/joiner.int8.onnx"
  cp "$TMP/$name/tokens.txt" "$DEST/$lang/tokens.txt"
}

fetch "$KO" ko
fetch "$EN" en
du -sh "$DEST"/*
echo "OK"
