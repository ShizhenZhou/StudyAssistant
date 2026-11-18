package com.zsz.studyassistant.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Portable instructions only. Never serialize preferences, local IDs, credentials or question data. */
@Serializable
internal data class PromptRuleBackup(
    val format: String,
    val version: Int,
    val rules: List<PortablePromptRule>
)

@Serializable
internal data class PortablePromptRule(
    val text: String,
    val enabled: Boolean,
    val allSubjects: Boolean,
    val subjectNames: List<String>
)

internal class PromptBackupException(val key: String) : IllegalArgumentException(key)

internal object PromptRuleBackupCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val FORMAT = "study-assistant-prompt-rules"
    private val json = Json { encodeDefaults = true }

    fun export(rules: List<CustomPromptRule>, categories: List<Category>): ByteArray {
        encodePromptRules(rules)
        val names = categories.associate { it.id to it.name }
        val scopedIds = rules.filterNot { it.allSubjects }.flatMap { it.categoryIds }.toSet()
        if (categories.filter { it.id in scopedIds }.distinctBy { it.id }
                .groupBy { it.name }.any { (_, subjects) -> subjects.size > 1 }) {
            throw PromptBackupException("promptBackup.ambiguousSubjects")
        }
        val portable = rules.map { rule ->
            val subjects = if (rule.allSubjects) emptyList() else rule.categoryIds.distinct().map { id ->
                names[id] ?: throw PromptBackupException("promptBackup.missingSubject")
            }.distinct()
            PortablePromptRule(rule.text, rule.enabled, rule.allSubjects, subjects)
        }
        val backup = PromptRuleBackup(FORMAT, 1, portable)
        validate(backup)
        return json.encodeToString(backup).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_BYTES) throw PromptBackupException("promptBackup.tooLarge")
        }
    }

    fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readAtMost(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw PromptBackupException("promptBackup.tooLarge")
        return bytes
    }

    fun decode(bytes: ByteArray): PromptRuleBackup {
        if (bytes.size > MAX_BYTES) throw PromptBackupException("promptBackup.tooLarge")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return json.decodeFromString<PromptRuleBackup>(text).also(::validate)
        } catch (error: PromptBackupException) { throw error }
        catch (_: Exception) { throw PromptBackupException("promptBackup.invalid") }
    }

    private fun validate(backup: PromptRuleBackup) {
        if (backup.format != FORMAT || backup.version != 1) throw PromptBackupException("promptBackup.invalid")
        if (backup.rules.size > MAX_PROMPT_RULES) throw PromptBackupException("promptRules.limit.count")
        backup.rules.forEach { rule ->
            if (rule.text.length > MAX_PROMPT_RULE_TEXT) throw PromptBackupException("promptRules.limit.text")
            if (rule.subjectNames.size > 1024 || rule.subjectNames.any { it.isBlank() || it.length > 1024 } ||
                rule.subjectNames.distinct().size != rule.subjectNames.size ||
                (rule.allSubjects && rule.subjectNames.isNotEmpty()) ||
                (!rule.allSubjects && rule.subjectNames.isEmpty())) {
                throw PromptBackupException("promptBackup.invalid")
            }
        }
    }

    fun sourceNames(backup: PromptRuleBackup): List<String> = backup.rules.flatMap { it.subjectNames }.distinct()

    /** Exact, unique name matches are suggestions and remain visible in the confirmation preview. */
    fun suggestMapping(backup: PromptRuleBackup, categories: List<Category>): Map<String, Long> =
        sourceNames(backup).mapNotNull { name ->
            categories.singleOrNull { it.name == name && it.id > 0 }?.let { name to it.id }
        }.toMap()

    fun resolve(backup: PromptRuleBackup, mapping: Map<String, Long>, categories: List<Category>): List<CustomPromptRule> {
        validate(backup)
        val currentIds = categories.map { it.id }.filter { it > 0 }.toSet()
        if (sourceNames(backup).any { mapping[it] !in currentIds }) {
            throw PromptBackupException("promptBackup.mapRequired")
        }
        return backup.rules.map { rule ->
            CustomPromptRule(UUID.randomUUID().toString(), rule.text, rule.enabled, rule.allSubjects,
                rule.subjectNames.map { mapping.getValue(it) }.distinct())
        }
    }
}
