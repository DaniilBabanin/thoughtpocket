#!/usr/bin/env bash
# Stage the LongFormBench fixtures on a connected device: push the benchmark PCM to the app's external bench
# dir, and the Whisper ggml models into the app's *internal* models dir (where ModelManager.fileFor looks).
# Moonshine is assumed already installed (tools/push-models.sh / in-app download). The whisper ggml are
# fetched from HuggingFace into the git-ignored bench-audio/whisper/ cache on first run.
#
# Internal storage isn't writable by `adb push`, so the ggml go via /data/local/tmp + `run-as cp` — this
# needs a DEBUG build of com.thoughtpocket installed (run-as only works on debuggable apps).
#
# Usage: tools/push-bench.sh [adb-serial] [model ...]   (default models: base small)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AUDIO="$ROOT/bench-audio"
PKG=com.thoughtpocket
HF=https://huggingface.co/ggerganov/whisper.cpp/resolve/main

SER=""; MODELS=()
for a in "$@"; do case "$a" in */*|[0-9]*) SER="$a";; *) MODELS+=("$a");; esac; done
[ "${#MODELS[@]}" -eq 0 ] && MODELS=(base small)
ADB=(adb); [ -n "$SER" ] && ADB=(adb -s "$SER")

# --- PCM fixtures → internal files/bench (via run-as; shell-pushed files under the app's *external* dir
#     are invisible to the app on Android 11+, so the bench reads from internal storage like the ggml). ---
shopt -s nullglob
pcms=("$AUDIO"/*.pcm)
[ "${#pcms[@]}" -gt 0 ] || { echo "No PCM in $AUDIO — run tools/fetch-bench-audio.sh first"; exit 1; }
"${ADB[@]}" shell "run-as $PKG mkdir -p files/bench"
for f in "${pcms[@]}"; do
  name=$(basename "$f"); lsize=$(stat -c%s "$f")
  rsize=$("${ADB[@]}" shell "run-as $PKG stat -c%s files/bench/$name 2>/dev/null" | tr -d '\r' || true)
  if [ "$lsize" = "$rsize" ]; then echo "skip $name (present)"; continue; fi
  echo "push $name ($((lsize/1000000)) MB)…"
  "${ADB[@]}" push "$f" "/data/local/tmp/$name" >/dev/null
  "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/$name files/bench/$name"
  "${ADB[@]}" shell "rm -f /data/local/tmp/$name"
done

# --- Whisper ggml → internal models dir (via run-as) ---
mkdir -p "$AUDIO/whisper"
for m in "${MODELS[@]}"; do
  fn="ggml-$m-q5_1.bin"; local_f="$AUDIO/whisper/$fn"
  if [ ! -f "$local_f" ]; then
    echo "fetch $fn from HuggingFace…"
    curl -fSL --retry 3 -o "$local_f" "$HF/$fn"
  fi
  echo "push $fn → internal models/ (run-as)…"
  "${ADB[@]}" push "$local_f" "/data/local/tmp/$fn" >/dev/null
  "${ADB[@]}" shell "run-as $PKG sh -c 'mkdir -p files/models && cp /data/local/tmp/$fn files/models/$fn'"
  "${ADB[@]}" shell "rm -f /data/local/tmp/$fn"
  echo "  installed $fn ($("${ADB[@]}" shell "run-as $PKG stat -c%s files/models/$fn" | tr -d '\r') bytes)"
done
echo "Done. Run e.g.: adb ${SER:+-s $SER} shell am instrument -w -e class com.thoughtpocket.LongFormBench#whisperBase $PKG.test/androidx.test.runner.AndroidJUnitRunner"
