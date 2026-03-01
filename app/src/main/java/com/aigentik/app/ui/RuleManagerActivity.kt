package com.aigentik.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.core.AigentikSettings
import com.aigentik.app.core.RuleEngine

// RuleManagerActivity v1.0
// GUI for viewing and managing SMS + Email routing rules.
// Rules control how Aigentik handles messages:
//   SPAM     → silently ignore the message
//   AUTO_REPLY → auto-reply using AI
//   REVIEW   → notify owner but don't auto-reply
//
// Rules are stored in RuleEngine (JSON → Room in v1.4.6+).
// Changes take effect immediately; no app restart required.
class RuleManagerActivity : AppCompatActivity() {

    private lateinit var layoutSmsRules: LinearLayout
    private lateinit var layoutEmailRules: LinearLayout
    private lateinit var tvRuleStatus: TextView

    // SMS add form
    private lateinit var etSmsDescription: EditText
    private lateinit var etSmsConditionValue: EditText
    private lateinit var rgSmsConditionType: RadioGroup
    private lateinit var rgSmsAction: RadioGroup

    // Email add form
    private lateinit var etEmailDescription: EditText
    private lateinit var etEmailConditionValue: EditText
    private lateinit var rgEmailConditionType: RadioGroup
    private lateinit var rgEmailAction: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        AigentikSettings.init(this)
        ThemeHelper.applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_manager)

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        layoutSmsRules   = findViewById(R.id.layoutSmsRules)
        layoutEmailRules = findViewById(R.id.layoutEmailRules)
        tvRuleStatus     = findViewById(R.id.tvRuleStatus)

        etSmsDescription     = findViewById(R.id.etSmsDescription)
        etSmsConditionValue  = findViewById(R.id.etSmsConditionValue)
        rgSmsConditionType   = findViewById(R.id.rgSmsConditionType)
        rgSmsAction          = findViewById(R.id.rgSmsAction)

        etEmailDescription    = findViewById(R.id.etEmailDescription)
        etEmailConditionValue = findViewById(R.id.etEmailConditionValue)
        rgEmailConditionType  = findViewById(R.id.rgEmailConditionType)
        rgEmailAction         = findViewById(R.id.rgEmailAction)

        // Add SMS rule button
        findViewById<Button>(R.id.btnAddSmsRule).setOnClickListener {
            addSmsRule()
        }

        // Add Email rule button
        findViewById<Button>(R.id.btnAddEmailRule).setOnClickListener {
            addEmailRule()
        }

        refreshRuleList()
    }

    private fun refreshRuleList() {
        populateRuleList(
            container  = layoutSmsRules,
            rulesText  = RuleEngine.listSmsRules(),
            isSms      = true
        )
        populateRuleList(
            container  = layoutEmailRules,
            rulesText  = RuleEngine.listEmailRules(),
            isSms      = false
        )
    }

    // Builds the rule list UI from the text returned by RuleEngine.list*Rules()
    private fun populateRuleList(container: LinearLayout, rulesText: String, isSms: Boolean) {
        container.removeAllViews()
        if (rulesText.startsWith("No ")) {
            val tv = buildLabel(rulesText, color = 0xFF556677.toInt())
            container.addView(tv)
            return
        }

        val lines = rulesText.lines()
        for (line in lines) {
            if (line.isBlank()) continue

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }

            // Rule text
            val tv = buildLabel(line, color = 0xFFCCDDEE.toInt())
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)

            // Delete button — extract rule identifier from the line
            // Format: "1. [ACTION] description"
            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 12f
                setTextColor(0xFFFF4444.toInt())
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(8, 0, 8, 0)
            }

            // Extract the rule description from the line (after the action bracket)
            // e.g. "1. [SPAM] Block spam number" → "Block spam number"
            val description = Regex("\\[.*?]\\s*(.+)").find(line)?.groupValues?.get(1)?.trim()
                ?: line.substringAfter(". ").trim()

            deleteBtn.setOnClickListener {
                val removed = RuleEngine.removeRule(description)
                showStatus(if (removed) "Rule removed." else "Could not find rule to remove.")
                refreshRuleList()
            }
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    private fun addSmsRule() {
        val description = etSmsDescription.text.toString().trim()
        val value       = etSmsConditionValue.text.toString().trim()

        if (description.isEmpty() || value.isEmpty()) {
            showStatus("Fill in both description and condition value.")
            return
        }

        val conditionType = when (rgSmsConditionType.checkedRadioButtonId) {
            R.id.rbSmsContains -> "message_contains"
            else               -> "from_number"
        }
        val action = when (rgSmsAction.checkedRadioButtonId) {
            R.id.rbSmsAutoReply -> RuleEngine.Action.AUTO_REPLY
            R.id.rbSmsReview    -> RuleEngine.Action.REVIEW
            else                -> RuleEngine.Action.SPAM
        }

        RuleEngine.addSmsRule(description, conditionType, value, action)
        etSmsDescription.setText("")
        etSmsConditionValue.setText("")
        showStatus("SMS rule added: [$action] $description")
        refreshRuleList()
    }

    private fun addEmailRule() {
        val description = etEmailDescription.text.toString().trim()
        val value       = etEmailConditionValue.text.toString().trim()

        // Promotional rule doesn't need a value
        val isPromo = rgEmailConditionType.checkedRadioButtonId == R.id.rbEmailPromo
        if (description.isEmpty() || (!isPromo && value.isEmpty())) {
            showStatus("Fill in description and condition value (not required for Promo).")
            return
        }

        val conditionType = when (rgEmailConditionType.checkedRadioButtonId) {
            R.id.rbEmailDomain  -> "domain"
            R.id.rbEmailSubject -> "subject_contains"
            R.id.rbEmailPromo   -> "promotional"
            else                -> "from"
        }
        val action = when (rgEmailAction.checkedRadioButtonId) {
            R.id.rbEmailAutoReply -> RuleEngine.Action.AUTO_REPLY
            R.id.rbEmailReview    -> RuleEngine.Action.REVIEW
            else                  -> RuleEngine.Action.SPAM
        }
        val conditionValue = if (isPromo) "promotional" else value

        RuleEngine.addEmailRule(description, conditionType, conditionValue, action)
        etEmailDescription.setText("")
        etEmailConditionValue.setText("")
        showStatus("Email rule added: [$action] $description")
        refreshRuleList()
    }

    private fun showStatus(msg: String) {
        tvRuleStatus.text = msg
        tvRuleStatus.setTextColor(0xFF00D4FF.toInt())
    }

    private fun buildLabel(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        this.textSize = 13f
        this.setTextColor(color)
        this.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        this.setPadding(0, 6, 0, 6)
    }
}
