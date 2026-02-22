package com.aigentik.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// RuleEngine v0.4 — port of email-rules.js and sms-rules.js
// Checks messages against saved rules
// Returns: AUTO_REPLY, REVIEW, SPAM, or DEFAULT
object RuleEngine {

    private const val TAG = "RuleEngine"

    enum class Action { AUTO_REPLY, REVIEW, SPAM, DEFAULT }

    data class Rule(
        val id: String,
        val description: String,
        val conditionType: String,
        val conditionValue: String,
        val action: Action,
        val addedBy: String = "owner",
        val createdAt: String = "",
        var matchCount: Int = 0
    )

    private val smsRules = mutableListOf<Rule>()
    private val emailRules = mutableListOf<Rule>()
    private lateinit var rulesDir: File

    fun init(context: Context) {
        rulesDir = File(context.filesDir, "rules")
        rulesDir.mkdirs()
        loadRules()
        Log.i(TAG, "RuleEngine loaded — SMS: ${smsRules.size} rules, Email: ${emailRules.size} rules")
    }

    // Check an SMS against all rules
    fun checkSms(sender: String, body: String): Pair<Action, Rule?> {
        val senderNorm = sender.filter { it.isDigit() }.takeLast(10)
        val bodyLower = body.lowercase()

        for (rule in smsRules) {
            val valNorm = rule.conditionValue.filter { it.isDigit() }.takeLast(10)
            val valLower = rule.conditionValue.lowercase()

            val matched = when (rule.conditionType) {
                "from_number" -> senderNorm == valNorm || senderNorm.contains(valNorm)
                "message_contains" -> bodyLower.contains(valLower)
                "any" -> bodyLower.contains(valLower) || senderNorm.contains(valNorm)
                else -> false
            }

            if (matched) {
                rule.matchCount++
                saveRules()
                Log.i(TAG, "SMS rule matched: ${rule.description} → ${rule.action}")
                return Pair(rule.action, rule)
            }
        }

        return Pair(Action.DEFAULT, null)
    }

    // Check an email against all rules
    fun checkEmail(from: String, subject: String, body: String): Pair<Action, Rule?> {
        val fromLower = from.lowercase()
        val subjectLower = subject.lowercase()
        val bodyLower = body.lowercase()

        // Auto-detect promotional emails
        val promoKeywords = listOf(
            "unsubscribe", "opt-out", "newsletter", "promotion",
            "no-reply", "noreply", "donotreply", "mailing list"
        )
        val isPromo = promoKeywords.any {
            fromLower.contains(it) || subjectLower.contains(it) || bodyLower.contains(it)
        }

        for (rule in emailRules) {
            val valLower = rule.conditionValue.lowercase()

            val matched = when (rule.conditionType) {
                "from" -> fromLower.contains(valLower)
                "domain" -> fromLower.contains("@$valLower") || fromLower.contains(valLower)
                "subject_contains" -> subjectLower.contains(valLower)
                "body_contains" -> bodyLower.contains(valLower)
                "promotional" -> isPromo
                "any" -> fromLower.contains(valLower) ||
                         subjectLower.contains(valLower) ||
                         bodyLower.contains(valLower)
                else -> false
            }

            if (matched) {
                rule.matchCount++
                saveRules()
                Log.i(TAG, "Email rule matched: ${rule.description} → ${rule.action}")
                return Pair(rule.action, rule)
            }
        }

        return Pair(Action.DEFAULT, null)
    }

    // Add a new SMS rule
    fun addSmsRule(
        description: String,
        conditionType: String,
        conditionValue: String,
        action: Action
    ): Rule {
        val rule = Rule(
            id = "sr_${System.currentTimeMillis()}",
            description = description,
            conditionType = conditionType,
            conditionValue = conditionValue,
            action = action
        )
        smsRules.add(0, rule) // Newer rules take priority
        saveRules()
        Log.i(TAG, "SMS rule added: $description → $action")
        return rule
    }

    // Add a new email rule
    fun addEmailRule(
        description: String,
        conditionType: String,
        conditionValue: String,
        action: Action
    ): Rule {
        val rule = Rule(
            id = "er_${System.currentTimeMillis()}",
            description = description,
            conditionType = conditionType,
            conditionValue = conditionValue,
            action = action
        )
        emailRules.add(0, rule)
        saveRules()
        Log.i(TAG, "Email rule added: $description → $action")
        return rule
    }

    // Remove rule by id or description
    fun removeRule(identifier: String): Boolean {
        val smsRemoved = smsRules.removeIf {
            it.id == identifier || it.description.lowercase().contains(identifier.lowercase())
        }
        val emailRemoved = emailRules.removeIf {
            it.id == identifier || it.description.lowercase().contains(identifier.lowercase())
        }
        if (smsRemoved || emailRemoved) saveRules()
        return smsRemoved || emailRemoved
    }

    fun listSmsRules(): String {
        if (smsRules.isEmpty()) return "No SMS rules set."
        return smsRules.mapIndexed { i, r ->
            "${i+1}. [${r.action}] ${r.description}"
        }.joinToString("\n")
    }

    fun listEmailRules(): String {
        if (emailRules.isEmpty()) return "No email rules set."
        return emailRules.mapIndexed { i, r ->
            "${i+1}. [${r.action}] ${r.description}"
        }.joinToString("\n")
    }

    private fun actionFromString(s: String) = when (s.uppercase()) {
        "AUTO_REPLY" -> Action.AUTO_REPLY
        "SPAM" -> Action.SPAM
        "REVIEW" -> Action.REVIEW
        else -> Action.DEFAULT
    }

    private fun loadRules() {
        loadRuleList("sms_rules.json", smsRules)
        loadRuleList("email_rules.json", emailRules)
    }

    private fun loadRuleList(filename: String, list: MutableList<Rule>) {
        try {
            val file = File(rulesDir, filename)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Rule(
                    id = obj.getString("id"),
                    description = obj.getString("description"),
                    conditionType = obj.getString("conditionType"),
                    conditionValue = obj.getString("conditionValue"),
                    action = actionFromString(obj.getString("action")),
                    addedBy = obj.optString("addedBy", "owner"),
                    matchCount = obj.optInt("matchCount", 0)
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $filename: ${e.message}")
        }
    }

    private fun saveRules() {
        saveRuleList("sms_rules.json", smsRules)
        saveRuleList("email_rules.json", emailRules)
    }

    private fun saveRuleList(filename: String, list: List<Rule>) {
        try {
            val arr = JSONArray()
            list.forEach { rule ->
                arr.put(JSONObject().apply {
                    put("id", rule.id)
                    put("description", rule.description)
                    put("conditionType", rule.conditionType)
                    put("conditionValue", rule.conditionValue)
                    put("action", rule.action.name)
                    put("addedBy", rule.addedBy)
                    put("matchCount", rule.matchCount)
                })
            }
            File(rulesDir, filename).writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Could not save $filename: ${e.message}")
        }
    }
}
