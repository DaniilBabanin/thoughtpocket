# ThoughtPocket

Privacy-first Android voice-notes app: record → Whisper transcribes on-device (audio then deleted) → on-device Gemma tags/formats/answers, Gecko embeddings power semantic search. No account, no cloud, works offline after a one-time model download. Single Gradle module `app`, namespace `com.thoughtpocket`, Jetpack Compose UI.

## Build & run

- **JDK 17 is required.** The default `java` on this machine is a JRE-only 25 and will fail the build. Prefix Gradle with:
  ```bash
  JAVA_HOME=/home/db/jdk/jdk-17.0.13+11 ./gradlew <task>
  ```
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Install on device: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (package `com.thoughtpocket`)
- `minSdk 31`, `targetSdk 35`, arm64 + x86_64 (emulator). Versions/SDK/NDK/CMake pins live in `app/build.gradle.kts` (no `libs.versions.toml`). whisper.cpp is built from source via CMake/NDK (FetchContent), so the pinned NDK + CMake must be installed.

## Tests

- **JVM unit tests (`app/src/test/`)** — pure logic, run anywhere: `./gradlew testDebugUnitTest`. This is the fast feedback loop (e.g. `MarkdownToggleTest` covers the `MarkdownOps` pure functions).
- **Instrumented (`app/src/androidTest/`)** — scale/RAG/Interact/perf spikes; need an **on-device GPU + the multi-GB models**. Run manually on hardware, not in CI.

## Entrypoints

- `ThoughtPocketApp.kt` — Application; loads the native `thoughtpocket` lib (whisper.cpp JNI), GPU-crash recovery, starts background notes↔folder sync.
- `ui/MainActivity.kt` — launcher activity (`.ui.MainActivity`), Compose root, permission handling.
- `service/RecordingService.kt` — foreground mic service: streams 16 kHz PCM, transcribes, saves + enriches the note (tags/markdown/title). Also handles append-to-note and file import.
- `service/DownloadService.kt` — foreground service for one-time model downloads.
- Manifest: `app/src/main/AndroidManifest.xml` (also `PermissionActivity`, `RecordTileService`, `widget/RecordWidget`).

## Package layout (`app/src/main/kotlin/com/thoughtpocket/`)

- `ai/` — on-device inference: `LlmEngine` (Gemma via LiteRT-LM; also `TaggingEngine`/`TitleEngine`/`NotesAnalysis`), `MarkdownEngine`, `InteractEngine` (+ `MarkdownOps.kt` pure edit ops), `ReminderEngine`, `Embedder` (Gecko).
- `audio/` — `MicRecorder`, `AudioFiles`, `Pcm`.
- `data/` — Room: `Note` entity + `NoteDao` + `NotesDb` + `Converters` (all in `data/Note.kt`).
- `service/` — foreground services, QS tile, `RecordState` (observable).
- `ui/` — Compose screens (`NotesScreens.kt`, `SettingsScreen`, `AnalyzeScreen`, `MarkdownView`) + `theme/` (the "Reach" glass design system, `theme/Reach.kt`).
- `widget/` — home-screen record widget.
- Root `*.kt` — `ModelManager`, `WhisperEngine`, `AppPreferences`, notes↔folder sync (`NotesFolderSync`/`NotesFolderIo`/`NoteFile`), `Export`.

## Architecture conventions

- **Hybrid LLM → deterministic apply.** An LLM parses a free-form command into a structured intent, then the app applies it with **pure, testable** functions so a bad model guess can't corrupt data. Canonical example: `InteractEngine.interpret()` → `InteractOp` → `MarkdownOps`/UI apply, with single-tap undo via a note snapshot. Keep new note-editing features on this pattern — put the deterministic transform in `MarkdownOps.kt` with a JVM unit test.
- **The Room DB is the source of truth.** Folder sync, files, and UI buffers reconcile to it.
- **Observable state via Flow/StateFlow** (e.g. `RecordState.status`, `ModelDownloads`); UI observes passively.
- Markdown body never carries a title heading — the note **title is a first-class `Note.title` field** (shown separately in the list card and detail screen).
- Style: match surrounding code, surgical diffs, comment the *why* not the *what*.

## On-device models

- Built-in set (`ModelManager.kt`): Whisper Base 60 MB, Gemma 4 **E2B** 2.59 GB (tagging/interact/title), Gemma 4 **E4B** 3.66 GB (markdown/Q&A), Gecko 110m 115 MB (search). End users download in-app (Settings → "Download all models") from HuggingFace + a no-auth Nextcloud DAV link.
- Local dev cache lives in gitignored `models/` (`models/llm/*.litertlm`, `models/gecko/*`). Push to a connected device with `tools/push-models.sh [adb-serial]`; pull Gemma from the Google AI Edge Gallery app with `tools/copy-edge-model.sh <substr> [serial]`.
- Test devices: Pixel 9 Pro XL (USB), Tab S11 (wireless).

## CI & release

- `.github/workflows/ci.yml` — fires on every push to `main` (and PRs): builds the **debug APK** + runs JVM unit tests. Output is an **Actions artifact** (`thoughtpocket-debug-<sha>`), *not* a GitHub Release.
- `.github/workflows/release.yml` — fires **only on a pushed `v[0-9]*.*.*` tag**: builds the signed release APK + AAB and publishes a GitHub Release (a `-` in the tag, e.g. `-pre`, marks it prerelease). Signed by the committed public `app/release.keystore`.
- **To cut a `-pre` build:** bump `versionCode` + `versionName` in `app/build.gradle.kts` as a standalone `Bump to X (versionCode N)` commit, tag `vX` (e.g. `v0.1.5-pre`), then push the commit **and** the tag. Versioning is patch-increment with a `-pre` suffix.
