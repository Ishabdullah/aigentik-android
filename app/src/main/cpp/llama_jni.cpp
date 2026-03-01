// llama_jni.cpp v1.5
// v1.5: Reverted KV cache prefix reuse (v1.4) — llama_kv_cache_seq_rm /
//   llama_kv_self_seq_rm is not present in the cached llama.cpp version.
//   Reverted to resetContext() at the start of every generation (same as v1.3).
//   Safe across all supported llama.cpp versions; no functional regression.
// v1.3: Safe UTF-8 → jstring conversion via byte array (toJavaString helper).
//   JNI's NewStringUTF() requires Modified UTF-8 (no 4-byte sequences). LLMs
//   commonly produce emoji and supplementary Unicode (U+10000+) which ARE valid
//   standard UTF-8 but NOT Modified UTF-8. Passing them to NewStringUTF() causes
//   JNI to call abort() — process terminates, uncatchable by Kotlin try/catch.
//   Fix: toJavaString() creates a Java byte array from raw bytes and constructs
//   a Java String using new String(bytes, "UTF-8"), which handles all Unicode.
// v1.2: Temperature + top-p (nucleus) sampling replaces greedy sampler.
//   - temperature=0.7f + top-p=0.9f produces more natural, varied responses
//   - temperature=0.0f special-cased to use greedy for deterministic output
//     (used by interpretCommand() for reliable JSON action parsing)
//   - Sampler chain: temp → top_p → dist (random draw from filtered distribution)
// v1.1 Changes:
//   - n_ctx 2048 → 8192 (full 8k context)
//   - n_threads / n_threads_batch 4 → 6 (Snapdragon 8 Gen 3 perf cores)
//   - KV cache quantized to Q8_0 — ~4x memory savings vs F16
//   - batch size 256 for better prompt prefill throughput
//   - batch_sz = max(tokens, N_BATCH) — handles large prompts safely
//   - context safety margin increased 10 → 32 tokens

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

// Inference configuration — tuned for Snapdragon 8 Gen 3 (S24 Ultra)
static const int      CTX_SIZE  = 8192;
static const int      N_THREADS = 6;
static const int      N_BATCH   = 256;
static const ggml_type KV_TYPE  = GGML_TYPE_Q8_0;

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::mutex     g_mutex;

// Safe std::string → jstring conversion.
// JNI NewStringUTF() requires Modified UTF-8: it does NOT support 4-byte standard
// UTF-8 sequences (emoji, supplementary Unicode U+10000+). When an LLM produces
// such bytes, NewStringUTF() calls abort() — killing the process immediately.
// This helper creates a Java byte[] from raw bytes and uses the String(byte[], charset)
// constructor to decode standard UTF-8 safely in Java, supporting all Unicode.
static jstring toJavaString(JNIEnv* env, const std::string& s) {
    if (s.empty()) return env->NewStringUTF("");
    jbyteArray arr = env->NewByteArray((jsize)s.size());
    if (!arr) return env->NewStringUTF("");
    env->SetByteArrayRegion(arr, 0, (jsize)s.size(),
                            reinterpret_cast<const jbyte*>(s.data()));
    jclass  strClass = env->FindClass("java/lang/String");
    jmethodID ctor   = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset  = env->NewStringUTF("UTF-8");
    auto    result   = (jstring)env->NewObject(strClass, ctor, arr, charset);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return result ? result : env->NewStringUTF("");
}

// Recreate context to clear KV cache between generations
// Q8_0 KV cache: ~128MB at 8k ctx vs ~512MB F16 — fits comfortably in 6GB RAM
static bool resetContext() {
    if (!g_model) return false;
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = CTX_SIZE;
    cp.n_batch         = N_BATCH;
    cp.n_ubatch        = N_BATCH;
    cp.n_threads       = N_THREADS;
    cp.n_threads_batch = N_THREADS;
    cp.type_k          = KV_TYPE;
    cp.type_v          = KV_TYPE;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Context reset failed");
        return false;
    }
    LOGI("Context reset: ctx=%d batch=%d threads=%d kv=Q8_0", CTX_SIZE, N_BATCH, N_THREADS);
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath) {

    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) { LOGE("Model load failed"); return JNI_FALSE; }
    if (!resetContext()) return JNI_FALSE;

    LOGI("Model ready — ctx=%d kv=Q8_0 threads=%d", CTX_SIZE, N_THREADS);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_aigentik_app_ai_LlamaJNI_nativeGenerate(
        JNIEnv* env, jobject, jstring promptStr, jint maxTokens,
        jfloat temperature, jfloat topP) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Generate called — no model loaded");
        return env->NewStringUTF("");
    }

    // Reset context to clear KV cache from the previous generation.
    if (!resetContext()) return env->NewStringUTF("");

    const char* prompt = env->GetStringUTFChars(promptStr, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    int n = -llama_tokenize(vocab, prompt, (int)strlen(prompt), nullptr, 0, true, true);
    if (n <= 0) {
        LOGE("Tokenize failed (n=%d)", n);
        env->ReleaseStringUTFChars(promptStr, prompt);
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, prompt, (int)strlen(prompt), tokens.data(), n, true, true);
    env->ReleaseStringUTFChars(promptStr, prompt);

    LOGI("Prompt tokens: %d  max_new: %d  ctx: %d", n, (int)maxTokens, CTX_SIZE);

    if (n >= CTX_SIZE - 32) {
        LOGE("Prompt too long: %d tokens (limit %d)", n, CTX_SIZE - 32);
        return env->NewStringUTF("Prompt too long for context window.");
    }

    // Build sampler chain based on temperature:
    //   temperature == 0.0 → greedy (deterministic, used for command parsing)
    //   temperature  > 0.0 → temp → top_p → dist (stochastic, better for conversation)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // Size batch to max(n, N_BATCH) — handles large prompts safely.
    int batch_sz = (n > N_BATCH) ? n : N_BATCH;
    llama_batch batch = llama_batch_init(batch_sz, 0, 1);
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
        LOGE("Prompt decode failed");
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        return env->NewStringUTF("");
    }

    std::string result;
    int pos = n;
    llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < (int)maxTokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
        if (tok == eos || tok < 0) { LOGI("EOS at pos %d", pos); break; }

        char piece[256] = {};
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece) - 1, 0, false);
        if (len > 0) {
            std::string p(piece, len);
            if (p.find("<|im_end|>") != std::string::npos) break;
            result += p;
        }

        // Reuse slot 0 for single-token decode
        batch.n_tokens     = 1;
        batch.token[0]     = tok;
        batch.pos[0]       = pos;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at pos %d", pos);
            break;
        }
        pos++;

        if (pos >= CTX_SIZE - 32) {
            LOGI("Context limit approaching at pos %d — stopping", pos);
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    LOGI("Generated %zu chars in %d tokens", result.size(), pos - n);

    // Use toJavaString() instead of NewStringUTF() — see helper comment above.
    return toJavaString(env, result);
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
    if (!g_model || !g_ctx) return env->NewStringUTF("No model loaded");
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    char info[256];
    snprintf(info, sizeof(info),
             "Vocab: %d | Ctx: %d | Threads: %d | KV: Q8_0 | Batch: %d",
             llama_vocab_n_tokens(vocab), llama_n_ctx(g_ctx), N_THREADS, N_BATCH);
    return env->NewStringUTF(info);
}
