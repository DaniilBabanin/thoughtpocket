# Plan: Live Streaming Transcript (optional, Whisper-batch stays the proven default)

Status: proposal · Owner: TBD · Created: 2026-06-24
Prompted by: Google AI Edge Eloquent (instant word-by-word dictation).

## Goal

Make transcribed words appear *as the user speaks* (Eloquent-style), as an **opt-in** mode.
Keep the current Whisper batch pipeline as a fully-supported, default option — it is proven,
high-quality, and produces the durable final transcript. Streaming is additive, never a replacement.

## Why this is an evolution, not a greenfield build

A live-preview path **already exists** and we should build on it, not around it:

- `RecordingService.liveLoop()` (`app/.../service/RecordingService.kt:181`) already runs while
  recording: every `LIVE_INTERVAL_MS` (2500 ms) it reads the **trailing 30 s window**
  (`PREVIEW_WINDOW_SAMPLES`, `rec.readTail(...)`), re-transcribes it with Whisper (greedy,
  `highQuality=false`), and publishes the text to `RecordState.setPartial(text)`.
- It is gated by `prefs.liveTranscription` and only runs when the model is small enough
  (`model.approxSizeMb <= LIVE_MAX_MODEL_MB`).
- The UI already observes `RecordState.partial: StateFlow<String>`
  (`service/RecordState.kt:19-21`) — MainActivity / NotesScreens / the ongoing notification.
- The whisper.cpp JNI bridge **already exposes streaming callbacks**:
  `WhisperEngine.transcribe(..., onSegment, onProgress)` (`WhisperEngine.kt:79-98`) routed through
  `TranscribeCallback.jniSegment/jniProgress` (`WhisperEngine.kt:160-173`). **These are currently
  unused** — `liveLoop` ignores them and re-runs the whole window from scratch each tick.

So the perceived latency today is ~2.5 s, and every tick re-decodes 30 s of audio (wasteful, and the
text can "flicker" as the window slides). That is the thing to improve.

## The honest constraint

whisper.cpp is a **batch / offline** encoder-decoder. It is excellent at final quality but is **not a
true online streaming model** — it has no native "feed me audio incrementally, emit stable tokens"
mode. Eloquent's instant feel comes from a *streaming-native* ASR (auto-regressive, no future
context) running on the NPU. So there are two honest tiers of "streaming" we can ship:

- **Tier 1 — Better Whisper preview (cheap, low risk, ships first).** Stay on Whisper; reduce
  perceived latency and flicker. No new model, no native rebuild beyond wiring an existing callback.
- **Tier 2 — True streaming engine (real win, real cost).** Add a second, streaming-capable engine
  behind an interface; select it per-recording. Whisper batch remains the default and always
  produces the final transcript.

Recommendation: **ship Tier 1 first** (small, immediately better), then evaluate Tier 2 behind the
`TranscriptionEngine` abstraction below. **Update (2026-06-25): Tier 1 is built and measured on device — see
"Measured on device" below. Headline finding: CPU re-decode has a ~2.8 s/tick floor, so Tier 1 is a modest
polish, not "instant"; Tier 2 is the only path to an Eloquent-style live transcript.**

---

## Tier 1 — Improve the existing Whisper preview

Two independent improvements to `liveLoop()`:

1. **Use the segment callback instead of swapping the whole string.** Wire
   `onSegment` through `transcribe(...)` so committed segments stream into `RecordState.partial`
   incrementally, rather than replacing the entire field every 2.5 s. Reduces flicker; words land
   as whisper.cpp commits each segment within the windowed decode.
2. **Shrink the cadence/window for responsiveness.** Lower `LIVE_INTERVAL_MS` and/or decode a
   shorter trailing window (e.g. last 8-10 s with a small overlap) so time-to-update drops. Cost is
   bounded by window length; measure CPU headroom on the slowest target (see Validation).

Optional: a lightweight **local-agreement** stabilizer (only show a token once two consecutive
windows agree on it) to stop the tail of the preview from rewriting itself. Keep this as pure,
unit-testable logic if added (project convention: deterministic transforms get a JVM test).

Touch points:
- `RecordingService.liveLoop()` (`:181`) — pass `onSegment`, adjust window/interval.
- `RecordingService.transcribe(...)` (`:196`) — already the single serialized Whisper entry; add an
  `onSegment` passthrough param (currently it never forwards callbacks).
- Constants `LIVE_INTERVAL_MS`, `PREVIEW_WINDOW_SAMPLES`, `LIVE_MAX_MODEL_MB` (RecordingService).
- No DB, no model, no UI-contract change (UI already reads `RecordState.partial`).

Scope: small. Mostly within one file plus a callback passthrough.

### Built (2026-06-25)

`liveLoop()` now: window 30 s → **10 s**, cadence 2500 ms → **1200 ms**, `onSegment` streams the **first**
window's segments (later ticks publish the finished decode, to avoid mid-decode reset flicker), and a pure
`previewTail()` publishes only the **freshest ~14 words** to the card (the old 30 s text showed words from
~30 s ago, ellipsized off the end). Card `maxLines` 2 → 3. Saved note is untouched (full re-decode on stop).
JVM test: `RecordingPreviewTest`. On-device harness: `WhisperSegmentSpike`. A temporary in-context `Log.i`
("live onSegment fired") is in `liveLoop` — remove before ship.

## Measured on device — the latency ceiling is the encoder, not the window

**Pixel 9 Pro XL · `ggml-base.en-q5_1` · CPU** (the preview path forces `useGpu=false`). Steady-state decode
time vs window length (`WhisperSegmentSpike.decodeLatencyVsLength`):

| window | 1 s | 2 s | 4 s | 7 s | 10 s |
|--------|------|------|------|------|------|
| decode | 2799 ms | 2865 ms | 2885 ms | 2954 ms | 3325 ms |

**Decoding 1 s of audio costs ~2.8 s.** whisper.cpp pads the mel to a fixed 30 s and runs the full-size
encoder on *every* call, so there is a ~2.8 s **fixed floor per tick** independent of window length; the
decoder adds only ~tiny per-token time on top. Consequences:

- **Sub-second preview is impossible with CPU re-decode.** The "≤1 s" Tier-1 target below is unreachable this
  way. The ~2.8 s decode is itself the pacer, so the loop interval is just a heat breather, not the cadence —
  the perceived update floor is **~3 s, decode-bound** (interval set to 500 ms; near-0 → back-to-back decode
  but more heat). Still far better than the old ~30 s-window decode, but not "instant."
- **Shrinking the window barely helped latency** (10 s vs 30 s differ only by decoder-token time). Its real
  wins were *recency* and *less flicker* — keep them, but don't sell Tier 1 as instant. Window length is now
  known to be latency-cheap, so it's a free knob for more right-context if wanted.
- **Incremental / chunked decode would NOT help** — every whisper call pays the same encoder floor.
- **GPU is the only lever that hits the floor — and it's not available.** `WhisperEngine.compiledBackend`
  reports **`cpu`**: the shipped binary has no Vulkan, so `useGpu=true` silently runs on CPU
  (`decodeLatencyOnGpu` confirmed identical times). Enabling Vulkan is a native build change, and the
  `#ifdef WHISPERSHARE_VULKAN` Mali-G715 crash workaround in `whisper_jni.cpp` shows Vulkan was tried before
  and aborted on this exact device (`ggml_abort … ggml-vulkan.cpp:6452`).
- **Jitter is mild** — bounded to the last 1–3 edge words, which whisper revises as right-context arrives
  ("red" → "bread", "time like" → "timeline"); earlier words are stable. The deferred local-agreement
  stabilizer would polish this, but it is not the bottleneck.

Caveats on the numbers: the floor is measured at **4 threads** (`procs/2`, the default; the pref allows up to
16 — more threads may cut it ~1.3–1.6× on the heterogeneous cores, still not sub-second). These are **spot
decodes on a cool device**; the plan's "no thermal regression over a 5-min recording" criterion is unmeasured
and sustained back-to-back decoding could throttle latency *up*. And these are **offline decode** numbers —
the in-app `liveLoop` preview itself is not yet observed (the temp `Log.i` + a real recording confirm it).

**Revised recommendation:** keep Tier 1 as a modest polish (recency + less flicker + a *validated* streaming
JNI callback, which de-risks Tier 2), but the only path to an actual Eloquent-style instant transcript is
**Tier 2 — a streaming-native model** (no full-encoder-per-tick). CPU re-decode is encoder-floor-bound, and
GPU whisper is blocked by both the missing Vulkan build and the historical Mali crash.

---

## Tier 2 — Optional true-streaming engine

Introduce a transcription-engine abstraction so Whisper batch and a streaming engine coexist and the
final-quality pass is always Whisper.

### Abstraction

```
interface TranscriptionEngine {
    suspend fun load(model: File, useGpu: Boolean): Result<Unit>
    fun release()
    // streaming engines emit partials; all engines return a final string
    suspend fun transcribe(pcm16k: FloatArray, ..., onSegment: ((String) -> Unit)? = null): String
}
```

- `WhisperEngine` already matches this shape (it's an `object`; wrap it as the `BatchTranscriber`).
- Add a `StreamingTranscriber` implementation for the chosen streaming model.
- `RecordingService` owns a `selectedEngine` resolved from prefs; `transcribe(...)` (`:196`) and
  `liveLoop(...)` (`:181`) call through the interface instead of `WhisperEngine` directly.
- Keep the existing serialization (`whisper.withLock`) per engine that owns a native context.

### Engine candidates (decide via a spike, don't pre-commit)

- A streaming CTC/RNN-T English model (e.g. a small Conformer/transducer) exported to LiteRT — true
  online decoding, lowest latency, English-first (matches Eloquent's launch scope).
- whisper-streaming-style local-agreement over whisper.cpp (still Whisper, better stability) — this
  is really a stronger Tier 1 and may make Tier 2 unnecessary.
- Gemma 3n audio encoder via LiteRT-LM (we already run LiteRT-LM for Gemma; an audio-in path would
  consolidate runtimes) — most aligned with our stack, but verify on-device ASR latency/quality
  first; this is unproven for us.

### Model delivery

A new streaming model is a new entry in `ModelManager` (built-in set + download flow, same as
Whisper/Gemma/Gecko), opt-in download. Streaming mode is unavailable until that model is installed;
fall back to Tier 1 Whisper preview otherwise.

### Settings

Add a transcription-mode preference (`AppPreferences`, SharedPreferences, same pattern as
`liveTranscription`): `Off | Whisper preview | Streaming`. Default stays **Whisper preview** so the
proven path is the out-of-the-box experience.

### Always-Whisper final pass

Even in streaming mode, on stop we still run the Whisper batch transcription
(`transcribeAndSave`, `:224`) to produce the durable, high-quality note body + enrichment. The
streaming text is preview only. This is the key promise: **streaming never degrades the saved note.**

Scope: medium-large. New engine, model entry/download, settings, spike to pick the model.

---

## Validation / success criteria

- Tier 1: on the slowest supported device, perceived update latency drops from ~2.5 s to a target
  (e.g. ≤1 s) **without** the final saved transcript changing (compare final body before/after).
- No regression in CPU/thermals during a 5-min recording (watch for the live loop starving the mic
  thread). Confirm the mic still frees immediately on stop (`startRecording` hands off to the queue
  at `:148-154`).
- Streaming preview text must never leak into the saved note — assert the note body comes only from
  the final batch pass.
- Tier 2 spike gate: a streaming engine only ships if it beats Tier 1's latency meaningfully on
  device *and* its preview tracks the final Whisper transcript closely enough to be trustworthy.

## Risks

- Lowering the window/interval can starve the recording coroutine or overheat on weak devices —
  measure, keep `LIVE_MAX_MODEL_MB` gating.
- True streaming adds a second multi-GB-ish model and a new native/runtime path — real maintenance
  and download-size cost; don't take it on unless Tier 1 proves insufficient.
- Preview/final divergence erodes trust; the local-agreement stabilizer and "Whisper owns the final"
  rule mitigate this.

## Out of scope

- System-wide dictation (Eloquent is in-app only too).
- Non-English streaming (English-first, like Eloquent at launch).
- Cloud anything — stays fully on-device.

## Open questions

- ~~Is Tier 1 "instant enough" that Tier 2 isn't worth a second model?~~ **Answered (2026-06-25): no.**
  Measured ~2.8 s/tick CPU encoder floor → Tier 1 caps at ~4–4.5 s perceived update. If "instant" is the
  goal, Tier 2 is required. Open sub-question: is "recent words, ~4 s, less flicker" good enough to ship as-is
  for now, deferring Tier 2? (Product call.)
- If Tier 2: which engine — keep two runtimes (Whisper + streaming ASR) or consolidate on Gemma 3n
  audio under LiteRT-LM?
