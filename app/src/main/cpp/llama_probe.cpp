// SPIKE (Phase 1, coder feature): minimal llama.cpp smoke probe.
// Loads a GGUF, greedily decodes a few tokens, returns them as a string.
// Exists only to prove the shared-ggml build produces coherent output on
// device; replaced by the real bridge (llama_jni.cpp) in Phase 2.
#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"

static std::string probe(const char *model_path, int n_predict) {
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only
    llama_model *model = llama_model_load_from_file(model_path, mparams);
    if (!model) { llama_backend_free(); return "ERROR: model load failed"; }

    const llama_vocab *vocab = llama_model_get_vocab(model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_batch = 512;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        llama_backend_free();
        return "ERROR: context init failed";
    }

    const char *prompt = "def fibonacci(n):";
    std::vector<llama_token> tokens(512);
    int n_tok = llama_tokenize(vocab, prompt, (int32_t) strlen(prompt),
                               tokens.data(), (int32_t) tokens.size(),
                               /*add_special=*/true, /*parse_special=*/true);
    if (n_tok <= 0) {
        llama_free(ctx); llama_model_free(model); llama_backend_free();
        return "ERROR: tokenize failed";
    }
    tokens.resize(n_tok);

    std::string out;
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    for (int i = 0; i < n_predict; i++) {
        if (llama_decode(ctx, batch) != 0) { out += " [decode error]"; break; }
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        batch = llama_batch_get_one(&tok, 1);
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_thoughtpocket_LlamaProbe_nativeProbe(JNIEnv *env, jobject /*thiz*/,
                                              jstring model_path, jint n_predict) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::string result;
    try {
        result = probe(path, n_predict);
    } catch (...) {
        result = "ERROR: native exception";
    }
    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}
