#!/usr/bin/env bash
# Push the project's cached AI models to a device's ThoughtPocket dirs, so we don't have to
# re-copy them from Edge Gallery (or another device) every time. The models live in the
# git-ignored models/ folder (models/llm/*.litertlm + models/gecko/*); populate it once with
# `adb pull` or tools/copy-edge-model.sh. Files already present with the same size are skipped.
# World-readable (0666) so the app's native open() can read them.
#
# Usage: tools/push-models.sh [adb-serial]
set -euo pipefail
SER="${1:-}"
ADB=(adb); [ -n "$SER" ] && ADB=(adb -s "$SER")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLM=/sdcard/Android/data/com.thoughtpocket/files/llm
GECKO=/sdcard/Android/data/com.thoughtpocket/files/gecko
CODER=/sdcard/Android/data/com.thoughtpocket/files/coder

push() {  # localfile remotedir
    local f="$1" dir="$2" name lsize rsize
    name=$(basename "$f")
    lsize=$(stat -c%s "$f")
    rsize=$("${ADB[@]}" shell "stat -c%s '$dir/$name' 2>/dev/null" | tr -d '\r' || true)
    if [ "$lsize" = "$rsize" ]; then echo "skip $name (already present, $rsize bytes)"; return; fi
    "${ADB[@]}" shell "mkdir -p '$dir'"
    echo "push $name ($((lsize / 1000000)) MB)…"
    "${ADB[@]}" push "$f" "$dir/$name" >/dev/null
    "${ADB[@]}" shell "chmod 0666 '$dir/$name'"
}

shopt -s nullglob
found=0
for f in "$ROOT"/models/llm/*.litertlm; do push "$f" "$LLM"; found=1; done
for f in "$ROOT"/models/gecko/*; do push "$f" "$GECKO"; found=1; done
for f in "$ROOT"/models/coder/*.gguf; do push "$f" "$CODER"; found=1; done
[ "$found" = 1 ] || { echo "No models in $ROOT/models/ — populate it first (adb pull / copy-edge-model.sh)."; exit 1; }
echo "Done."
