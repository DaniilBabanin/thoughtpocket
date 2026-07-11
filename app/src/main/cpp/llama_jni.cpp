// llama.cpp JNI bridge for the coder feature (ai/coder/LlamaEngine.kt).
// Follows whisper_jni.cpp conventions: jlong handle, g_last_error stash,
// cached-jmethodID upcalls, no C++ exceptions across JNI. New vs whisper:
// a cancel flag checked every token — generations run minutes, users cancel.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string g_last_error;
// One inference at a time app-wide (AiMutex on the Kotlin side), so a single
// global cancel flag is unambiguous. Set by nativeCancel, cleared on entry.
std::atomic<bool> g_cancel{false};

struct LlamaHandle {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
};

// Token pieces can split multi-byte UTF-8 (byte tokens); NewStringUTF on a
// partial sequence aborts under CheckJNI. Hold bytes until they form valid
// UTF-8, then flush.
bool is_valid_utf8(const std::string &s) {
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = s[i];
        int len = (c < 0x80) ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3
                : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0 || i + len > n) return false;
        for (int k = 1; k < len; k++)
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) return false;
        i += len;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeInitContext(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LlamaHandle *h = nullptr;
    try {
        llama_backend_init(); // idempotent
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0; // CPU only in v1
        llama_model *model = llama_model_load_from_file(path, mp);
        if (!model) {
            g_last_error = "model load failed";
        } else {
            llama_context_params cp = llama_context_default_params();
            cp.n_ctx = nCtx;
            cp.n_batch = 512;
            cp.n_threads = nThreads;
            cp.n_threads_batch = nThreads;
            llama_context *ctx = llama_init_from_model(model, cp);
            if (!ctx) {
                g_last_error = "context init failed";
                llama_model_free(model);
            } else {
                h = new LlamaHandle{model, ctx, llama_model_get_vocab(model)};
            }
        }
    } catch (const std::exception &e) {
        g_last_error = e.what();
    } catch (...) {
        g_last_error = "unknown native error in init";
    }
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint maxTokens, jobject callback) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) { g_last_error = "null handle"; return nullptr; }

    g_cancel.store(false);

    jmethodID on_token = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string out;
    bool ok = false;
    try {
        // Each call is a standalone full prompt (the harness threads its own
        // history), so drop any previous KV state.
        llama_memory_clear(llama_get_memory(h->ctx), true);

        std::vector<llama_token> tokens(strlen(prompt) + 16);
        int n_tok = llama_tokenize(h->vocab, prompt, (int32_t) strlen(prompt),
                                   tokens.data(), (int32_t) tokens.size(),
                                   /*add_special=*/true, /*parse_special=*/true);
        if (n_tok <= 0) {
            g_last_error = "tokenize failed";
        } else if (n_tok + maxTokens >= (int) llama_n_ctx(h->ctx)) {
            g_last_error = "prompt too long for context (" + std::to_string(n_tok) + " tokens)";
        } else {
            tokens.resize(n_tok);
            llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

            std::string pending; // UTF-8 buffer, see is_valid_utf8
            llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
            ok = true;
            for (int i = 0; i < maxTokens; i++) {
                if (g_cancel.load()) { LOGI("generate cancelled at token %d", i); break; }
                if (llama_decode(h->ctx, batch) != 0) {
                    g_last_error = "decode failed at token " + std::to_string(i);
                    ok = false;
                    break;
                }
                llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
                if (llama_vocab_is_eog(h->vocab, tok)) break;
                char buf[256];
                int n = llama_token_to_piece(h->vocab, tok, buf, sizeof(buf), 0, true);
                if (n > 0) {
                    pending.append(buf, n);
                    if (is_valid_utf8(pending)) {
                        out += pending;
                        if (on_token) {
                            jstring js = env->NewStringUTF(pending.c_str());
                            env->CallVoidMethod(callback, on_token, js);
                            if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
                            env->DeleteLocalRef(js);
                        }
                        pending.clear();
                    }
                }
                batch = llama_batch_get_one(&tok, 1);
            }
            llama_sampler_free(smpl);
        }
    } catch (const std::exception &e) {
        g_last_error = e.what();
        ok = false;
    } catch (...) {
        g_last_error = "unknown native error in generate";
        ok = false;
    }
    env->ReleaseStringUTFChars(jprompt, prompt);
    return ok ? env->NewStringUTF(out.c_str()) : nullptr;
}

JNIEXPORT void JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeFreeContext(
        JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

} // extern "C"
