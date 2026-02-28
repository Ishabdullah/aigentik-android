package com.aigentik.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.core.AigentikSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// ModelManagerActivity v0.9.3
// v0.9.3: Shows all downloaded GGUF files in modelsDir with "Load" button per file.
//   Allows switching between multiple downloaded models without re-downloading.
// v0.9.2: Handles model download from URL or loading from local file.
//   Can be launched from onboarding (showSkip=true) or settings (showSkip=false).
//   Downloads saved to app private storage — survives app updates.
//   Large models (2.5GB) require stable WiFi — warn user if on mobile data.
class ModelManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelManager"
        const val EXTRA_SHOW_SKIP = "show_skip"
        const val EXTRA_FROM_ONBOARDING = "from_onboarding"

        // Pre-filled Qwen3-4B Q4_K_M URL — official Qwen repo
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"

        private const val FILE_PICKER_REQUEST = 200
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var downloadJob: Job? = null
    private var isCancelled = false

    // Models stored in app private files dir — not deleted on update
    private val modelsDir: File by lazy {
        File(filesDir, "models").also { it.mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        setContentView(R.layout.activity_model_manager)

        val showSkip = intent.getBooleanExtra(EXTRA_SHOW_SKIP, false)

        // Pre-fill URL
        findViewById<EditText>(R.id.etDownloadUrl).setText(DEFAULT_MODEL_URL)

        // Show skip button only during onboarding
        val btnSkip = findViewById<Button>(R.id.btnSkip)
        btnSkip.visibility = if (showSkip) View.VISIBLE else View.GONE
        btnSkip.setOnClickListener { finishAndProceed() }

        // Update status with current model if loaded
        updateCurrentModelCard()
        updateDownloadedModelsList()

        // Download button
        findViewById<Button>(R.id.btnDownload).setOnClickListener {
            val url = findViewById<EditText>(R.id.etDownloadUrl).text.toString().trim()
            if (url.isEmpty() || !url.startsWith("http")) {
                showStatus("⚠️ Enter a valid URL starting with https://")
                return@setOnClickListener
            }
            startDownload(url)
        }

        // Browse for local file
        findViewById<Button>(R.id.btnBrowse).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, FILE_PICKER_REQUEST)
        }

        // Load from local path
        findViewById<Button>(R.id.btnLoadLocal).setOnClickListener {
            val path = findViewById<EditText>(R.id.etLocalPath).text.toString().trim()
            if (path.isEmpty()) {
                showStatus("⚠️ Enter a file path or use Browse")
                return@setOnClickListener
            }
            loadLocalFile(path)
        }

        // Cancel download
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            isCancelled = true
            downloadJob?.cancel()
            showStatus("Download cancelled")
            hideProgress()
        }
    }

    private fun startDownload(urlStr: String) {
        isCancelled = false
        val fileName = urlStr.substringAfterLast("/").ifEmpty { "model.gguf" }
        val destFile = File(modelsDir, fileName)

        showProgress()
        showStatus("Starting download of $fileName...")
        Log.i(TAG, "Downloading: $urlStr → ${destFile.absolutePath}")

        downloadJob = scope.launch {
            val success = withContext(Dispatchers.IO) {
                downloadFile(urlStr, destFile)
            }

            if (success && !isCancelled) {
                showStatus("✅ Download complete — loading model...")
                loadModelFile(destFile.absolutePath)
            } else if (!isCancelled) {
                showStatus("❌ Download failed — check URL and connection")
                hideProgress()
            }
        }
    }

    private suspend fun downloadFile(urlStr: String, destFile: File): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()

            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L
            var lastSpeedUpdate = System.currentTimeMillis()
            var lastBytes = 0L

            val buffer = ByteArray(8192)
            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            destFile.delete()
                            return false
                        }
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Update UI every 500ms
                        val now = System.currentTimeMillis()
                        if (now - lastSpeedUpdate > 500) {
                            val speed = (downloadedBytes - lastBytes) * 2 // bytes/sec
                            val speedMb = speed / 1_048_576.0
                            val downloadedMb = downloadedBytes / 1_048_576.0
                            val totalMb = if (totalBytes > 0) totalBytes / 1_048_576.0 else 0.0
                            val pct = if (totalBytes > 0)
                                (downloadedBytes * 100 / totalBytes).toInt() else 0

                            val eta = if (speed > 0 && totalBytes > 0) {
                                val remaining = (totalBytes - downloadedBytes) / speed
                                "${remaining}s remaining"
                            } else "calculating..."

                            withContext(Dispatchers.Main) {
                                updateProgress(pct,
                                    "%.1f MB / %.1f MB — %.1f MB/s — $eta"
                                        .format(downloadedMb, totalMb, speedMb))
                            }
                            lastSpeedUpdate = now
                            lastBytes = downloadedBytes
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            destFile.delete()
            false
        }
    }

    private fun loadLocalFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            showStatus("❌ File not found: $path")
            return
        }
        if (!path.endsWith(".gguf")) {
            showStatus("⚠️ File must be a .gguf model file")
            return
        }

        // If file is not already in models dir, copy it
        val destFile = File(modelsDir, file.name)
        if (file.absolutePath != destFile.absolutePath) {
            showStatus("Copying model to app storage...")
            showProgress()
            scope.launch {
                withContext(Dispatchers.IO) {
                    file.copyTo(destFile, overwrite = true)
                }
                loadModelFile(destFile.absolutePath)
            }
        } else {
            scope.launch { loadModelFile(path) }
        }
    }

    private suspend fun loadModelFile(path: String) {
        showStatus("Loading model — this takes 15-30 seconds...")
        Log.i(TAG, "Loading model: $path")

        val success = AiEngine.loadModel(path)

        if (success) {
            // Save model path to settings
            AigentikSettings.modelPath = path
            updateCurrentModelCard()
            updateDownloadedModelsList()
            showStatus("✅ Model loaded! ${AiEngine.getModelInfo()}")
            hideProgress()

            // Auto-proceed after 2 seconds if from onboarding
            if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) {
                kotlinx.coroutines.delay(2000)
                finishAndProceed()
            }
        } else {
            showStatus("❌ Failed to load model — file may be corrupt")
            hideProgress()
        }
    }

    // Show all downloaded .gguf files in modelsDir with a "Load" button per file
    private fun updateDownloadedModelsList() {
        val container = findViewById<LinearLayout>(R.id.layoutDownloadedModels) ?: return
        container.removeAllViews()

        val ggufFiles = modelsDir.listFiles { f -> f.name.endsWith(".gguf") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (ggufFiles.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "No downloaded models found."
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            }
            container.addView(tv)
            return
        }

        val currentPath = AigentikSettings.modelPath
        for (file in ggufFiles) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            // Model name + size
            val sizeMb = file.length() / (1024 * 1024)
            val isActive = file.absolutePath == currentPath
            val tv = android.widget.TextView(this).apply {
                text = "${if (isActive) "▶ " else ""}${file.name}\n${sizeMb} MB"
                textSize = 12f
                setTextColor(if (isActive) 0xFF00FF88.toInt() else 0xFFCCCCCC.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)

            // Load button (hidden for currently active model)
            if (!isActive) {
                val btn = android.widget.Button(this).apply {
                    text = "Load"
                    textSize = 12f
                    setTextColor(0xFF000000.toInt())
                    backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00FF88.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        scope.launch { loadModelFile(file.absolutePath) }
                        updateDownloadedModelsList()
                    }
                }
                row.addView(btn)
            }

            container.addView(row)
        }
    }

    private fun updateCurrentModelCard() {
        val card = findViewById<LinearLayout>(R.id.cardCurrentModel)
        val tvStatus = findViewById<TextView>(R.id.tvModelStatus)

        if (AiEngine.isReady()) {
            card.visibility = View.VISIBLE
            val path = AigentikSettings.modelPath
            val name = File(path).name
            findViewById<TextView>(R.id.tvLoadedModel).text = name
            findViewById<TextView>(R.id.tvModelInfo).text = AiEngine.getModelInfo()
            tvStatus.text = "✅ Model ready"
            tvStatus.setTextColor(0xFF00FF88.toInt())
        } else {
            card.visibility = View.GONE
            tvStatus.text = "No model loaded — AI will use fallback replies"
            tvStatus.setTextColor(0xFF888888.toInt())
        }
    }

    private fun showProgress() {
        findViewById<LinearLayout>(R.id.layoutProgress).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnDownload).isEnabled = false
        findViewById<Button>(R.id.btnLoadLocal).isEnabled = false
    }

    private fun hideProgress() {
        findViewById<LinearLayout>(R.id.layoutProgress).visibility = View.GONE
        findViewById<Button>(R.id.btnDownload).isEnabled = true
        findViewById<Button>(R.id.btnLoadLocal).isEnabled = true
    }

    private fun updateProgress(pct: Int, statusText: String) {
        findViewById<ProgressBar>(R.id.progressDownload).progress = pct
        findViewById<TextView>(R.id.tvDownloadStatus).text = statusText
    }

    private fun showStatus(msg: String) {
        findViewById<TextView>(R.id.tvModelStatus).text = msg
    }

    private fun finishAndProceed() {
        if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finishAffinity()
        } else {
            finish()
        }
    }

    // Handle file picker result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER_REQUEST && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val path = getRealPath(uri)
            if (path != null) {
                findViewById<EditText>(R.id.etLocalPath).setText(path)
            } else {
                showStatus("⚠️ Could not read file path — paste it manually")
            }
        }
    }

    // Get real file path from URI
    // NOTE: Android 13+ content:// URIs may not expose _data column
    // We copy the file to app storage and return that path instead
    private fun getRealPath(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // Try _data column first (works on most file managers)
                    val fromData = contentResolver.query(
                        uri, arrayOf("_data"), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex("_data")
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
                    if (fromData != null) return fromData

                    // Android 13+ fallback: copy to app models dir via stream
                    // NOTE: This is the correct approach for scoped storage
                    val fileName = contentResolver.query(
                        uri, arrayOf("_display_name"), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "model_${System.currentTimeMillis()}.gguf"

                    val destFile = java.io.File(modelsDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists()) destFile.absolutePath else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRealPath failed: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        super.onDestroy()
    }
}
