// llama_jni.cpp v0.9.1 fix — updated to current llama.cpp API
// Uses llama_vocab* for tokenization (new API)
// Uses llama_model_free, llama_model_load_from_file (new API)

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama_src/include/llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static llama_model*   g_model    = nullptr;
static llama_context* g_ctx      = nullptr;
static bool           g_is_loaded = false;

static std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jint nCtx) {

    if (g_is_loaded) {
        if (g_ctx)   { llama_free(g_ctx);          g_ctx   = nullptr; }
        if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
        g_is_loaded = false;
    }

    std::string path = jstr(env, modelPath);
    LOGI("Loading model: %s", path.c_str());

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = (uint32_t)nCtx;
    cp.n_batch        = 512;
    cp.n_threads      = 4;
    cp.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("Model loaded OK");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeGenerate(
        JNIEnv* env, jobject, jstring prompt, jint maxTokens) {

    if (!g_is_loaded || !g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    std::string ps = jstr(env, prompt);

    // Get vocab from model — new API
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize
    const int np = -llama_tokenize(vocab, ps.c_str(), (int32_t)ps.size(),
                                   nullptr, 0, true, true);
    std::vector<llama_token> tokens(np);
    if (llama_tokenize(vocab, ps.c_str(), (int32_t)ps.size(),
                       tokens.data(), np, true, true) < 0) {
        LOGE("Tokenize failed");
        return env->NewStringUTF("");
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed");
        return env->NewStringUTF("");
    }

    std::string result;
    llama_token eos = llama_vocab_eos(vocab);
    int n_vocab     = llama_vocab_n_tokens(vocab);

    for (int i = 0; i < (int)maxTokens; i++) {
        auto*  logits  = llama_get_logits_ith(g_ctx, -1);
        llama_token tok = 0;
        float  mx      = logits[0];
        for (int j = 1; j < n_vocab; j++) {
            if (logits[j] > mx) { mx = logits[j]; tok = j; }
        }

        if (tok == eos) break;

        char buf[256];
        int  len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, nb) != 0) { LOGW("Decode error — stopping"); break; }
    }

    // Clear KV cache for next call — new function name


    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeIsLoaded(JNIEnv*, jobject) {
    return g_is_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeUnload(JNIEnv*, jobject) {
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    g_is_loaded = false;
    LOGI("Model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeGetModelInfo(JNIEnv* env, jobject) {
    if (!g_is_loaded || !g_model) return env->NewStringUTF("No model loaded");
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string info = "Vocab: ";
    info += std::to_string(llama_vocab_n_tokens(vocab));
    info += " | Ctx: ";
    info += std::to_string(llama_n_ctx(g_ctx));
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
