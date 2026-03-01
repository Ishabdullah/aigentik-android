package com.aigentik.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.ai.LlamaJNI
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.email.GmailApiClient
import com.aigentik.app.email.GmailHistoryClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AiDiagnosticActivity v1.1
// v1.1: Added Gmail Health section — shows sign-in status, scope grant status,
//   historyId prime status, and a "Check Gmail Token" button that calls
//   GmailApiClient.checkTokenHealth() (users.getProfile) to verify the token
//   can actually reach the Gmail API. Useful for diagnosing OAuth issues.
// v1.0: AI pipeline diagnostics (native lib, model, benchmark).
class AiDiagnosticActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val llama get() = LlamaJNI.getInstance()

    private lateinit var tvNativeLibStatus    : TextView
    private lateinit var tvModelStatus        : TextView
    private lateinit var tvModelInfo          : TextView
    private lateinit var tvBenchmarkResult    : TextView
    private lateinit var tvSampleOutput       : TextView
    private lateinit var btnRunBenchmark      : Button
    private lateinit var tvGmailSignInStatus  : TextView
    private lateinit var tvGmailScopeStatus   : TextView
    private lateinit var tvGmailHistoryStatus : TextView
    private lateinit var btnCheckGmailHealth  : Button
    private lateinit var tvGmailHealthResult  : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_diagnostic)

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvNativeLibStatus    = findViewById(R.id.tvNativeLibStatus)
        tvModelStatus        = findViewById(R.id.tvModelStatus)
        tvModelInfo          = findViewById(R.id.tvModelInfo)
        tvBenchmarkResult    = findViewById(R.id.tvBenchmarkResult)
        tvSampleOutput       = findViewById(R.id.tvSampleOutput)
        btnRunBenchmark      = findViewById(R.id.btnRunBenchmark)
        tvGmailSignInStatus  = findViewById(R.id.tvGmailSignInStatus)
        tvGmailScopeStatus   = findViewById(R.id.tvGmailScopeStatus)
        tvGmailHistoryStatus = findViewById(R.id.tvGmailHistoryStatus)
        btnCheckGmailHealth  = findViewById(R.id.btnCheckGmailHealth)
        tvGmailHealthResult  = findViewById(R.id.tvGmailHealthResult)

        btnRunBenchmark.setOnClickListener { runBenchmark() }
        btnCheckGmailHealth.setOnClickListener { checkGmailHealth() }

        refreshStatus()
    }

    private fun refreshStatus() {
        // Native library
        if (llama.isNativeLibLoaded()) {
            tvNativeLibStatus.text = "libaiгentik_llama.so loaded"
            tvNativeLibStatus.setTextColor(0xFF00FF88.toInt())
        } else {
            tvNativeLibStatus.text = "Failed to load native library\n" +
                "Check that ABI = arm64-v8a\n" +
                "Rebuild the APK and reinstall"
            tvNativeLibStatus.setTextColor(0xFFFF4444.toInt())
        }

        // Model
        if (AiEngine.isReady()) {
            tvModelStatus.text = "Model loaded and ready"
            tvModelStatus.setTextColor(0xFF00FF88.toInt())
            tvModelInfo.text = AiEngine.getModelInfo()
        } else {
            val stateLbl = AiEngine.getStateLabel()
            tvModelStatus.text = stateLbl
            tvModelStatus.setTextColor(0xFFFFAA00.toInt())
            val modelPath = AigentikSettings.modelPath
            tvModelInfo.text = if (modelPath.isEmpty()) "No model path configured"
                               else "Path: $modelPath"
        }

        // Gmail status
        refreshGmailStatus()
    }

    private fun refreshGmailStatus() {
        // Sign-in
        val signedIn = GoogleAuthManager.isSignedIn(this)
        val email = GoogleAuthManager.getSignedInEmail(this)
        if (signedIn && email != null) {
            tvGmailSignInStatus.text = "Sign-in: $email"
            tvGmailSignInStatus.setTextColor(0xFF00FF88.toInt())
        } else {
            tvGmailSignInStatus.text = "Sign-in: Not signed in"
            tvGmailSignInStatus.setTextColor(0xFFFF4444.toInt())
        }

        // Scopes
        when {
            GoogleAuthManager.gmailScopesGranted -> {
                tvGmailScopeStatus.text = "Scopes: gmail.modify + gmail.send granted"
                tvGmailScopeStatus.setTextColor(0xFF00FF88.toInt())
            }
            GoogleAuthManager.hasPendingScopeResolution() -> {
                tvGmailScopeStatus.text = "Scopes: Consent needed — grant in Settings"
                tvGmailScopeStatus.setTextColor(0xFFFFAA00.toInt())
            }
            !signedIn -> {
                tvGmailScopeStatus.text = "Scopes: N/A (not signed in)"
                tvGmailScopeStatus.setTextColor(0xFF556677.toInt())
            }
            else -> {
                tvGmailScopeStatus.text = "Scopes: Unknown — tap Check Gmail Token"
                tvGmailScopeStatus.setTextColor(0xFF7BA7CC.toInt())
            }
        }

        // History ID
        val historyId = GmailHistoryClient.getHistoryId()
        val primeResult = GmailHistoryClient.lastPrimeResult
        if (historyId != null) {
            tvGmailHistoryStatus.text = "History ID: $historyId (delta fetch ready)"
            tvGmailHistoryStatus.setTextColor(0xFF00FF88.toInt())
        } else if (primeResult != null) {
            tvGmailHistoryStatus.text = "History ID: Not set (prime result: ${primeResult.name})"
            tvGmailHistoryStatus.setTextColor(0xFFFFAA00.toInt())
        } else {
            tvGmailHistoryStatus.text = "History ID: Not set"
            tvGmailHistoryStatus.setTextColor(0xFF556677.toInt())
        }
    }

    private fun checkGmailHealth() {
        if (!GoogleAuthManager.isSignedIn(this)) {
            tvGmailHealthResult.text = "Not signed in — sign in first in Settings."
            tvGmailHealthResult.setTextColor(0xFFFF4444.toInt())
            return
        }

        btnCheckGmailHealth.isEnabled = false
        tvGmailHealthResult.text = "Checking Gmail token..."
        tvGmailHealthResult.setTextColor(0xFFFFAA00.toInt())

        scope.launch {
            val email = withContext(Dispatchers.IO) {
                GmailApiClient.checkTokenHealth(this@AiDiagnosticActivity)
            }
            btnCheckGmailHealth.isEnabled = true
            refreshGmailStatus()

            if (email != null) {
                tvGmailHealthResult.text = buildString {
                    appendLine("Token: Valid")
                    appendLine("Gmail API: Accessible")
                    appendLine("Account: $email")
                    appendLine("Scopes: Granted")
                }
                tvGmailHealthResult.setTextColor(0xFF00FF88.toInt())
            } else {
                val error = GmailApiClient.lastError ?: GoogleAuthManager.lastTokenError ?: "Unknown"
                tvGmailHealthResult.text = buildString {
                    appendLine("Token: Failed")
                    appendLine("Error: $error")
                    if (GoogleAuthManager.hasPendingScopeResolution()) {
                        appendLine("\nAction: Go to Settings → Grant Gmail Permissions")
                    }
                }
                tvGmailHealthResult.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    private fun runBenchmark() {
        if (!AiEngine.isReady()) {
            tvBenchmarkResult.text = "Model not loaded. Load a model first in Settings → Manage AI Model."
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
                val result = llama.generate(testPrompt, maxTokens = 64, temperature = 0.7f, topP = 0.9f)
                val endMs = System.currentTimeMillis()
                Pair(endMs - startMs, result)
            }

            btnRunBenchmark.isEnabled = true

            if (output.isEmpty()) {
                tvBenchmarkResult.text = "Generation returned empty output.\n" +
                    "Model may be corrupt or context too small."
                tvBenchmarkResult.setTextColor(0xFFFF4444.toInt())
                tvSampleOutput.text = "(no output)"
                return@launch
            }

            val approxTokens = (output.length / 4).coerceAtLeast(1)
            val tokensPerSec = approxTokens * 1000.0 / elapsed.coerceAtLeast(1)

            val result = buildString {
                appendLine("Benchmark complete")
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

private fun CoroutineScope.cancel() {
    this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
