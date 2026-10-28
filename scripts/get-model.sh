#!/usr/bin/env bash
# Download Gemma 4 E2B (or E4B) LiteRT-LM file outside the repo, resumable, then adb push.
# Usage: scripts/get-model.sh [e2b|e4b] [--no-push]
set -euo pipefail
SIZE="${1:-e2b}"
DEST="${HAKU_MODELS_DIR:-F:/Haku Project/models}"
REV_E2B=6e5c4f1e395deb959c494953478fa5cec4b8008f
REV_E4B=28299f30ee4d43294517a4ac93abd6163412f07f
case "$SIZE" in
  e2b) FILE=gemma-4-E2B-it.litertlm; URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REV_E2B/$FILE?download=true" ;;
  e4b) FILE=gemma-4-E4B-it.litertlm; URL="https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/$REV_E4B/$FILE?download=true" ;;
  *) echo "usage: $0 [e2b|e4b] [--no-push]" >&2; exit 2 ;;
esac
mkdir -p "$DEST"
AUTH=()
[ -n "${HF_TOKEN:-}" ] && AUTH=(-H "Authorization: Bearer $HF_TOKEN")
curl -L --fail -C - "${AUTH[@]}" -o "$DEST/$FILE" "$URL"
echo "downloaded: $DEST/$FILE"
if [ "${2:-}" != "--no-push" ]; then
  adb shell mkdir -p /data/local/tmp/haku
  adb push "$DEST/$FILE" /data/local/tmp/haku/
fi
