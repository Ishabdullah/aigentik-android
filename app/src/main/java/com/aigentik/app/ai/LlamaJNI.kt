package com.aigentik.app.ai

import android.util.Log

// LlamaJNI v0.9.5 — Kotlin-side mutex prevents concurrent JNI calls
// v0.9.5: nativeLibLoaded flag exposed so AiEngine can distinguish
//   "native .so failed to load" from "model not yet loaded". Enables
//   dashboard to show "Native lib error" vs "No model" accurately.
//   Temperature + top-p sampling params added to generate() — pass
//   temperature=0.0f to use greedy (legacy behavior).
// Native side also has std::mutex — double protection against re-entrancy
// All native calls must happen on Dispatchers.IO — never on Main thread
class LlamaJNI private constructor() {

    companion object {
        private const val TAG = "LlamaJNI"
        private var instance: LlamaJNI? = null

        fun getInstance(): LlamaJNI {
            return instance ?: synchronized(this) {
                instance ?: LlamaJNI().also {
                    it.loadNativeLib()
                    instance = it
                }
            }
        }
    }

    // Kotlin-side lock — prevents two coroutines calling nativeGenerate simultaneously
    private val lock = java.util.concurrent.locks.ReentrantLock()

    // Tracks whether the native .so was successfully loaded.
    // False means the JNI bridge itself is broken (wrong ABI, missing from APK, etc.)
    // Distinct from "model not loaded" — allows dashboard to show specific error.
    private var nativeLibLoaded = false

    private fun loadNativeLib() {
        try {
            System.loadLibrary("aigentik_llama")
            nativeLibLoaded = true
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibLoaded = false
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    fun isNativeLibLoaded(): Boolean = nativeLibLoaded

    fun loadModel(path: String): Boolean {
        return try {
            lock.lock()
            nativeLoadModel(path)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "loadModel UnsatisfiedLinkError: ${e.message}")
            false
        } finally {
            lock.unlock()
        }
    }

    // NOTE: This blocks the calling thread during generation (can be 5-30s)
    // Always call from Dispatchers.IO — never on Main thread
    // temperature: 0.0 = greedy/deterministic, 0.7 = balanced, 1.0 = creative
    // topP: nucleus sampling probability mass (0.9 is a good default)
    fun generate(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.9f
    ): String {
        return try {
            lock.lock()
            nativeGenerate(prompt, maxTokens, temperature, topP)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "generate UnsatisfiedLinkError: ${e.message}")
            ""
        } finally {
            lock.unlock()
        }
    }

    fun isLoaded(): Boolean {
        return try {
            nativeIsLoaded()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun unload() {
        try {
            lock.lock()
            nativeUnload()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "unload error: ${e.message}")
        } finally {
            lock.unlock()
        }
    }

    fun getModelInfo(): String {
        return try {
            nativeGetModelInfo()
        } catch (e: UnsatisfiedLinkError) {
            "Native library not available"
        }
    }

    // Chat prompt formatter for Qwen3 ChatML format
    fun buildChatPrompt(systemPrompt: String, userMessage: String): String {
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
               "<|im_start|>user\n$userMessage<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    // Native declarations — prefixed to avoid Kotlin overload conflicts
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topP: Float): String
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeUnload()
    private external fun nativeGetModelInfo(): String
}
