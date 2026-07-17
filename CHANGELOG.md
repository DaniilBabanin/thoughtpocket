# Changelog

## v0.2.4

- **Instant record stop:** stopping no longer waits out the in-flight live-preview decode (up to ~3 s on Whisper) — the orb, QS tile and widget react immediately and the next recording can start right away.
- A stop tapped a split second after start is never lost; the QS tile timer no longer carries the previous recording's time over on an instant re-record; a stale widget tap after a process kill cleans itself up instead of leaving a service running.
- Closed a rare window where the last words of a note could be dropped when stopping mid-decode with the final pass off.

## v0.2.3

- **APK ~54% smaller:** release builds are arm64-only (x86_64 only ever served desktop emulators) and drop unused RAG SDK JNI libs.

## v0.2.2-pre

- **Ask your notes covers everything:** scopes over one context window are split into batches, answered per batch and merged (fit-or-split harness), with live part-i-of-n progress.
- **Coder:** per-task all-notes access grant; note detail links to the coding hub.
- Inset fixes: coding screen no longer starts under the status bar; record orb no longer covers the last button in note detail.

## v0.2.1-pre

- **Hi-res audio imports:** 96 kHz / 24-bit and 32-bit-int WAVs decode correctly (were read as garbage); decoding streams with bounded memory instead of OOM-ing on very large files.
- **Visible transcription queue:** each queued clip is an optimistic card under Recent (label, progress, transcript-so-far); long-press cancels — even mid-transcription, via a new whisper abort callback.
- **Whisper language auto-detect:** a blank language setting now auto-detects (it silently forced English before, even on multilingual models).

## v0.2.0

Production release of the "Code this" line — v0.1.10-pre through v0.1.13-pre below.

## v0.1.13-pre

- **Coder hardening:** safe engine teardown, mid-prefill cancel, 8k context, runtime guards.
- Licenses/README: credit llama.cpp, Chaquopy, embedded CPython, Ornith.

## v0.1.12-pre

- **Coding tasks persist per note:** list, iterate, delete, revert; meta tasks can read all notes.
- **Coder prompt contract:** honesty clause, attempt log kept on failure, generation cap raised to 1300 after diagnosing truncated replies.

## v0.1.11-pre

- **"Code this" (experimental):** on-device coding agent — Ornith 9B GGUF on llama.cpp (JNI bridge with per-token cancellation), out-of-process Python runner via Chaquopy, agentic harness loop, note-detail entry + run/results screen + Settings card, in-app Ornith download or BYO GGUF import. Pixel decode fixed 0.16 → 4.25 tok/s (SIMD flags, threads).
- **Universal release APK** for now — Chaquopy has no ABI-splits support (slimmed back down in v0.2.3).

## v0.1.10-pre

- **Import audio files as notes:** picker in list and note detail, plus an Android share-sheet target.

## v0.1.9-pre

- **Reliability: GPU-crash recovery works again** — if an on-device Gemma run crashes the GPU driver, the app once more falls back to CPU on the next launch (this recovery had silently stopped working).
- **Lighter, faster note processing:** long recordings finalize without loading the whole clip into memory, and a burst of recordings no longer reloads the multi-GB AI models between each note — Markdown formatting runs once as a batch when the queue drains.
- **Snappier search and note screens:** semantic-search scoring and Markdown rendering are cached instead of recomputed on every UI update.

## v0.1.8-pre

- **Share and copy a note:** share button on the note screen (Android share sheet) plus a copy-to-clipboard button in both views — each sends what's on screen, the formatted body or the raw transcript.
- **Fix broken Moonshine downloads:** the server gzipped `tokens.txt`, which dropped the Content-Length the download-completeness guard requires; downloads now request uncompressed bytes.

## v0.1.7-pre

- **Reliability: recordings can no longer be silently lost** — appending to a note that was deleted mid-transcription now saves a new note instead of discarding the audio; a failed final decode keeps the recording for retry; failed audio imports are retried on the next scan.
- **Fix a crash on Android 12/13** when extracting a calendar reminder from a note.
- **Smaller downloads:** releases now ship per-device APKs — arm64 phones no longer download ~35 MB of emulator-only code (install `app-arm64-v8a-release.apk`).
- Interrupted model downloads are detected and rejected instead of being treated as complete.

## v0.1.6

- **Two-pass transcription:** instant streaming first pass (Moonshine via sherpa-onnx) + quality Whisper final pass, each with its own on/off toggle and model in Settings; Moonshine models download in-app from the project's Nextcloud.
- **One-tap transform presets** on a note (Key Points / Formal / Short / Long) — pre-fill the Interact command box and rewrite the body, undoable like any command.

## v0.1.5-pre

- **AI "set title" command** for notes via the Interact box.

## v0.1.4-pre

- **Adaptive tablet layout**; random home-screen quips.

## v0.1.3-pre

- **Opt-in two-way notes ↔ folder sync** (one Markdown file per note, Nextcloud-friendly): tombstoned deletes, last-write-wins conflicts; a vanished file never deletes a note.

## v0.1.2-pre

- **Stop turning silence into notes:** non-speech-token suppression + Silero VAD + transcription-artifact stripping.
- Licenses screen: attribute whisper.cpp/ggml and Silero VAD (MIT); collapse full license texts.

## v0.1.1-pre

- **Recover recordings killed mid-transcription**; transcription and model-download failures are surfaced instead of silently dropped.
- **Ask:** time-window precision for date-anchored questions + a loading state.
- Dependency updates (Compose BOM 2026.05, protobuf-javalite 4.35, AndroidX test runner 1.7).

## v0.1.0 — first release

First public build. Privacy-first, fully on-device voice notes.

- **Record** three ways: in-app FAB, home-screen widget, Quick Settings tile. Transcript-only — audio is deleted after transcription.
- **Transcription** via whisper.cpp (CPU); default Whisper Base English (60 MB), in-app model picker.
- **On-device AI (Gemma 4 / LiteRT-LM, GPU + CPU fallback):** auto-tagging, auto-Markdown formatting, tickable checklists, free-form interact commands with single-tap undo, Ask/Analyze with RAG retrieval, deterministic structured checklist queries, random-task picker.
- **Semantic search / relate / cluster** via on-device Gecko 110m embeddings (AI Edge RAG SDK).
- **In-app model download** from the project's Nextcloud — one-tap "Download all models" in Settings, no ADB/sideloading.
- **16 KB page-size alignment** for the native whisper lib (Play Store requirement).
- **CI/CD:** build + unit tests on push/PR; tagged releases build a signed APK + AAB with signing-cert pinning and dex witnesses.
