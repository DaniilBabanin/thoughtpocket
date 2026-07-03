# Changelog

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
