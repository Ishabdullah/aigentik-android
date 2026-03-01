package com.aigentik.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.aigentik.app.BuildConfig
import com.aigentik.app.R
import com.aigentik.app.ai.AiEngine
import com.aigentik.app.auth.GoogleAuthManager
import com.aigentik.app.core.AigentikSettings

// SettingsHubActivity v1.0
// Clean settings navigation hub — replaces the navigation drawer.
// Each row opens the appropriate sub-screen.
class SettingsHubActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        setContentView(R.layout.activity_settings_hub)

        container = findViewById(R.id.settingsContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        buildRows()
    }

    override fun onResume() {
        super.onResume()
        // Refresh dynamic subtitles when returning from a sub-screen
        container.removeAllViews()
        buildRows()
    }

    private fun buildRows() {
        val ownerName = AigentikSettings.ownerName.ifBlank { "Not set" }
        val email = GoogleAuthManager.getSignedInEmail(this) ?: "Not signed in"
        val themeLabel = when (AigentikSettings.themeMode) {
            1 -> "Light"
            2 -> "Dark"
            else -> "System"
        }
        val modelInfo = if (AiEngine.isReady()) AiEngine.getModelInfo() else "No model loaded"

        addRow("Profile & Account", ownerName) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        addRow("Google Account", email) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        addRow("Appearance", themeLabel) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        addDivider()
        addRow("AI Model", modelInfo) {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        addRow("Message Rules", "SMS and email routing") {
            startActivity(Intent(this, RuleManagerActivity::class.java))
        }
        addRow("AI Diagnostic", "Performance, Gmail health") {
            startActivity(Intent(this, AiDiagnosticActivity::class.java))
        }
        addDivider()
        addRow("About", "v${BuildConfig.VERSION_NAME}") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Aigentik")
                .setMessage("Version ${BuildConfig.VERSION_NAME}\n\nPrivacy-first on-device AI assistant.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun addRow(title: String, subtitle: String, onClick: () -> Unit) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = title
        view.findViewById<TextView>(R.id.tvRowSubtitle).text = subtitle
        view.setOnClickListener { onClick() }
        container.addView(view)
    }

    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            // NOTE: android.R.attr.listDivider is an attribute ID, not a drawable resource ID.
            // setBackgroundResource() calls resources.getDrawable(id) and crashes with
            // Resources.NotFoundException when passed an attr ID. Use setBackgroundColor() directly.
            setBackgroundColor(0x1AFFFFFF.toInt())
        }
        container.addView(divider)
    }
}
