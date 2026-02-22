// llama_jni.cpp — JNI bridge between Android Kotlin and llama.cpp
// Exposes model load, generate, and status functions to Kotlin
// NOTE: All functions run on IO thread — never call from main thread
// NOTE: Single model instance — not thread safe for concurrent generation

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// llama.cpp headers
#include "llama_src/include/llama.h"
#include "llama_src/include/llama-cpp.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global model state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static bool g_is_loaded = false;

// Convert jstring to std::string
static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

extern "C" {

// Load model from file path
// Returns true on success, false on failure
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_loadModel(
        JNIEnv* env, jobject /* this */, jstring modelPath, jint nCtx) {

    // Unload existing model if any
    if (g_is_loaded) {
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        if (g_model) { llama_free_model(g_model); g_model = nullptr; }
        g_is_loaded = false;
    }

    std::string path = jstring_to_string(env, modelPath);
    LOGI("Loading model from: %s", path.c_str());

    // Init backend
    llama_backend_init();

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only — GPU not available via JNI

    g_model = llama_load_model_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t)nCtx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4; // Use 4 threads on S24 Ultra CPU
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// Generate text from prompt
// Returns generated string or empty string on error
JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_generate(
        JNIEnv* env, jobject /* this */,
        jstring prompt, jint maxTokens) {

    if (!g_is_loaded || !g_model || !g_ctx) {
        LOGE("Model not loaded — cannot generate");
        return env->NewStringUTF("");
    }

    std::string prompt_str = jstring_to_string(env, prompt);

    // Tokenize prompt
    const int n_prompt_tokens = -llama_tokenize(
        g_model, prompt_str.c_str(), prompt_str.size(),
        nullptr, 0, true, true
    );

    std::vector<llama_token> tokens(n_prompt_tokens);
    if (llama_tokenize(g_model, prompt_str.c_str(), prompt_str.size(),
                       tokens.data(), tokens.size(), true, true) < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }

    // Evaluate prompt tokens
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        return env->NewStringUTF("");
    }

    // Generate tokens
    std::string result;
    int n_generated = 0;
    const int max_gen = (int)maxTokens;

    llama_token eos = llama_token_eos(g_model);

    while (n_generated < max_gen) {
        // Sample next token
        llama_token new_token;
        auto logits = llama_get_logits_ith(g_ctx, -1);

        // Greedy sampling
        int n_vocab = llama_n_vocab(g_model);
        new_token = 0;
        float max_logit = logits[0];
        for (int i = 1; i < n_vocab; i++) {
            if (logits[i] > max_logit) {
                max_logit = logits[i];
                new_token = i;
            }
        }

        // Stop at EOS
        if (new_token == eos) break;

        // Decode token to string
        char buf[256];
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
        }

        // Decode next token
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            LOGW("Decode error during generation — stopping");
            break;
        }

        n_generated++;
    }

    // Reset KV cache for next generation
    llama_kv_cache_clear(g_ctx);

    LOGI("Generated %d tokens", n_generated);
    return env->NewStringUTF(result.c_str());
}

// Check if model is loaded
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_isLoaded(JNIEnv* env, jobject /* this */) {
    return g_is_loaded ? JNI_TRUE : JNI_FALSE;
}

// Unload model and free memory
JNIEXPORT void JNICALL
Java_com_aigentik_app_ai_LlamaJNI_unloadModel(JNIEnv* env, jobject /* this */) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    llama_backend_free();
    g_is_loaded = false;
    LOGI("Model unloaded");
}

// Get model info string
JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_getModelInfo(JNIEnv* env, jobject /* this */) {
    if (!g_is_loaded || !g_model) {
        return env->NewStringUTF("No model loaded");
    }
    std::string info = "Params: ";
    info += std::to_string(llama_model_n_params(g_model) / 1000000);
    info += "M | Ctx: ";
    info += std::to_string(llama_n_ctx(g_ctx));
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
