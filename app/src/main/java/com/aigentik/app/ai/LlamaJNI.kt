package com.aigentik.app.ai

import android.util.Log

// LlamaJNI v0.9.1 — Kotlin interface to llama.cpp via JNI
// All functions are native — implemented in llama_jni.cpp
// NOTE: Never call from main thread — always use Dispatchers.IO
class LlamaJNI {

    companion object {
        private const val TAG = "LlamaJNI"
        private const val DEFAULT_CONTEXT_SIZE = 2048

        // Load the native library
        // NOTE: Library name matches add_library name in CMakeLists.txt
        init {
            try {
                System.loadLibrary("aigentik_llama")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
                Log.e(TAG, "AI features will not be available")
            }
        }

        // Singleton instance
        @Volatile
        private var instance: LlamaJNI? = null

        fun getInstance(): LlamaJNI {
            return instance ?: synchronized(this) {
                instance ?: LlamaJNI().also { instance = it }
            }
        }
    }

    // Check if native library is available
    fun isNativeAvailable(): Boolean {
        return try {
            isLoaded() // Will throw if library not loaded
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    // Load model — returns true on success
    // modelPath: absolute path to .gguf file
    // nCtx: context window size (2048 recommended for SMS/email)
    fun loadModel(
        modelPath: String,
        nCtx: Int = DEFAULT_CONTEXT_SIZE
    ): Boolean {
        Log.i(TAG, "Loading model: $modelPath (ctx=$nCtx)")
        return try {
            loadModel(modelPath, nCtx)
        } catch (e: Exception) {
            Log.e(TAG, "loadModel exception: ${e.message}")
            false
        }
    }

    // Generate text from prompt
    fun generate(prompt: String, maxTokens: Int = 256): String {
        return try {
            generate(prompt, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "generate exception: ${e.message}")
            ""
        }
    }

    // Build chat prompt in Qwen3 format
    // Qwen3 uses ChatML format: <|im_start|>role\ncontent<|im_end|>
    fun buildChatPrompt(systemMsg: String, userMsg: String): String {
        return "<|im_start|>system\n$systemMsg<|im_end|>\n" +
               "<|im_start|>user\n$userMsg<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    // Native function declarations — implemented in llama_jni.cpp
    private external fun loadModel(path: String, nCtx: Int): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun isLoaded(): Boolean
    external fun unloadModel()
    external fun getModelInfo(): String
}
