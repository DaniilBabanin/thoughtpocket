# SoundScript — deferred TODOs

## UX / UI polish (backlog)
General pass once features settle: visual hierarchy, empty/loading/error states, the Interact
section + Undo affordance, markdown rendering (spacing, nested lists), the random-task dialog,
Settings grouping, theming/typography, accessibility (content descriptions, touch targets),
animations/transitions. Collect concrete nits here as they come up.


## Model delivery (in-app download from Nextcloud) — DONE 2026-06-14
Gemma 4 E2B/E4B (`.litertlm`) + Gecko + whisper are downloaded in-app from the project's Nextcloud
(direct, no-auth public DAV links; `LlmEngine.Downloadable` + repointed `Embedder`). No more
adb-only. Download UI lives in Settings, incl. a one-tap **"Download all models"** button
(Base-English whisper 60 MB + Gemma E2B 2.59 GB + Gemma E4B 3.66 GB + Gecko 115 MB ≈ 6.4 GB; skips
any already installed). A missing model shows a toast pointing to Settings (`llmReadyOrToast`).
No resume (Settings-initiated, user-watched). License:
**Gemma 4 is Apache 2.0** (the gated custom Gemma Terms were Gemma 3n, not 4).

**Offline bundling: dropped.** Now that models are hosted on Nextcloud, we keep the APK small and
download everything on demand — no asset-pack bundling of Gecko/Gemma. (Gecko-on-GPU tested earlier:
no speedup, CPU.)

## 16 KB page-size native alignment (Play Store blocker) — RESOLVED 2026-06-14
Only `libsoundscript.so` (our whisper build) was misaligned (4 KB LOAD segments); every dependency
lib (litertlm 0.13.1, RAG SDK 0.3.0, NDK, androidx) was already 16 KB — no dep bumps needed.
Fixed by adding `-Wl,-z,max-page-size=16384` + `-Wl,-z,common-page-size=16384` to our CMake target
(see app/CMakeLists.txt); verified all 4 LOAD segments are now 0x4000 via `llvm-readelf -l`.

## Whisper on LiteRT / NPU (investigate, maybe faster)
We run whisper.cpp on CPU (Vulkan disabled — unstable on this Mali). A TFLite
(LiteRT) whisper could offload the encoder to GPU/NPU. See notes in chat:
likely encoder-only speedup, decoder stays autoregressive; real porting +
re-validation cost. Revisit only if transcription latency becomes a bottleneck.
