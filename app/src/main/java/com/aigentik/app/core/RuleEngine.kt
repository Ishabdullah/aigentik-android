package com.aigentik.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

// RuleEngine v0.5
// v0.5: Migrated from JSON-backed storage (sms_rules.json + email_rules.json)
//   to Room/SQLite (RuleDatabase). Benefits:
//   - Atomic writes via Room — no corruption risk during concurrent saves
//   - Indexed queries — fast rule lookup even with many rules
//   - One-time migration from JSON files on first launch; originals renamed *.migrated
//   - In-memory rule lists retained for fast match evaluation (same as before)
//   - matchCount incremented via DAO (atomic SQL UPDATE instead of file rewrite)
// v0.4: port of email-rules.js and sms-rules.js — checks messages against saved rules
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

    // In-memory rule lists — loaded from Room on init for fast evaluation
    private val smsRules   = mutableListOf<Rule>()
    private val emailRules = mutableListOf<Rule>()
    private var dao: RuleDao? = null
    // Background scope for async DAO writes — prevents main thread Room access crashes
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        val db = RuleDatabase.getInstance(context)
        dao = db.ruleDao()

        // One-time migration from legacy JSON files
        migrateFromJsonIfNeeded(context)

        // Load from Room into in-memory cache
        loadFromRoom()
        Log.i(TAG, "RuleEngine ready (Room) — SMS: ${smsRules.size}, Email: ${emailRules.size} rules")
    }

    // Check an SMS against all rules
    fun checkSms(sender: String, body: String): Pair<Action, Rule?> {
        val senderNorm = sender.filter { it.isDigit() }.takeLast(10)
        val bodyLower  = body.lowercase()

        for (rule in smsRules) {
            val valNorm  = rule.conditionValue.filter { it.isDigit() }.takeLast(10)
            val valLower = rule.conditionValue.lowercase()

            val matched = when (rule.conditionType) {
                "from_number"      -> senderNorm == valNorm || senderNorm.contains(valNorm)
                "message_contains" -> bodyLower.contains(valLower)
                "any"              -> bodyLower.contains(valLower) || senderNorm.contains(valNorm)
                else               -> false
            }

            if (matched) {
                rule.matchCount++
                dao?.incrementMatchCount(rule.id)
                Log.i(TAG, "SMS rule matched: ${rule.description} → ${rule.action}")
                return Pair(rule.action, rule)
            }
        }
        return Pair(Action.DEFAULT, null)
    }

    // Check an email against all rules
    fun checkEmail(from: String, subject: String, body: String): Pair<Action, Rule?> {
        val fromLower    = from.lowercase()
        val subjectLower = subject.lowercase()
        val bodyLower    = body.lowercase()

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
                "from"             -> fromLower.contains(valLower)
                "domain"           -> fromLower.contains("@$valLower") || fromLower.contains(valLower)
                "subject_contains" -> subjectLower.contains(valLower)
                "body_contains"    -> bodyLower.contains(valLower)
                "promotional"      -> isPromo
                "any"              -> fromLower.contains(valLower) ||
                                      subjectLower.contains(valLower) ||
                                      bodyLower.contains(valLower)
                else               -> false
            }

            if (matched) {
                rule.matchCount++
                dao?.incrementMatchCount(rule.id)
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
            id             = "sr_${System.currentTimeMillis()}",
            description    = description,
            conditionType  = conditionType,
            conditionValue = conditionValue,
            action         = action
        )
        smsRules.add(0, rule) // Newer rules take priority
        val entity = rule.toEntity("sms")
        ioScope.launch { dao?.insert(entity) }
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
            id             = "er_${System.currentTimeMillis()}",
            description    = description,
            conditionType  = conditionType,
            conditionValue = conditionValue,
            action         = action
        )
        emailRules.add(0, rule)
        val entity = rule.toEntity("email")
        ioScope.launch { dao?.insert(entity) }
        Log.i(TAG, "Email rule added: $description → $action")
        return rule
    }

    // Remove rule by id or description (searches both SMS and email lists)
    fun removeRule(identifier: String): Boolean {
        val smsRemoved   = smsRules.removeIf   { it.id == identifier || it.description.lowercase().contains(identifier.lowercase()) }
        val emailRemoved = emailRules.removeIf { it.id == identifier || it.description.lowercase().contains(identifier.lowercase()) }
        if (smsRemoved)   ioScope.launch { dao?.deleteByIdentifier("sms",   identifier) }
        if (emailRemoved) ioScope.launch { dao?.deleteByIdentifier("email", identifier) }
        return smsRemoved || emailRemoved
    }

    fun listSmsRules(): String {
        if (smsRules.isEmpty()) return "No SMS rules set."
        return smsRules.mapIndexed { i, r -> "${i+1}. [${r.action}] ${r.description}" }.joinToString("\n")
    }

    fun listEmailRules(): String {
        if (emailRules.isEmpty()) return "No email rules set."
        return emailRules.mapIndexed { i, r -> "${i+1}. [${r.action}] ${r.description}" }.joinToString("\n")
    }

    // ─── Private / internal ───────────────────────────────────────────────────

    private fun loadFromRoom() {
        smsRules.clear()
        emailRules.clear()
        dao?.getAllSmsRules()?.forEach   { smsRules.add(it.toRule()) }
        dao?.getAllEmailRules()?.forEach { emailRules.add(it.toRule()) }
    }

    private fun actionFromString(s: String) = when (s.uppercase()) {
        "AUTO_REPLY" -> Action.AUTO_REPLY
        "SPAM"       -> Action.SPAM
        "REVIEW"     -> Action.REVIEW
        else         -> Action.DEFAULT
    }

    // One-time migration from legacy JSON files
    private fun migrateFromJsonIfNeeded(context: Context) {
        val rulesDir = File(context.filesDir, "rules")
        if (!rulesDir.exists()) return

        migrateFile(File(rulesDir, "sms_rules.json"), "sms")
        migrateFile(File(rulesDir, "email_rules.json"), "email")
    }

    private fun migrateFile(file: File, ruleType: String) {
        if (!file.exists()) return
        Log.i(TAG, "Migrating ${file.name} → Room...")
        try {
            val arr = JSONArray(file.readText())
            val entities = mutableListOf<RuleEntity>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entities.add(RuleEntity(
                    id             = obj.getString("id"),
                    ruleType       = ruleType,
                    description    = obj.getString("description"),
                    conditionType  = obj.getString("conditionType"),
                    conditionValue = obj.getString("conditionValue"),
                    action         = obj.getString("action"),
                    addedBy        = obj.optString("addedBy", "owner"),
                    matchCount     = obj.optInt("matchCount", 0)
                ))
            }
            dao?.insertAll(entities)
            file.renameTo(File(file.parent, "${file.name}.migrated"))
            Log.i(TAG, "Migrated ${entities.size} $ruleType rules to Room")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed for ${file.name}: ${e.message}")
        }
    }
}

// ── Extension helpers: Rule ↔ RuleEntity ────────────────────────────────────

fun RuleEngine.Rule.toEntity(ruleType: String): RuleEntity = RuleEntity(
    id             = id,
    ruleType       = ruleType,
    description    = description,
    conditionType  = conditionType,
    conditionValue = conditionValue,
    action         = action.name,
    addedBy        = addedBy,
    createdAt      = createdAt,
    matchCount     = matchCount
)

fun RuleEntity.toRule(): RuleEngine.Rule = RuleEngine.Rule(
    id             = id,
    description    = description,
    conditionType  = conditionType,
    conditionValue = conditionValue,
    action         = try { RuleEngine.Action.valueOf(action) } catch (e: Exception) { RuleEngine.Action.DEFAULT },
    addedBy        = addedBy,
    createdAt      = createdAt,
    matchCount     = matchCount
)
