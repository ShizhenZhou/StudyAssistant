package com.zsz.studyassistant.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CustomPromptRule(
    val id: String,
    val text: String,
    val enabled: Boolean = true,
    val allSubjects: Boolean = true,
    val categoryIds: List<Long> = emptyList()
)

@Serializable
private data class PromptRuleSettings(val version: Int = 1, val rules: List<CustomPromptRule>)

private val promptRulesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodePromptRules(encoded: String?, legacy: String): List<CustomPromptRule> {
    // Absence alone means legacy. A deliberately saved empty list must stay empty.
    if (encoded == null) return if (legacy.isBlank()) emptyList()
        else listOf(CustomPromptRule("legacy-custom-prompt", legacy))
    require(encoded.length <= 1024 * 1024)
    val settings = promptRulesJson.decodeFromString<PromptRuleSettings>(encoded)
    require(settings.version == 1)
    validatePromptRules(settings.rules)
    return settings.rules
}

internal fun encodePromptRules(rules: List<CustomPromptRule>): String {
    validatePromptRules(rules)
    return promptRulesJson.encodeToString(PromptRuleSettings(rules = rules)).also {
        require(it.length <= 1024 * 1024)
    }
}

private fun validatePromptRules(rules: List<CustomPromptRule>) {
    require(rules.size <= 128 && rules.map { it.id }.distinct().size == rules.size)
    rules.forEach {
        require(it.id.isNotBlank() && it.id.length <= 128)
        require(it.text.length <= 65536)
        require(it.categoryIds.size <= 1024 && it.categoryIds.all { id -> id > 0 })
        require(it.allSubjects || it.categoryIds.isNotEmpty())
    }
}

/** Local preferences, separate from question/backup formats. Retain the old key as a migration copy. */
class PromptRulesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun read(): List<CustomPromptRule> = decodePromptRules(
        prefs.getString("custom_prompt_rules_v1", null), prefs.getString("custom_prompt", "").orEmpty())

    /** Call on IO. Never replace an unreadable/unknown format or report a failed write as saved. */
    fun save(rules: List<CustomPromptRule>) = synchronized(writeLock) {
        read()
        val encoded = encodePromptRules(rules)
        val previous = prefs.getString("custom_prompt_rules_v1", null)
        val reviewed = prefs.getBoolean("custom_prompt_legacy_reviewed", false)
        if (!prefs.edit().putString("custom_prompt_rules_v1", encoded)
                .putBoolean("custom_prompt_legacy_reviewed", true).commit()) {
            // SharedPreferences updates memory even when disk commit fails; restore the previous view.
            prefs.edit().putString("custom_prompt_rules_v1", previous)
                .putBoolean("custom_prompt_legacy_reviewed", reviewed).apply()
            error("Cannot save prompt rules")
        }
    }

    fun needsLegacyReview(): Boolean = !prefs.getBoolean("custom_prompt_legacy_reviewed", false) &&
        read().any { it.id == "legacy-custom-prompt" }

    fun acknowledgeLegacyReview() = synchronized(writeLock) {
        if (!prefs.edit().putBoolean("custom_prompt_legacy_reviewed", true).commit()) {
            prefs.edit().putBoolean("custom_prompt_legacy_reviewed", false).apply()
            error("Cannot save prompt migration acknowledgment")
        }
    }

    private companion object { val writeLock = Any() }
}

/** No category inference request: unknown subjects receive explicitly conditional requirements. */
internal fun compilePromptRules(rules: List<CustomPromptRule>, categories: List<Category>, categoryId: Long?): String {
    val names = categories.associate { it.id to it.name }
    val selected = rules.filter { rule -> rule.enabled && rule.text.isNotBlank() &&
        (rule.allSubjects || if (categoryId != null) categoryId in rule.categoryIds && categoryId in names
            else rule.categoryIds.any { it in names }) }
    if (selected.isEmpty()) return ""
    return buildString {
        append("以下是用户分条要求。只应用适用的条目；特定科目要求与全部科目要求冲突时，优先特定科目。\n")
        if (categoryId == null) append("当前尚未确认科目，请根据题意判断；不属于所列科目时不要应用对应条目，也不要为此单独提问或输出分类过程。\n")
        selected.forEachIndexed { index, rule ->
            append(index + 1).append(". ")
            if (rule.allSubjects) append("[全部科目]\n")
            else if (categoryId == null) {
                val subjects = rule.categoryIds.distinct().mapNotNull(names::get)
                append("[仅当题目属于以下科目之一时适用：")
                append(promptRulesJson.encodeToString(subjects)).append("]\n")
            } else append("[当前科目要求]\n")
            append(rule.text.trim()).append("\n\n")
        }
    }.trimEnd()
}
