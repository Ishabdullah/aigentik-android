package com.aigentik.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.ai.LlamaJNI
import com.aigentik.app.core.AigentikSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AiDiagnosticActivity v1.0
// Provides a diagnostic panel for testing:
//   - Native library (.so) load status
//   - Model load status and configuration info
//   - Inference speed benchmark (tokens/second)
//   - Sample output from a known test prompt
//
// Useful for verifying the AI pipeline is working correctly
// after installing a new model or updating the app.
class AiDiagnosticActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val llama get() = LlamaJNI.getInstance()

    private lateinit var tvNativeLibStatus : TextView
    private lateinit var tvModelStatus     : TextView
    private lateinit var tvModelInfo       : TextView
    private lateinit var tvBenchmarkResult : TextView
    private lateinit var tvSampleOutput    : TextView
    private lateinit var btnRunBenchmark   : Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_diagnostic)
        AigentikSettings.init(this)

        tvNativeLibStatus = findViewById(R.id.tvNativeLibStatus)
        tvModelStatus     = findViewById(R.id.tvModelStatus)
        tvModelInfo       = findViewById(R.id.tvModelInfo)
        tvBenchmarkResult = findViewById(R.id.tvBenchmarkResult)
        tvSampleOutput    = findViewById(R.id.tvSampleOutput)
        btnRunBenchmark   = findViewById(R.id.btnRunBenchmark)

        btnRunBenchmark.setOnClickListener { runBenchmark() }

        refreshStatus()
    }

    private fun refreshStatus() {
        // Native library
        if (llama.isNativeLibLoaded()) {
            tvNativeLibStatus.text = "✅ libaiгentik_llama.so loaded"
            tvNativeLibStatus.setTextColor(0xFF00FF88.toInt())
        } else {
            tvNativeLibStatus.text = "❌ Failed to load native library\n" +
                "• Check that ABI = arm64-v8a\n" +
                "• Rebuild the APK and reinstall"
            tvNativeLibStatus.setTextColor(0xFFFF4444.toInt())
        }

        // Model
        if (AiEngine.isReady()) {
            tvModelStatus.text = "✅ Model loaded and ready"
            tvModelStatus.setTextColor(0xFF00FF88.toInt())
            tvModelInfo.text = AiEngine.getModelInfo()
        } else {
            val stateLbl = AiEngine.getStateLabel()
            tvModelStatus.text = "⚠️ $stateLbl"
            tvModelStatus.setTextColor(0xFFFFAA00.toInt())
            val modelPath = AigentikSettings.modelPath
            tvModelInfo.text = if (modelPath.isEmpty()) "No model path configured"
                               else "Path: $modelPath"
        }
    }

    private fun runBenchmark() {
        if (!AiEngine.isReady()) {
            tvBenchmarkResult.text = "❌ Model not loaded. Load a model first in Settings → Manage AI Model."
            tvBenchmarkResult.setTextColor(0xFFFF4444.toInt())
            return
        }

        btnRunBenchmark.isEnabled = false
        tvBenchmarkResult.text = "Running benchmark..."
        tvBenchmarkResult.setTextColor(0xFFFFAA00.toInt())
        tvSampleOutput.text = "(generating...)"

        scope.launch {
            val (elapsed, output) = withContext(Dispatchers.IO) {
                val testPrompt = llama.buildChatPrompt(
                    "You are a concise AI assistant.",
                    "Briefly explain what artificial intelligence is in two sentences."
                )
                val startMs = System.currentTimeMillis()
                // Use 64 tokens — enough to measure real speed, short enough to be fast
                val result = llama.generate(testPrompt, maxTokens = 64, temperature = 0.7f, topP = 0.9f)
                val endMs = System.currentTimeMillis()
                Pair(endMs - startMs, result)
            }

            btnRunBenchmark.isEnabled = true

            if (output.isEmpty()) {
                tvBenchmarkResult.text = "❌ Generation returned empty output.\n" +
                    "Model may be corrupt or context too small."
                tvBenchmarkResult.setTextColor(0xFFFF4444.toInt())
                tvSampleOutput.text = "(no output)"
                return@launch
            }

            // Approximate token count from output chars (rough estimate: ~4 chars/token)
            val approxTokens = (output.length / 4).coerceAtLeast(1)
            val tokensPerSec = approxTokens * 1000.0 / elapsed.coerceAtLeast(1)

            val result = buildString {
                appendLine("✅ Benchmark complete")
                appendLine("─────────────────────")
                appendLine("Time:        ${elapsed}ms")
                appendLine("Output:      ${output.length} chars (~$approxTokens tokens)")
                appendLine("Speed:       %.1f tok/s (approx)".format(tokensPerSec))
                appendLine("─────────────────────")
                appendLine(AiEngine.getModelInfo())
            }

            tvBenchmarkResult.text = result
            tvBenchmarkResult.setTextColor(0xFF00FF88.toInt())
            tvSampleOutput.text = output.trim()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// Extension to cancel a CoroutineScope (avoids needing Job reference)
private fun CoroutineScope.cancel() {
    this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
