package com.aigentik.app.ai

import android.util.Log

// LlamaJNI v0.9.4 — Kotlin-side mutex prevents concurrent JNI calls
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

    private fun loadNativeLib() {
        try {
            System.loadLibrary("aigentik_llama")
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

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
    // Always call from Dispatchers.IO — never from Main thread
    fun generate(prompt: String, maxTokens: Int = 256): String {
        return try {
            lock.lock()
            nativeGenerate(prompt, maxTokens)
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
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeUnload()
    private external fun nativeGetModelInfo(): String
}
