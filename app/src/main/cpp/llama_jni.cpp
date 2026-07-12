// llama.cpp JNI bridge for the coder feature (ai/coder/LlamaEngine.kt).
// Follows whisper_jni.cpp conventions: jlong handle, g_last_error stash,
// cached-jmethodID upcalls, no C++ exceptions across JNI. New vs whisper:
// a cancel flag — checked every token AND wired into ggml's abort callback,
// so cancel lands mid-decode too (a long-prompt prefill is ONE llama_decode
// call that can run tens of seconds on CPU; the per-token check alone left
// Cancel inert for that whole stretch — found in review 2026-07-12).
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
        // Models live in the app's EXTERNAL files dir = FUSE on device. mmap'd
        // weights page-fault through FUSE on every access → decode crawls
        // (measured 2026-07-11 on Pixel 9 Pro XL). Load into RAM once instead;
        // residency is the same 5.6 GB either way.
        mp.use_mmap = false;
        llama_model *model = llama_model_load_from_file(path, mp);
        if (!model) {
            g_last_error = "model load failed";
        } else {
            llama_context_params cp = llama_context_default_params();
            cp.n_ctx = nCtx;
            // n_batch is the LOGICAL per-decode cap — a full long prompt must
            // fit or llama_decode hard-aborts (GGML_ASSERT, llama-context.cpp:
            // n_tokens_all <= n_batch; hit on-device 2026-07-12 with a ~4k-token
            // prompt). n_ubatch stays small — it alone sizes the compute buffer.
            cp.n_batch = nCtx;
            cp.n_ubatch = 512;
            cp.n_threads = nThreads;
            cp.n_threads_batch = nThreads;
            llama_context *ctx = llama_init_from_model(model, cp);
            if (!ctx) {
                g_last_error = "context init failed";
                llama_model_free(model);
            } else {
                // Abort mid-decode when cancelled (decode returns 2 = aborted).
                llama_set_abort_callback(ctx, [](void *) { return g_cancel.load(); }, nullptr);
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
        jint maxTokens, jfloat temperature, jobject callback) {
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
        } else if (n_tok >= (int) llama_n_ctx(h->ctx) - 8) {
            g_last_error = "prompt too long for context (" + std::to_string(n_tok) + " tokens)";
        } else {
            // Clamp the generation budget to what fits instead of refusing the
            // whole run — a shorter reply beats a hard "prompt too long".
            int max_gen = maxTokens;
            int room = (int) llama_n_ctx(h->ctx) - n_tok - 4;
            if (max_gen > room) { max_gen = room; LOGI("gen budget clamped to %d (prompt %d tok)", max_gen, n_tok); }

            tokens.resize(n_tok);
            llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
            if (temperature > 0.0f) {
                // Stuck-retry escalation (CoderHarness.decide): one sampled
                // attempt after two identical failures. Fixed seed — reproducible.
                llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(0xC0DE5EEDu));
            } else {
                llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
            }

            std::string pending; // UTF-8 buffer, see is_valid_utf8
            llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
            ok = true;
            for (int i = 0; i < max_gen; i++) {
                if (g_cancel.load()) { LOGI("generate cancelled at token %d", i); break; }
                int ret = llama_decode(h->ctx, batch);
                if (ret != 0) {
                    if (g_cancel.load()) { LOGI("generate aborted mid-decode at token %d", i); break; }
                    g_last_error = "decode failed (" + std::to_string(ret) + ") at token " + std::to_string(i);
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

// Format system+user through the model's embedded chat template (GGUF
// metadata). Returns null when the model ships no template (or it's one
// llama.cpp can't apply) — Kotlin falls back to hardcoded ChatML.
JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeFormatPrompt(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jsystem, jstring juser) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return nullptr;
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    if (!tmpl) return nullptr;
    const char *sys = env->GetStringUTFChars(jsystem, nullptr);
    const char *usr = env->GetStringUTFChars(juser, nullptr);
    jstring result = nullptr;
    try {
        llama_chat_message msgs[2] = {{"system", sys}, {"user", usr}};
        std::vector<char> buf(strlen(sys) + strlen(usr) + 1024);
        int32_t n = llama_chat_apply_template(tmpl, msgs, 2, /*add_ass=*/true,
                                              buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) {
            std::string out(buf.data(), (size_t) n);
            result = env->NewStringUTF(out.c_str());
        }
    } catch (...) {
        result = nullptr; // unsupported template → ChatML fallback upstream
    }
    env->ReleaseStringUTFChars(jsystem, sys);
    env->ReleaseStringUTFChars(juser, usr);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_ai_coder_LlamaEngine_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

} // extern "C"
