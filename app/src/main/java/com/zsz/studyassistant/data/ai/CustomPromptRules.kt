package com.zsz.studyassistant.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections

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

internal const val MAX_PROMPT_RULES = 128
internal const val MAX_PROMPT_RULE_TEXT = 65536
internal const val MAX_PROMPT_RULES_ENCODED = 1024 * 1024

internal enum class PromptRuleValidationError(val key: String) {
    COUNT("promptRules.limit.count"),
    TEXT_LENGTH("promptRules.limit.text"),
    ENCODED_SIZE("promptRules.limit.total"),
    INVALID_RULE("promptRules.invalidRule")
}

internal class PromptRuleValidationException(val reason: PromptRuleValidationError) :
    IllegalArgumentException(reason.key)

private fun checkPromptRule(valid: Boolean, reason: PromptRuleValidationError) {
    if (!valid) throw PromptRuleValidationException(reason)
}

internal fun decodePromptRules(encoded: String?, legacy: String): List<CustomPromptRule> {
    // Absence alone means legacy. A deliberately saved empty list must stay empty.
    if (encoded == null) return if (legacy.isBlank()) emptyList()
        else listOf(CustomPromptRule("legacy-custom-prompt", legacy))
    checkPromptRule(encoded.length <= MAX_PROMPT_RULES_ENCODED, PromptRuleValidationError.ENCODED_SIZE)
    val settings = promptRulesJson.decodeFromString<PromptRuleSettings>(encoded)
    require(settings.version == 1)
    validatePromptRules(settings.rules)
    return settings.rules
}

internal fun encodePromptRules(rules: List<CustomPromptRule>): String {
    validatePromptRules(rules)
    return promptRulesJson.encodeToString(PromptRuleSettings(rules = rules)).also {
        checkPromptRule(it.length <= MAX_PROMPT_RULES_ENCODED, PromptRuleValidationError.ENCODED_SIZE)
    }
}

private fun validatePromptRules(rules: List<CustomPromptRule>) {
    checkPromptRule(rules.size <= MAX_PROMPT_RULES, PromptRuleValidationError.COUNT)
    checkPromptRule(rules.map { it.id }.distinct().size == rules.size, PromptRuleValidationError.INVALID_RULE)
    rules.forEach {
        checkPromptRule(it.id.isNotBlank() && it.id.length <= 128, PromptRuleValidationError.INVALID_RULE)
        checkPromptRule(it.text.length <= MAX_PROMPT_RULE_TEXT, PromptRuleValidationError.TEXT_LENGTH)
        checkPromptRule(it.categoryIds.size <= 1024 && it.categoryIds.all { id -> id > 0 }, PromptRuleValidationError.INVALID_RULE)
        checkPromptRule(it.allSubjects || it.categoryIds.isNotEmpty(), PromptRuleValidationError.INVALID_RULE)
    }
}

/** One immutable decoded snapshot, with at most eight compiled subject variants. */
internal class PromptRulesCache {
    private data class Source(val encoded: String?, val legacy: String)
    private var source: Source? = null
    private var rules: List<CustomPromptRule> = emptyList()
    private var subjects: List<Pair<Long, String>>? = null
    private val compiled = LinkedHashMap<Long?, String>(8, 0.75f, true)

    @Synchronized
    fun read(encoded: String?, legacy: String): List<CustomPromptRule> {
        val next = Source(encoded, legacy)
        if (source != next) {
            // Decode before replacing the cache: errors still surface without changing saved data.
            val decoded = decodePromptRules(encoded, legacy)
            rules = Collections.unmodifiableList(decoded.map { rule ->
                rule.copy(categoryIds = Collections.unmodifiableList(ArrayList(rule.categoryIds)))
            })
            source = next
            subjects = null
            compiled.clear()
        }
        return rules
    }

    @Synchronized
    fun compile(encoded: String?, legacy: String, categories: List<Category>, categoryId: Long?): String {
        val currentRules = read(encoded, legacy)
        val currentSubjects = categories.map { it.id to it.name }
        if (subjects != currentSubjects) {
            subjects = currentSubjects
            compiled.clear()
        }
        return compiled.getOrPut(categoryId) {
            compilePromptRules(currentRules, categories, categoryId)
        }.also {
            if (compiled.size > 8) compiled.remove(compiled.keys.first())
        }
    }
}

/** Local preferences, separate from question/backup formats. Retain the old key as a migration copy. */
class PromptRulesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun read(): List<CustomPromptRule> = synchronized(writeLock) {
        cache.read(prefs.getString("custom_prompt_rules_v1", null), prefs.getString("custom_prompt", "").orEmpty())
    }

    fun compile(categories: List<Category>, categoryId: Long?): String = synchronized(writeLock) {
        cache.compile(prefs.getString("custom_prompt_rules_v1", null),
            prefs.getString("custom_prompt", "").orEmpty(), categories, categoryId)
    }

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
        // Refresh on the saving IO thread; subsequent requests reuse the decoded snapshot.
        read()
    }

    fun needsLegacyReview(): Boolean = !prefs.getBoolean("custom_prompt_legacy_reviewed", false) &&
        read().any { it.id == "legacy-custom-prompt" }

    fun acknowledgeLegacyReview() = synchronized(writeLock) {
        if (!prefs.edit().putBoolean("custom_prompt_legacy_reviewed", true).commit()) {
            prefs.edit().putBoolean("custom_prompt_legacy_reviewed", false).apply()
            error("Cannot save prompt migration acknowledgment")
        }
    }

    private companion object {
        val writeLock = Any()
        val cache = PromptRulesCache()
    }
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
