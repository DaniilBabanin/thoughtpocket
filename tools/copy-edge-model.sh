#!/usr/bin/env bash
# Copy an on-device AI Edge Gallery model (.litertlm + its sibling weight/cache
# files) into SoundScript's LLM dir over adb, so SoundScript can use it.
#
# This is a developer convenience. Edge Gallery downloads the (licence-gated)
# Gemma models with the user's auth; SoundScript can't read another app's
# Android/data dir, so we copy via adb (shell can) and chmod world-readable
# (else SoundScript's native open() is permission-denied).
#
# A user-friendly in-app import already exists (Settings -> Import); this script
# just automates pulling models straight from Edge Gallery for testing.
#
# Usage: tools/copy-edge-model.sh <name-substring> [adb-serial]
#   e.g. tools/copy-edge-model.sh 1B
#        tools/copy-edge-model.sh E2B 48171FDAS004EU
set -euo pipefail

PAT="${1:?usage: copy-edge-model.sh <model-name-substring> [adb-serial]}"
SER="${2:-}"
ADB=(adb); [ -n "$SER" ] && ADB=(adb -s "$SER")

EG=/storage/emulated/0/Android/data/com.google.ai.edge.gallery/files
DST=/sdcard/Android/data/com.soundscript/files/llm

SRC=$("${ADB[@]}" shell "find $EG -type f -iname '*.litertlm' 2>/dev/null" \
        | tr -d '\r' | grep -i "$PAT" | head -1)
[ -z "$SRC" ] && { echo "No .litertlm matching '$PAT' in Edge Gallery."; exit 1; }

SRCDIR=$(dirname "$SRC")
echo "Source : $SRC"
echo "Dest   : $DST"
"${ADB[@]}" shell "mkdir -p $DST"
echo "Copying model + sibling weight/cache files (this can take a minute)…"
"${ADB[@]}" shell "cp '$SRCDIR/'* '$DST/' && chmod 0666 '$DST/'* 2>/dev/null; ls -lah '$DST'"
echo "Done. Select it in SoundScript -> Settings -> AI model."
