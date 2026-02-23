// llama_jni.cpp v0.9.4
// Fixes: armv8.4-a arch, mutex guard, batch_clear, sampler API, JNI threading
// Crash was SIGSEGV in ggml_compute_forward_mul_mat due to:
//   1. armv8-a+dotprod generating misaligned GEMM kernels on Cortex-X4
//   2. No llama_batch_clear between calls — dirty buffers hit math kernel
//   3. No mutex — concurrent JNI calls corrupted context state

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::mutex     g_mutex;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeLoadModel(
        JNIEnv* env, jobject,
        jstring modelPath) {

    std::lock_guard<std::mutex> lock(g_mutex);

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading: %s", path);

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) { LOGE("Model load failed"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = 2048;
    cp.n_threads      = 4;
    cp.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Context init failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("Model ready");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeGenerate(
        JNIEnv* env, jobject,
        jstring promptStr, jint maxTokens) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Generate called — no model");
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(promptStr, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize
    int n = -llama_tokenize(vocab, prompt, (int)strlen(prompt),
                            nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, prompt, (int)strlen(prompt),
                   tokens.data(), n, true, true);
    env->ReleaseStringUTFChars(promptStr, prompt);

    LOGI("Prompt: %d tokens, max new: %d", n, (int)maxTokens);

    // Init sampler — greedy for determinism, avoids sampling randomness crashes
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    // Init batch — sized for prompt
    llama_batch batch = llama_batch_init((int)tokens.size(), 0, 1);

    // NOTE: llama_batch_clear zeros n_tokens — MUST call before filling
    batch.n_tokens = 0;

    for (int i = 0; i < n; i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed on prompt");
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        return env->NewStringUTF("");
    }

    // Generate
    std::string result;
    int pos = n;
    llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < (int)maxTokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
        if (tok == eos || tok < 0) break;

        char piece[256] = {};
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece) - 1, 0, false);
        if (len > 0) result.append(piece, len);

        // NOTE: batch_clear + single token batch for each new token
        batch.n_tokens = 0;
        batch.token[0]     = tok;
        batch.pos[0]       = pos;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        batch.n_tokens     = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at pos %d", pos);
            break;
        }
        pos++;
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);

    LOGI("Done: %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeIsLoaded(JNIEnv*, jobject) {
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    LOGI("Model unloaded");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("No model loaded");
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    char info[128];
    snprintf(info, sizeof(info), "Vocab: %d | Ctx: %d",
             llama_vocab_n_tokens(vocab), llama_n_ctx(g_ctx));
    return env->NewStringUTF(info);
}
