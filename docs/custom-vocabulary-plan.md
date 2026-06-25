# Plan: Custom Vocabulary (+ the audio test harness that must justify it)

Status: proposal · Owner: TBD · Created: 2026-06-24
Prompted by: Google AI Edge Eloquent's custom word list for names/jargon.

## Goal

Let the user maintain a small word list (names, jargon, product terms) that biases Whisper toward
spelling those correctly. **On-device only** — we deliberately do NOT adopt Eloquent's Gmail-import
(that ships your contacts/sent-mail to build the list; it breaks our no-account, no-cloud promise).

## The premise must be proven before we ship it

The accuracy-gain claim ("custom vocab improves transcription") is **unverified for our stack**. We
will not ship product code on a vibe. So the work is **gated**: build a measurement harness first,
prove a real gain on representative audio, *then* wire the feature. If it doesn't move the numbers on
device, we don't ship it. This sequencing is the whole point of the doc.

```
Phase 0  Audio WER/term-recall harness (no product change) ── gate ──► Phase 1+
Phase 1  Native: expose whisper.cpp initial_prompt
Phase 2  Prefs + Settings vocabulary editor; feed list into transcribe()
```

## Why a native change is unavoidable (and why Phase 0 comes first)

whisper.cpp supports biasing via `whisper_full_params.initial_prompt`, but **our JNI bridge does not
expose it**. `nativeTranscribe(...)` (`WhisperEngine.kt:118-127`) has no prompt parameter, and
`transcribe(...)` (`:79-98`) has no way to pass one. So Phase 1 means editing the C++ binding +
rebuilding the native lib (CMake/NDK from source, per CLAUDE.md) — non-trivial. We don't pay that
cost until Phase 0 shows the gain is real.

## Phase 0 — the audio test harness (the deliverable that justifies the rest)

A new instrumented test (`app/src/androidTest/`) that feeds **real audio** through Whisper and
measures error against ground truth, **with and without** a biasing prompt.

Grounded in existing test infra:
- Instrumented tests already load multi-GB models on device and log results
  (`TaggingBenchmark.kt`, `ScaleTest.kt`); they read JSON fixtures from `androidTest/assets/`
  (e.g. `corpus.json`/`queries.json`) via `InstrumentationRegistry...context.assets.open(name)`.
- We already load a Whisper model and transcribe PCM in-app (`RecordingService.transcribe`,
  `WhisperEngine.transcribe`) — the test reuses `WhisperEngine.load()` + `transcribe()` directly.
- Run/collect pattern is established: `adb shell am instrument ... ; adb logcat -d -s <TAG>`.

Harness pieces:
1. **Fixtures.** A handful of `.wav` clips in `app/src/androidTest/assets/audio/` that actually
   contain hard-to-spell names/jargon (people, products, domain terms), plus a `ground_truth.json`
   mapping clip → reference transcript and the target vocab terms in each. Convert to 16 kHz mono
   float PCM with the existing audio path (`audio/AudioFiles`, `MicRecorder.readPcm`/`Pcm`). Keep
   clips small and committed; document provenance/licensing.
2. **Metrics (pure, JVM-unit-tested in `app/src/test/`):**
   - **WER** = word-level Levenshtein(ref, hyp) / ref length, after normalize (lowercase, strip
     punctuation). This is the headline number.
   - **Term recall** = fraction of the *target vocab terms* transcribed correctly. This is the metric
     that should actually move — overall WER may barely budge while the specific names get fixed,
     which is the real user value. **Report both.**
   - Both metrics are pure functions → unit tests with hand-checked cases, so we trust the harness
     before trusting its verdict (verification integrity: prove the ruler before measuring).
3. **Two arms per clip:** baseline (no prompt) vs biased (prompt = the clip's target terms). The
   biased arm needs Phase 1's native param — so Phase 0 ships in two sub-steps: 0a build + validate
   the harness on the **baseline arm only** (establishes WER/term-recall measurement end-to-end on
   one real clip), 0b add the biased arm once `initial_prompt` exists. Prove one clip end-to-end
   before fanning out to the full fixture set.
4. **Verdict logged**, e.g. `Log.i("VOCAB_WER", "clip=… baseWer=… biasWer=… baseTermRecall=…
   biasTermRecall=…")`, summarized across clips.

Gate: ship the feature only if the biased arm shows a **meaningful term-recall gain** (target names
actually getting fixed) without a WER regression on non-vocab words. Quantify the bar in review
(e.g. ≥X pp term-recall, ΔWER ≤ 0). If no gain → stop, document the negative result (like the
EmbeddingGemma A/B that was measured and rejected), don't ship.

## Phase 1 — native: expose `initial_prompt`

- Add `prompt: String` to `nativeTranscribe(...)` and the JNI C++ binding; set
  `params.initial_prompt` when non-empty. Rebuild the native lib.
- Thread an `initialPrompt: String = ""` param through `WhisperEngine.transcribe()` (`:79`) and
  `RecordingService.transcribe()` (`:196`).
- Note the constraint: initial_prompt is a soft bias with a bounded token budget — a long vocab list
  must be capped/truncated (and possibly rotated). The harness informs how many terms actually help
  before it hurts.

## Phase 2 — prefs + settings editor + wire-in

- **Storage:** `AppPreferences` is SharedPreferences; a string-set pref matches the existing
  `importedAudio` set pattern. Add `customVocabulary: Set<String>` with a `KEY_VOCABULARY` constant.
- **Editor UI:** new `GlassCard` section in `ui/SettingsScreen.kt` (alongside the audio section) —
  chips for current terms (tap to remove) + a `GlassTextField` with an Add button, mirroring the
  tag-input pattern already in note detail (`ui/NotesScreens.kt` add-tag field). Optional bulk paste.
- **Wire-in:** build the prompt from `customVocabulary` (capped) and pass it as `initialPrompt` at
  the two `transcribe(...)` call sites (final pass `:230`, append `:275`). Live preview can skip it
  (cheap path) or include it — decide from harness cost data.

## Validation / success criteria

- Phase 0 harness: WER + term-recall computed correctly (unit-tested metrics), runs on device,
  reproduces a stable baseline number on the same clip across runs.
- Gate metric (term-recall gain) met before any Phase 2 UI is built.
- Post-ship sanity: a note dictated with "Aoife", "Kubernetes", "ThoughtPocket" in the vocab list
  spells them right where the baseline didn't, with no new errors elsewhere.

## Risks / notes

- Native rebuild is the costly part — gated behind a proven win on purpose.
- initial_prompt can *hurt* (hallucinate the primed words into audio that didn't contain them) — the
  term-recall-up / WER-not-down gate is exactly what catches that.
- Fixture audio licensing/provenance — prefer self-recorded clips we can commit freely.

## Out of scope (deliberate non-adoption)

- Gmail / contacts import of vocabulary (Eloquent) — violates the no-account, on-device, privacy
  positioning. Manual list only.
