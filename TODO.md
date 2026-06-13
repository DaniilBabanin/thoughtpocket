# SoundScript — deferred TODOs

## Fully-bundled build variant (Gecko model in-APK)
Gecko embedder model (~110MB) currently downloads on first run. Add an offline build
flavor that bundles `Gecko_256_quant.tflite` + `sentencepiece.model` in assets so the app
works with zero network on first launch (at the cost of a big APK). Also: try Gecko on GPU
(currently CPU, ~315ms/note) for faster backfill.



## 16 KB page-size native alignment (Play Store blocker, eventually)
Debug dialog flags these `.so` libs as not 16 KB-aligned:
`libLiteRt.so`, `liblitertlm_jni.so`, `libLiteRtClGlAccelerator.so`,
`libsoundscript.so` (whisper, "LOAD segment not aligned"),
`libc++_shared.so`, `libomp.so`, `libandroidx.graphics.path.so`.
Harmless for local testing; Google Play will require 16 KB alignment.
Fix: build with NDK r28+ (defaults to 16 KB) and/or linker flag
`-Wl,-z,max-page-size=16384` for our own CMake target; bump the litertlm
dependency to a 16 KB-aligned release when available.

## Whisper on LiteRT / NPU (investigate, maybe faster)
We run whisper.cpp on CPU (Vulkan disabled — unstable on this Mali). A TFLite
(LiteRT) whisper could offload the encoder to GPU/NPU. See notes in chat:
likely encoder-only speedup, decoder stays autoregressive; real porting +
re-validation cost. Revisit only if transcription latency becomes a bottleneck.
