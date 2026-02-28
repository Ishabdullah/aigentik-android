package com.aigentik.app.core

import androidx.room.Entity
import androidx.room.PrimaryKey

// RuleEntity v1.0
// Room entity replacing JSON-backed RuleEngine storage.
// ruleType distinguishes "sms" from "email" rules within the same table.

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val ruleType: String,       // "sms" or "email"
    val description: String,
    val conditionType: String,
    val conditionValue: String,
    val action: String,         // RuleEngine.Action enum name
    val addedBy: String = "owner",
    val createdAt: String = "",
    val matchCount: Int = 0
)
