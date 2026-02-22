package com.aigentik.app.ai

import android.util.Log

// LlamaJNI v0.9.1 fix — native functions prefixed with "native"
// to avoid Kotlin overload conflicts with public wrapper functions
class LlamaJNI {

    companion object {
        private const val TAG = "LlamaJNI"
        private const val DEFAULT_CTX = 2048

        @Volatile private var instance: LlamaJNI? = null

        fun getInstance(): LlamaJNI = instance ?: synchronized(this) {
            instance ?: LlamaJNI().also { instance = it }
        }

        init {
            try {
                System.loadLibrary("aigentik_llama")
                Log.i(TAG, "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library load failed: ${e.message}")
            }
        }
    }

    // Public API — called by AiEngine
    fun loadModel(path: String, nCtx: Int = DEFAULT_CTX): Boolean {
        return try {
            nativeLoadModel(path, nCtx)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "loadModel JNI error: ${e.message}")
            false
        }
    }

    fun generate(prompt: String, maxTokens: Int = 256): String {
        return try {
            nativeGenerate(prompt, maxTokens)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "generate JNI error: ${e.message}")
            ""
        }
    }

    fun isLoaded(): Boolean {
        return try { nativeIsLoaded() }
        catch (e: UnsatisfiedLinkError) { false }
    }

    fun unload() {
        try { nativeUnload() }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "unload JNI error") }
    }

    fun getModelInfo(): String {
        return try { nativeGetModelInfo() }
        catch (e: UnsatisfiedLinkError) { "Native library not available" }
    }

    // Build Qwen3 ChatML format prompt
    fun buildChatPrompt(systemMsg: String, userMsg: String): String =
        "<|im_start|>system\n$systemMsg<|im_end|>\n" +
        "<|im_start|>user\n$userMsg<|im_end|>\n" +
        "<|im_start|>assistant\n"

    // Native declarations — names match JNI function names in llama_jni.cpp
    private external fun nativeLoadModel(path: String, nCtx: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeUnload()
    private external fun nativeGetModelInfo(): String
}
