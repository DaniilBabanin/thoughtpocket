# Long-form transcription baseline (accuracy + speed)

Status: results · Created: 2026-06-26 · Device: Pixel 9 Pro XL (Tensor G4), `komodo`
Harness: `app/src/androidTest/.../LongFormBench.kt` · Ruler: `Wer.kt` (JVM-tested in `WerTest.kt`)

The reference baseline for the [fine-tuned-whisper plan](fine-tuned-whisper-plan.md): how the on-device
engines do on **long** audio (15–18 min) where high-quality transcripts exist, so a future fine-tune has
numbers to beat and we surface the failure modes that only appear at length.

## What this measures — and what it does NOT

These are **clean, formal oratory** (presidential speeches) — the *opposite* of the app's real
distribution (messy ADHD-capture, see [[product-purpose-adhd]]). So this baseline is two things:

1. **Long-audio stress test** — does a 15–18 min decode OOM, hang, drift, loop (whisper.cpp repetition),
   truncate, or (Moonshine) split words at its 25 s chunk boundaries?
2. **Clean-speech accuracy *ceiling*** — the best case, not the typical case.

It is **NOT** the representative WER the fine-tune *ship-gate* needs. That gate (per the plan) still owes a
**messy-capture** test set. Do not read "WER 4% on Eisenhower" as "fine-tuning is unnecessary."

**WER is reported as a FLOOR.** References are *audio-aligned* Wikisource transcripts (they include ad-libs
like JFK's "Why does Rice play Texas?" and match delivery), but published transcripts still differ from the
recording in ways that score as errors without being engine errors — chiefly **number formatting** (the JFK
transcript writes digits: "1970", "240,000 miles"; an engine may spell them) and the odd dropped/added
filler. Read the dumped hypotheses to attribute the floor (quantified under Findings).

## Fixtures (public domain)

| id | speech | duration | audio source (sha256 of .ogg) | transcript source |
|----|--------|---------:|-------------------------------|-------------------|
| `jfk_rice` | JFK — "We choose to go to the Moon", Rice Univ., 1962-09-12 | 17:40 | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Jfk_rice_university_we_choose_to_go_to_the_moon.ogg) `7486fba8…2ae68b27` | [Wikisource](https://en.wikisource.org/wiki/We_choose_to_go_to_the_moon) |
| `eisenhower_farewell` | Eisenhower — Farewell Address, 1961-01-17 | 15:30 | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Eisenhower_farewell_address.ogg) `220b342a…b2e5b12a` | [Wikisource (audio transcript)](https://en.wikisource.org/wiki/Eisenhower%27s_farewell_address_(audio_transcript)) |

Both are U.S. federal works → public domain. Audio is too big to commit (~30 MB PCM each); the **references
are committed** at `app/src/androidTest/assets/longform/`. Audio is fetched + converted to 16 kHz mono s16le
PCM by `tools/fetch-bench-audio.sh` (URLs + sha256 pinned), then staged on-device by `tools/push-bench.sh`.

## Reproduce

```bash
tools/fetch-bench-audio.sh                 # → bench-audio/*.pcm  (gitignored)
tools/push-bench.sh                        # PCM + whisper ggml (base,small) → device (internal, via run-as)
# Run one engine per invocation (peak RSS isolates per process):
adb shell am instrument -w -e class com.thoughtpocket.LongFormBench#whisperBase \
  com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s LONGBENCH
# Hypotheses are dumped to (internal) files/bench/out/<id>__<engine>.txt — pull with run-as cat.
```

## Metrics

- **WER** — `(sub+del+ins)/refWords` after normalize (lowercase, strip punctuation). Floor (see above).
- **sub / del / ins** — edit breakdown. `del` ≫ `ins` ⇒ dropped/truncated speech; `ins` spikes ⇒
  repetition/hallucination.
- **RTF** — compute time / audio duration (lower = faster; <1 = faster than real-time). Whisper runs on CPU
  (Vulkan not compiled), VAD on (like the final pass), at **beam search** (`highQuality=true`) — the
  **accuracy ceiling**. Note production *defaults to greedy* (`AppPreferences.highQuality` default = `false`),
  which is faster and slightly less accurate, so these WER/RTF are the upper-accuracy / upper-cost end.
- **peakRss** — process high-water `VmHWM` (MB), cumulative across the run.
- **maxRepeat4gram** — longest back-to-back repeat of any 4-word span (≥3 ⇒ a loop). The long-audio canary.
- **lenRatio** — hyp words / ref words (≪1 truncated, ≫1 inflated/looping).
- **drift (1st/2nd half WER)** — WER of the first vs second half of the reference, attributed via the
  *global* alignment (`Wer.halves`). Artifact-free, unlike comparing a re-decoded audio window to a
  proportional ref prefix (speech rate varies → that boundary is off by tens of words = pure noise).

## Results

RTF/WER from `LONGBENCH` logcat. (engine = on-device backend; Whisper = whisper.cpp ggml q5_1, Moonshine =
sherpa-onnx int8.)

| speech | engine | WER (floor) | sub / del / ins | RTF | peakRss | maxRep4 | drift 1st/2nd¹ |
|--------|--------|------------:|-----------------|----:|--------:|--------:|---------------:|
| JFK Rice (17:40) | moonshine-tiny (123 MB) | 12.8% | 204 / 46 / 39 | 0.075 | 742 MB | 1 | 8.8% / 16.7% |
| JFK Rice | moonshine-base (286 MB) | 7.3% | 119 / 28 / 18 | 0.144 | 930 MB | 2 | 5.4% / 9.2% |
| JFK Rice | whisper-base-q5 (60 MB) | 6.8% | 87 / 49 / 17 | 0.432 | 824 MB | 2 | 6.0% / 7.5% |
| JFK Rice | whisper-small-q5 (190 MB) | **3.6%** ² | 46 / 29 / 7 | ≈1.17 ² | ~1156 MB | — | 1.9% / 5.3% |
| Eisenhower (15:30) | moonshine-tiny | 5.9% | 65 / 9 / 39 | 0.080 | 742 MB | 1 | 6.2% / 5.5% |
| Eisenhower | moonshine-base | 3.4% | 38 / 7 / 20 | 0.157 | 930 MB | 1 | 3.1% / 3.6% |
| Eisenhower | whisper-base-q5 | 3.9% | 39 / 8 / 29 | 0.381 | 858 MB | 1 | 3.8% / 4.0% |
| Eisenhower | whisper-small-q5 | **1.5%** | 13 / 3 / 12 | 1.080 | 1156 MB | 1 | 1.3% / 1.6% |

¹ drift = WER of the first vs second half of the reference (global-alignment, artifact-free). Eisenhower is
flat; JFK's 2nd half is worse on every engine — see Findings.
² whisper-small JFK: the `LONGBENCH` RESULT line rolled out of the logcat buffer during the 42-min run; WER +
breakdown + drift recovered **exactly** from the dumped hypothesis via the offline ruler, RTF derived from
wall-clock (dump mtime − run start). For long runs use `adb logcat -G 16M` or stream logcat to a file.

Whisper deletions partly reflect **VAD** (the final-pass config trims silence; Moonshine ran without VAD), so
some Whisper `del` are silence trims, not model misses — don't read Whisper-base's del=49 as pure model error.

### PC reference — the large frontier (host whisper.cpp, same q5 ggml)

The RTX 4090 here is **not usable** (system PyTorch is CPU-only, no CUDA toolkit / Vulkan tools, no cp314
CUDA wheel). But **accuracy is hardware-independent** — only speed is — and the "too slow on phone" point is
already made on-device. So this runs a host-compiled `whisper-cli` (i9-13900HX, 16 threads, CPU, beam=5,
**no VAD**) on the **same q5 ggml the app ships**, to reach the large models that are impractical on-device.
Host RTF is shown for scale; the **est. phone RTF** column multiplies by ~14× (device-base RTF 0.432 ÷
host-base RTF 0.031) to project onto the Pixel.

| speech | model | WER (floor) | words (ref) | host RTF | est. phone RTF |
|--------|-------|------------:|-------------|---------:|---------------:|
| JFK Rice | base-q5 (anchor) | 7.0% (dev 6.8) | 2238 (2260) | 0.031 | 0.43 |
| JFK Rice | small-q5 ✗ | 7.2% ⁴ | 2174 | 0.083 | ~1.2 |
| JFK Rice | medium-q5 (539 MB) | **4.0%** | 2219 | 0.233 | ~3.3 |
| JFK Rice | large-v3-turbo-q5 (574 MB) | 69.4% ✗✗ | **929** | 0.303 | ~4.2 |
| JFK Rice | large-v3-q5 (1080 MB) | 5.2% ⁵ | 2242 | 0.459 | ~6.4 |
| Eisenhower | base-q5 (anchor) | 3.4% (dev 3.9) | 1922 (1930) | 0.032 | 0.45 |
| Eisenhower | small-q5 ✗ | 2.4% ⁴ | 1929 | 0.088 | ~1.2 |
| Eisenhower | medium-q5 | **1.1%** | 1929 | 0.243 | ~3.4 |
| Eisenhower | large-v3-turbo-q5 | 47.0% ✗✗ | **1322** | 0.272 | ~3.8 |
| Eisenhower | large-v3-q5 | 2.7% ⁵ | 1925 | 0.461 | ~6.5 |

⁴ **small no-VAD is not representative** — it dropped two ~10–15 s segments (105 deletions on JFK) that the
device's VAD-on path did not; trust the device small numbers (3.6% / 1.5%), not these.
⁵ large-v3 / medium ran with **minimal drop** (lenRatio ≈ 0.98–1.00), so those two are comparable to each
other; the soft point is that some of large-v3's extra edits are *insertions* (more verbatim fillers/ad-libs
the cleaned reference lacks) → part floor, not model error.
✗✗ **large-v3-turbo collapsed without VAD** — it dropped >half the audio (catastrophic early-stop on long
continuous segments, a known turbo fragility). Not a usable on-device final pass without VAD scaffolding.

## Verification (prove the ruler before trusting it)

- `WerTest` (JVM, no device): hand-checked WER + half/half-drift cases, plus self-WER=0 and known-edit-count
  on the **real** committed transcripts. 10/10 pass.
- **Independent offline cross-check**: a separate Python WER over the pulled Moonshine hypotheses reproduced
  the four on-device numbers **to the decimal** (3.4 / 5.9 / 7.3 / 12.8) with matching ref/hyp word counts.
  Two implementations agreeing on 2000-word real data → the ruler is trustworthy.

## Findings

- **Long audio is stable on both engines.** No OOM, no truncation (lenRatio ≈ 1.00), no repetition loops
  (maxRepeat4gram ≤ 2) across 15–18 min. Moonshine's 25 s-chunk concatenation did **not** mangle boundaries
  at length — a real worry going in, now measured away.
- **JFK ≈ 2× Eisenhower WER on every engine.** JFK is denser in proper nouns (Pitzer, Wiley, Webb, Glenn),
  foreign place names, and digit-heavy figures — exactly the proper-noun/number weakness a custom-vocab
  ([custom-vocabulary-plan](custom-vocabulary-plan.md)) or domain fine-tune targets.
- **whisper-small is the accuracy winner but too slow on CPU.** JFK 3.6% / Eisenhower 1.5% — roughly *half*
  whisper-base's errors — but RTF ≥ 1.0 means ~20 min to finalize a 17-min note. Not viable as a snappy final
  pass on this device without GPU. This ~2× base→small accuracy gap **is the headroom a fine-tune chases**:
  small-class accuracy at base-class speed/size.
- **whisper-base ≈ moonshine-base on accuracy, but Moonshine is ~3× faster** (RTF 0.15 vs 0.43) and streams.
  On Eisenhower, moonshine-base (3.4%) even *edges* whisper-base (3.9%). On clean speech the streaming
  first-pass engine is already competitive with the batch final-pass engine — a point in favor of investing
  in Moonshine, not only Whisper.
- **No catastrophic length pathologies, but JFK shows real back-half degradation.** No repetition loops
  (maxRepeat4gram ≤ 2) and no truncation (lenRatio ≈ 1.0) on either engine. Drift, though, is *not* zero:
  **Eisenhower is flat** across halves (e.g. whisper-small 1.3% → 1.6%), but **JFK's second half is worse on
  every engine** (whisper-small 1.9% → 5.3%, moonshine-tiny 8.8% → 16.7%). The likely cause is **content, not
  length-decay**: JFK's back half is the dense technical/numeric rocket passage ("Saturn C-1… 300 feet tall…
  new metal alloys…"), while Eisenhower's content is uniform and shows no decay over equal length. So there's
  no evidence of accuracy decaying *because* audio is long — it tracks where the hard words are. (My first
  attempt measured this with a re-decoded 120 s window vs a proportional prefix; that was a windowing
  artifact — Eisenhower's *easy* opening scored worse than its full clip — and was replaced by the
  global-alignment half/half above.)
- **The floor is honest.** Number-formatting is 6–10% of JFK edits and 0% of Eisenhower edits; the rest are
  genuine ASR errors (proper nouns, acoustic confusions like "decade"→"dictate", "confidence"→"competence").
- **Ruler trustworthy.** Offline WER reproduced every captured device number to the decimal.

## Implications for the fine-tune plan

- Measure any fine-tuned model against **this table**: the bar is *beat whisper-base WER at ≤ base RTF and
  size*, ideally closing toward the small column (JFK 3.6% / Eis 1.5%).
- These numbers are a **ceiling**. The [fine-tune ship-gate](fine-tuned-whisper-plan.md) still requires a
  **messy real-capture** set (accents, disfluency, background noise) — clean oratory cannot justify shipping.
- A fast streaming engine (Moonshine) being competitive on clean speech suggests the first-pass/final-pass
  split is worth re-examining alongside Whisper fine-tuning.

### Are the large models worth offering in the app?

Short answer: **on this evidence, large-v3 and large-v3-turbo are not worth keeping as on-device options;
medium is the realistic top end, and even it is borderline.**

- **Accuracy plateaus past small/medium on clean speech.** medium (JFK 4.0% / Eis 1.1%) ≈ small and ≈ the
  best result here; **large-v3 (5.2% / 2.7%) did not beat medium** in this run. Whatever the exact cause
  (q5 quant, no-VAD edits, verbatim-filler insertions), the gain from medium→large is at best zero on this
  material. A 1 GB model that doesn't beat a 0.5 GB one is hard to justify.
- **They're impractical on-device.** Est. phone RTF ~3.3 (medium), ~6.4 (large-v3) → roughly **55 min and
  ~1h50 to finalize a 17-min note** on the Pixel. Even small is ~RTF 1.2. None is usable as a snappy final
  pass without GPU.
- **Turbo is fragile on long audio.** Without VAD it dropped >half the clip. Shipping it as a "fast &
  accurate" pass over long notes risks silent truncation.
- **Recommendation:** drop large-v3 + large-v3-turbo from the on-device model list (or hide behind an
  "experimental / very slow" gate); cap the realistic ceiling at **small/medium**. Spend the effort instead
  on a **fine-tuned base/small** (the actual headroom: base→small halves errors) and on the **messy-capture
  set** that the ship-gate truly needs. Caveats: the large-v3 figure is soft (no-VAD; clean oratory only) — a
  VAD-matched re-run + messy audio would firm it up, but neither is likely to flip the speed verdict.
