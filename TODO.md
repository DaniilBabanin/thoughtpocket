# SoundScript — deferred TODOs

## UX / UI polish (backlog)
General pass once features settle: visual hierarchy, empty/loading/error states, the Interact
section + Undo affordance, markdown rendering (spacing, nested lists), the random-task dialog,
Settings grouping, theming/typography, accessibility (content descriptions, touch targets),
animations/transitions. Collect concrete nits here as they come up.


## Fully-bundled / offline build (Gecko in-APK; Gemma stays download-only)
Investigated 2026-06-14: Gemma 3n E2B/E4B **cannot** be bundled — each model (>2 GB) blows past
Play Asset Delivery's 2 GB total-asset-pack cap, and the weights are gated under the custom Gemma
Terms (redistribution carries NOTICE/EULA duties). Keep Gemma download-on-first-run.
Gecko (`Gecko_256_quant.tflite` ~109 MB + `sentencepiece.model`, non-gated) **can** be bundled —
install-time asset pack or `assets/` + `noCompress`. Gives a true offline search + transcription
build; AI tagging still needs the one-time Gemma download. (Gecko-on-GPU tested: no speedup, CPU.)

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
