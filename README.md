# ThoughtPocket

Privacy-first voice notes for Android. Record, transcribe, tag, and search your notes **entirely on-device** — no cloud, no account, no audio ever leaving the phone. Recordings are transcribed locally with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and the audio is deleted immediately; only the transcript is kept. On-device Gemma 4 (via LiteRT-LM) adds AI tagging, Markdown formatting, and question-answering over your notes, with semantic search powered by on-device Gecko embeddings.

Everything runs offline after a one-time model download.

---

## 1. Install

Download `app-release.apk` from [Releases](../../releases), enable "Install from unknown sources," then:

```bash
adb install app-release.apk
```

Requires Android 12+ (minSdk 31), arm64. The APK also ships an `x86_64` slice so it runs on a desktop emulator.

---

## 2. First-time setup

Open ThoughtPocket → **Settings** → **Download all models** (~6.4 GB, once, over Wi-Fi):

| Model | Size | Used for |
|-------|------|----------|
| Whisper Base (English) | 60 MB | speech → text |
| Gemma 4 E2B | 2.59 GB | fast AI tagging / interact |
| Gemma 4 E4B | 3.66 GB | Markdown formatting / Q&A |
| Gecko 110m | 115 MB | semantic search / relate / cluster |

Models are downloaded in-app — **no ADB or sideloading needed**. You can also grab them individually, or import your own `.litertlm` / whisper `.bin` files from the same screen. Grant microphone + notification permissions on first record. If an AI feature is tapped before its model is present, a toast points you back to Settings.

---

## 3. Record a note

Three entry points, all producing a transcribed note:

- **In-app** — the record FAB on the notes list.
- **Home-screen widget** — tap to record, tap to stop.
- **Quick Settings tile** — drag the ThoughtPocket tile into Quick Settings, tap to record/stop.

Speak, stop, and the note appears transcribed (audio is discarded). New notes are auto-tagged and auto-formatted as Markdown if those options are on (Settings).

---

## 4. AI features (on-device)

- **Auto-tag** — topical tags suggested right after transcription.
- **Auto-Markdown** — prose is reflowed into lists/checklists; the raw transcript is kept immutable underneath.
- **Checklists** — tap a `- [ ]` to tick it; toggles persist.
- **Interact** — free-form commands (text or voice) like "check off milk" / "add eggs"; an LLM parses the intent, then the change is applied deterministically so a model slip can't corrupt the note. Single-tap **Undo** reverts the last AI change.
- **Ask / Analyze** — question-answering over your notes with RAG retrieval (query embedding + time filter + top-K relevant notes), plus deterministic structured checklist queries ("what did I buy last week", "what do I still need").
- **Random task** — the dice icon picks a random open `- [ ]` to do next.

All inference is local: Gemma runs on the GPU with a CPU fallback; whisper runs on the CPU.

---

## 5. Building

Local builds need **JDK 17** and the Android SDK with NDK `27.0.12077973` + CMake `3.22.1` (the first build pulls whisper.cpp v1.8.4 via CMake FetchContent, so it needs network and is slow):

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleRelease        # signed release APK (see signing below)
```

The androidTest suites (`ScaleTest`, `ComplexQueryTest`, `InteractSpike`, …) exercise the on-device models and need a real GPU device plus the multi-GB models, so they're run manually on hardware, not in CI.

### CI / release

- **`ci.yml`** — builds the debug APK and runs the JVM unit tests on every push/PR to `main`/`master`.
- **`release.yml`** — on a `v*` tag push, builds the signed release APK + AAB, verifies the signing-cert fingerprint against `tools/release/expected-signing-cert.sha256`, emits `classes*.dex` SHA-256 witnesses, and creates a GitHub Release with the artifacts attached. A `-pre.N` tag suffix marks it a pre-release.

Cut a release by bumping `versionCode`/`versionName` in `app/build.gradle.kts`, then:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

### Signing

The release keystore (`app/release.keystore`) is committed on purpose (password `whispershare`, documented in `.gitignore`) so CI and local builds sign **identically with no secrets**. The build is configured to sign release artifacts automatically when the keystore is present.

> ⚠️ Because the keystore is public, anyone can re-sign a modified APK with the same certificate. The signature proves only the *official CI build's* provenance — not anti-tamper. The trust anchor is the GitHub Releases page itself; compare the published `dex` hashes if you sideload from anywhere else.

---

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · Coroutines/Flow · whisper.cpp (NDK/CMake, CPU) · [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) `0.13.1` (Gemma 4) · AI Edge RAG SDK `0.3.0` (Gecko embeddings).

The scaffold reuses the hard parts of two sibling projects: whisper.cpp/JNI wiring from WhisperShare and the recording service / widget / tile from [VoiceDrop](https://github.com/) — see [`TODO.md`](TODO.md) for roadmap and deferred work.

---

## License

[Apache 2.0](LICENSE). Gemma 4 is also Apache 2.0, so its weights are re-hosted for in-app download.
