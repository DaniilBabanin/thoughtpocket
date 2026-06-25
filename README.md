# ThoughtPocket

ThoughtPocket is a voice-notes app for Android that runs entirely on your phone, with no account, no cloud, and nothing leaving the device. Tap record, say what's on your mind, and it's down before it slips — the words appear live as you speak. Transcription runs in two on-device passes you can mix and match: an instant streaming pass that captions while you record, and a higher-accuracy Whisper pass that finalizes the note on stop. The audio is deleted either way, so what you keep is plain text. Later, instead of scrolling back through everything you've saved, you ask a question and the relevant notes come back. On-device Gemma handles the tagging, formatting, and answering; Gecko embeddings power search. It all works offline after a one-time model download.

## Install

Download `app-release.apk` from [Releases](../../releases), enable "Install from unknown sources," then:

```bash
adb install app-release.apk
```

Needs Android 12+ (minSdk 31), arm64. The APK also ships an x86_64 slice for desktop emulators.

## First-time setup

Open Settings and tap **Download all models** (about 6.4 GB, once, over Wi-Fi):

| Model | Size | Used for |
|-------|------|----------|
| Whisper Base (English) | 60 MB | speech to text |
| Gemma 4 E2B | 2.59 GB | tagging, interact |
| Gemma 4 E4B | 3.66 GB | Markdown, Q&A |
| Gecko 110m | 115 MB | semantic search |

For live transcription while you record, add an instant model from **Settings → Instant transcription**: Moonshine Base (286 MB) or Tiny (123 MB), English-only. It's optional and separate from the download above — without it, the live preview falls back to windowed Whisper.

Models download in-app, so there's no ADB or sideloading. You can grab them one at a time or import your own `.litertlm` / whisper `.bin` files from the same screen. Grant microphone and notification permissions on first record.

## Record

Three ways to capture a note, all transcribed:

- The record button on the notes list.
- A home-screen widget: tap to record, tap to stop.
- A Quick Settings tile: drag it in, tap to record or stop.

## Transcription

Two passes, each toggled on or off with its own model in **Settings**:

- **Instant (first pass)** streams words into the note while you record — Moonshine (sub-second, English) or windowed Whisper.
- **Final (quality)** re-transcribes the full audio on stop with Whisper, and that result becomes the saved note.

Run both (watch it live, keep the best), instant-only (fastest — the live transcript is the note), or final-only (no live preview, transcribe on stop). At least one pass is always on.

## AI features (on-device)

- **Auto-tag** suggests topical tags right after transcription.
- **Auto-Markdown** turns prose into lists and checklists, with the raw transcript kept underneath, untouched. Tap a `- [ ]` to tick it.
- **Interact** takes free-form commands by text or voice, like "check off milk" or "add eggs." A model reads the intent, then the change is applied deterministically, so a bad guess can't corrupt the note. One tap undoes the last AI change.
- **Ask** answers questions across your notes with RAG retrieval (query embedding, time filter, top-K notes), plus exact checklist queries like "what do I still need."
- **Random task** picks a random open `- [ ]` for when you don't know where to start.

## Sync (optional)

Turn on **Settings → Sync notes to a folder** and pick a folder: each note is mirrored as a plain Markdown file (the formatted body plus the original transcript), and files dropped into that folder are picked up as notes. Point your own Nextcloud — or any folder-sync tool — at that folder to back up your notes and sync them across devices. It's two-way, off by default, and your notes never leave the device unless you turn it on. Deletes move the file to a `.trash` subfolder, so they're recoverable.

## Building

JDK 17 and the Android SDK with NDK `27.0.12077973` and CMake `3.22.1`; the first build pulls whisper.cpp v1.8.4 over the network, so it's slow. `./gradlew assembleDebug` builds, `testDebugUnitTest` runs the JVM tests, `assembleRelease` produces a signed APK. CI builds and tests on every push to `main`/`master` and publishes a signed release on a `v*` tag; the androidTest suites need a real GPU device and the big models, so they run by hand. The keystore is committed on purpose (password `whispershare`) so builds sign identically with no secrets. Being public, the signature only proves an APK came from official CI, not that it's untampered, so verify the published `dex` hashes if you sideload from elsewhere.

## Tech and license

Kotlin, Jetpack Compose (Material 3), Room, Coroutines/Flow, [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT, incl. ggml — NDK/CMake, CPU) with bundled [Silero VAD](https://github.com/snakers4/silero-vad) (MIT) to skip silent stretches, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) running [Moonshine](https://github.com/usefulsensors/moonshine) (MIT, English models) on [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT) for the instant first pass, [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) `0.13.1` for Gemma (GPU with a CPU fallback), AI Edge RAG SDK `0.3.0` for Gecko. The app is licensed [Apache 2.0](LICENSE); Gemma 4 (Apache 2.0) and the Moonshine models (MIT) are re-hosted for in-app download.
