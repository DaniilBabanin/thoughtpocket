#!/usr/bin/env bash
# Fetch the long-form transcription-benchmark audio (public-domain presidential speeches) and convert it to
# the 16 kHz mono s16le PCM the app feeds Whisper/Moonshine. Source .ogg + derived .pcm land in the
# git-ignored bench-audio/ dir; push them to a device with tools/push-bench.sh, then run LongFormBench.
#
# The reference transcripts are committed (app/src/androidTest/assets/longform/) — only the big audio lives
# here. URLs + sha256 are pinned for reproducibility; the .ogg is verified after download. The .pcm sha256 is
# printed (it depends on your ffmpeg's resampler, so it is reported, not enforced).
#
# Usage: tools/fetch-bench-audio.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/bench-audio"
mkdir -p "$OUT"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found"; exit 1; }

# id | source .ogg URL | expected ogg sha256
FIXTURES=(
  "jfk_rice|https://upload.wikimedia.org/wikipedia/commons/5/50/Jfk_rice_university_we_choose_to_go_to_the_moon.ogg|7486fba805c20bde4d43750bd9c34aee86ab7acfd3854a1d0f3d64f62ae68b27"
  "eisenhower_farewell|https://upload.wikimedia.org/wikipedia/commons/9/90/Eisenhower_farewell_address.ogg|220b342a83842c31a416597f237bcbea0bd73cd5d4699c3514d70c41b2e5b12a"
)

for row in "${FIXTURES[@]}"; do
  IFS='|' read -r id url sha <<<"$row"
  ogg="$OUT/$id.ogg"; pcm="$OUT/$id.pcm"
  if [ ! -f "$ogg" ] || [ "$(sha256sum "$ogg" | cut -d' ' -f1)" != "$sha" ]; then
    echo "downloading $id …"
    curl -fSL --retry 3 --max-time 300 -o "$ogg" "$url"
  else
    echo "have $id.ogg (sha ok)"
  fi
  got="$(sha256sum "$ogg" | cut -d' ' -f1)"
  [ "$got" = "$sha" ] || { echo "  WARNING: $id.ogg sha256 mismatch (got $got, expected $sha) — source may have been re-encoded"; }
  echo "converting $id → 16 kHz mono s16le PCM …"
  ffmpeg -nostdin -v error -y -i "$ogg" -ac 1 -ar 16000 -f s16le -acodec pcm_s16le "$pcm"
  sz=$(stat -c%s "$pcm")
  printf "  %-22s %s s  (%s MB)  pcm sha256=%s\n" "$id" \
    "$(awk "BEGIN{printf \"%.1f\", $sz/2/16000}")" "$((sz/1000000))" "$(sha256sum "$pcm" | cut -d' ' -f1)"
done
echo "Done. Push with: tools/push-bench.sh"
