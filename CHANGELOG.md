# Changelog

## v0.1.0 — first release

First public build. Privacy-first, fully on-device voice notes.

- **Record** three ways: in-app FAB, home-screen widget, Quick Settings tile. Transcript-only — audio is deleted after transcription.
- **Transcription** via whisper.cpp (CPU); default Whisper Base English (60 MB), in-app model picker.
- **On-device AI (Gemma 4 / LiteRT-LM, GPU + CPU fallback):** auto-tagging, auto-Markdown formatting, tickable checklists, free-form interact commands with single-tap undo, Ask/Analyze with RAG retrieval, deterministic structured checklist queries, random-task picker.
- **Semantic search / relate / cluster** via on-device Gecko 110m embeddings (AI Edge RAG SDK).
- **In-app model download** from the project's Nextcloud — one-tap "Download all models" in Settings, no ADB/sideloading.
- **16 KB page-size alignment** for the native whisper lib (Play Store requirement).
- **CI/CD:** build + unit tests on push/PR; tagged releases build a signed APK + AAB with signing-cert pinning and dex witnesses.
