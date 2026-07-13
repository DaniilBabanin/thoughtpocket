#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <exception>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Last native error stash — Kotlin polls this after any failure.
// Most useful case: Vulkan vk::DeviceLostError (Mali drivers).
std::string g_last_error;

// User-cancel for a queued-clip transcription (llama_jni convention): cleared
// on entry of every whisper_full run, set by nativeCancelTranscribe, checked
// by ggml's abort callback so it lands mid-decode. Whisper calls are
// serialized on the Kotlin side, so one global flag is unambiguous.
std::atomic<bool> g_cancel_transcribe{false};

struct CallbackCtx {
    JNIEnv  *env;
    jobject  callback;
    jmethodID seg_method;
    jmethodID prog_method;
};

void cb_new_segment(struct whisper_context *ctx, struct whisper_state * /*state*/,
                    int n_new, void *user_data) {
    auto *cbctx = static_cast<CallbackCtx *>(user_data);
    if (cbctx == nullptr || cbctx->callback == nullptr) return;
    int n_segments = whisper_full_n_segments(ctx);
    int start = n_segments - n_new;
    if (start < 0) start = 0;
    for (int i = start; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        jstring jtext = cbctx->env->NewStringUTF(text);
        cbctx->env->CallVoidMethod(cbctx->callback, cbctx->seg_method, jtext);
        if (cbctx->env->ExceptionCheck()) {
            cbctx->env->ExceptionDescribe();
            cbctx->env->ExceptionClear();
        }
        cbctx->env->DeleteLocalRef(jtext);
    }
}

void cb_progress(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                 int progress, void *user_data) {
    auto *cbctx = static_cast<CallbackCtx *>(user_data);
    if (cbctx == nullptr || cbctx->callback == nullptr) return;
    cbctx->env->CallVoidMethod(cbctx->callback, cbctx->prog_method, static_cast<jint>(progress));
    if (cbctx->env->ExceptionCheck()) {
        cbctx->env->ExceptionDescribe();
        cbctx->env->ExceptionClear();
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeInitContext(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jboolean useGpu) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
#ifdef WHISPERSHARE_VULKAN
    cparams.use_gpu = useGpu;
    if (useGpu) {
        // Mali-G715 / Pixel 9 hits ggml_abort at ggml-vulkan.cpp:6452
        // (descriptor_set_idx exceeds descriptor_sets.size()) when the fused
        // add/multi_add code paths are active. Disable them so pre-flight and
        // runtime descriptor-set counts agree. Pass-through if user explicitly
        // sets these env vars themselves (overwrite=0).
        setenv("GGML_VK_DISABLE_FUSION",     "1", 0);
        setenv("GGML_VK_DISABLE_MULTI_ADD",  "1", 0);
    }
#else
    cparams.use_gpu = false;
#endif
    cparams.flash_attn = false;

    LOGI("Loading model: %s (gpu=%d)", path, cparams.use_gpu);
    whisper_context *ctx = nullptr;
    try {
        ctx = whisper_init_from_file_with_params(path, cparams);
    } catch (const std::exception &e) {
        g_last_error = std::string("init failed: ") + e.what();
        LOGE("%s", g_last_error.c_str());
    } catch (...) {
        g_last_error = "init failed: unknown native exception";
        LOGE("%s", g_last_error.c_str());
    }
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    g_last_error.clear();
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeFreeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return;
    try {
        whisper_free(ctx);
    } catch (const std::exception &e) {
        LOGW("whisper_free threw: %s", e.what());
    } catch (...) {
        LOGW("whisper_free threw unknown exception");
    }
}

// Shared whisper_full run for the array and file entry points — params, VAD, callbacks, error stash
// must stay identical between the two, so they live here once.
static jstring transcribe_pcm(
        JNIEnv *env,
        whisper_context *ctx,
        const float *samples,
        jsize n,
        jstring language,
        jboolean translate,
        jint nThreads,
        jboolean useBeam,
        jobject callback,
        jstring vadModelPath) {

    g_cancel_transcribe.store(false);

    whisper_full_params params = whisper_full_default_params(
            useBeam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.abort_callback = [](void *) { return g_cancel_transcribe.load(); };
    params.print_progress  = false;
    params.print_special   = false;
    params.print_realtime  = false;
    params.print_timestamps = false;
    params.translate       = translate;
    params.n_threads       = nThreads;
    params.suppress_blank  = true;
    params.suppress_nst    = true;   // drop non-speech tokens ([MUSIC], [BLANK_AUDIO]) at the source
    params.no_context      = true;
    params.single_segment  = false;
    if (useBeam) {
        params.beam_search.beam_size = 5;
    }

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang && lang[0] != '\0') params.language = lang;
    }

    // VAD (Silero): skip silent stretches so thinking-pauses aren't transcribed as [BLANK_AUDIO]/[MUSIC]
    // (and aren't wasted compute). Tuned to KEEP speech (low threshold + padding) — never drop a soft thought.
    const char *vadPath = nullptr;
    if (vadModelPath != nullptr) {
        vadPath = env->GetStringUTFChars(vadModelPath, nullptr);
        if (vadPath && vadPath[0] != '\0') {
            params.vad            = true;
            params.vad_model_path = vadPath;
            params.vad_params     = whisper_vad_default_params();
            params.vad_params.threshold               = 0.30f;  // default 0.50 — more permissive
            params.vad_params.min_speech_duration_ms  = 100;
            params.vad_params.min_silence_duration_ms = 600;    // only end a segment after a real pause
            params.vad_params.speech_pad_ms           = 200;    // don't clip word onsets/offsets
        }
    }

    CallbackCtx cbctx{env, callback, nullptr, nullptr};
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        cbctx.seg_method  = env->GetMethodID(cls, "jniSegment",  "(Ljava/lang/String;)V");
        cbctx.prog_method = env->GetMethodID(cls, "jniProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (cbctx.seg_method != nullptr) {
            params.new_segment_callback           = cb_new_segment;
            params.new_segment_callback_user_data = &cbctx;
        }
        if (cbctx.prog_method != nullptr) {
            params.progress_callback           = cb_progress;
            params.progress_callback_user_data = &cbctx;
        }
    }

    int rc = -1;
    bool native_threw = false;
    try {
        rc = whisper_full(ctx, params, samples, n);
    } catch (const std::exception &e) {
        native_threw = true;
        g_last_error = std::string("native exception: ") + e.what();
        LOGE("%s", g_last_error.c_str());
    } catch (...) {
        native_threw = true;
        g_last_error = "native exception: unknown";
        LOGE("%s", g_last_error.c_str());
    }

    if (lang) env->ReleaseStringUTFChars(language, lang);
    if (vadPath) env->ReleaseStringUTFChars(vadModelPath, vadPath);

    if (native_threw) {
        return env->NewStringUTF("");
    }
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        g_last_error = "whisper_full returned " + std::to_string(rc);
        return env->NewStringUTF("");
    }
    g_last_error.clear();

    std::string out;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) out += text;
    }
    size_t start = out.find_first_not_of(" \t\n");
    if (start != std::string::npos) out = out.substr(start);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/,
        jlong ctxPtr,
        jfloatArray pcm,
        jstring language,
        jboolean translate,
        jint nThreads,
        jboolean useBeam,
        jobject callback,
        jstring vadModelPath) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    jstring out = transcribe_pcm(env, ctx, samples, n, language, translate, nThreads, useBeam,
                                 callback, vadModelPath);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return out;
}

// Final-pass entry point: reads int16 LE PCM straight from disk into a native buffer, so a long
// recording never materializes as a Kotlin FloatArray + a second JNI pin/copy. endSample -1 = to EOF.
JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeTranscribeFile(
        JNIEnv *env, jobject /*thiz*/,
        jlong ctxPtr,
        jstring pcmPath,
        jlong startSample,
        jlong endSample,
        jstring language,
        jboolean translate,
        jint nThreads,
        jboolean useBeam,
        jobject callback,
        jstring vadModelPath) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const char *path = env->GetStringUTFChars(pcmPath, nullptr);
    FILE *f = path ? fopen(path, "rb") : nullptr;
    if (path) env->ReleaseStringUTFChars(pcmPath, path);
    if (f == nullptr) {
        g_last_error = "pcm file open failed";
        LOGE("%s", g_last_error.c_str());
        return env->NewStringUTF("");
    }

    fseek(f, 0, SEEK_END);
    const long file_samples = ftell(f) / 2;
    long start = startSample < 0 ? 0 : (long) startSample;
    if (start > file_samples) start = file_samples;
    long end = (endSample < 0 || (long) endSample > file_samples) ? file_samples : (long) endSample;
    if (end < start) end = start;
    fseek(f, start * 2, SEEK_SET);

    // int16 → float: s / 32768.0f, the exact conversion MicRecorder.readPcm / shortsToFloat use.
    std::vector<float> samples;
    samples.reserve((size_t) (end - start));
    int16_t buf[32 * 1024];   // 64 KB blocks, matching readPcm's block size
    const long buf_samples = (long) (sizeof(buf) / sizeof(buf[0]));
    long remaining = end - start;
    while (remaining > 0) {
        size_t want = (size_t) (remaining < buf_samples ? remaining : buf_samples);
        size_t got = fread(buf, 2, want, f);
        if (got == 0) break;
        for (size_t i = 0; i < got; ++i) samples.push_back(buf[i] / 32768.0f);
        remaining -= (long) got;
    }
    fclose(f);

    return transcribe_pcm(env, ctx, samples.data(), (jsize) samples.size(), language, translate,
                          nThreads, useBeam, callback, vadModelPath);
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeBackendInfo(
        JNIEnv *env, jobject /*thiz*/) {
    std::string info;
#ifdef WHISPERSHARE_VULKAN
    info = "vulkan";
#else
    info = "cpu";
#endif
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeLastError(
        JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_error.c_str());
}

// Abort the in-flight whisper_full run (user cancelled the queued clip).
// The aborted run returns rc != 0 → "" upstream; the caller decides discard.
JNIEXPORT void JNICALL
Java_com_thoughtpocket_WhisperEngine_nativeCancelTranscribe(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_cancel_transcribe.store(true);
}

} // extern "C"
