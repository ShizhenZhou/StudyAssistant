package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PromptRulesTest {
    @Test fun validationExplainsCountTextAndSerializedLimitsWithoutTruncating() {
        val maximumText = "文".repeat(MAX_PROMPT_RULE_TEXT)
        val rule = CustomPromptRule("one", maximumText)
        assertEquals(maximumText, decodePromptRules(encodePromptRules(listOf(rule)), "").single().text)
        encodePromptRules(List(MAX_PROMPT_RULES) { CustomPromptRule("$it", "ok") })
        assertEquals(PromptRuleValidationError.COUNT, assertThrows(PromptRuleValidationException::class.java) {
            encodePromptRules(List(MAX_PROMPT_RULES + 1) { CustomPromptRule("$it", "ok") })
        }.reason)
        assertEquals(PromptRuleValidationError.TEXT_LENGTH, assertThrows(PromptRuleValidationException::class.java) {
            encodePromptRules(listOf(rule.copy(text = maximumText + "文")))
        }.reason)
        // Escaping counts toward the serialized limit even when each raw instruction fits.
        val escapedText = "\"".repeat(MAX_PROMPT_RULE_TEXT)
        assertEquals(PromptRuleValidationError.ENCODED_SIZE, assertThrows(PromptRuleValidationException::class.java) {
            encodePromptRules(List(8) { CustomPromptRule("$it", escapedText) })
        }.reason)
        assertEquals(MAX_PROMPT_RULE_TEXT, rule.text.length)
    }

    @Test fun cachedRulesAreImmutableAndLegacyAndEncodedChangesInvalidateThem() {
        val cache = PromptRulesCache()
        val legacy = cache.read(null, "  original\n ")
        assertSame(legacy, cache.read(null, "  original\n "))
        assertEquals("  original\n ", legacy.single().text)
        assertEquals("changed", cache.read(null, "changed").single().text)
        val encoded = encodePromptRules(listOf(CustomPromptRule("scoped", "text", allSubjects = false, categoryIds = listOf(1))))
        val decoded = cache.read(encoded, "changed")
        assertSame(decoded, cache.read(encoded, "changed"))
        assertThrows(UnsupportedOperationException::class.java) { (decoded as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (decoded.single().categoryIds as MutableList<*>).clear() }
        assertTrue(cache.read(encodePromptRules(emptyList()), "changed").isEmpty())
    }

    @Test fun compiledCacheTracksRuleChangesSubjectRenameDeletionAndSelectedSubject() {
        val cache = PromptRulesCache()
        val rule = CustomPromptRule("scoped", "math-only", allSubjects = false, categoryIds = listOf(1))
        val encoded = encodePromptRules(listOf(rule))
        val subjects = listOf(Category(id = 1, name = "Math"))
        val original = cache.compile(encoded, "", subjects, null)
        assertSame(original, cache.compile(encoded, "", subjects.toList(), null))
        assertTrue(cache.compile(encoded, "", listOf(Category(id = 1, name = "Renamed")), null).contains("Renamed"))
        assertFalse(cache.compile(encoded, "", subjects, 1).contains("Math"))
        assertEquals("", cache.compile(encoded, "", subjects, 2))
        assertEquals("", cache.compile(encoded, "", emptyList(), null))
        assertEquals("", cache.compile(encodePromptRules(listOf(rule.copy(enabled = false))), "", subjects, null))
        assertEquals("", cache.compile(encodePromptRules(listOf(rule.copy(categoryIds = listOf(2)))), "", subjects, 1))
    }

    @Test fun legacyRequirementsSurviveAndSavedEmptyListDoesNotResurrectThem() {
        val original = "  分步讲解\n保留公式  "
        val migrated = decodePromptRules(null, original)
        assertEquals(original, migrated.single().text)
        assertTrue(migrated.single().enabled && migrated.single().allSubjects)
        assertEquals(migrated, decodePromptRules(encodePromptRules(migrated), "ignored"))
        assertTrue(decodePromptRules(encodePromptRules(emptyList()), original).isEmpty())
        assertThrows(Exception::class.java) { decodePromptRules("{broken", original) }
    }

    @Test fun onlyEnabledMatchingRulesReachKnownSubjectAndDeletedIdsNeverBecomeGlobal() {
        val rules = listOf(
            CustomPromptRule("all", "global"),
            CustomPromptRule("math", "math-only", allSubjects = false, categoryIds = listOf(1)),
            CustomPromptRule("physics", "physics-only", allSubjects = false, categoryIds = listOf(2)),
            CustomPromptRule("off", "disabled", enabled = false),
            CustomPromptRule("gone", "orphaned", allSubjects = false, categoryIds = listOf(3)))
        val subjects = listOf(Category(id = 1, name = "数学（改名后）"), Category(id = 2, name = "物理"))
        val known = compilePromptRules(rules, subjects, 1)
        assertTrue(known.contains("global") && known.contains("math-only"))
        assertFalse(known.contains("physics-only") || known.contains("disabled") || known.contains("orphaned"))
        val unknown = compilePromptRules(rules, subjects, null)
        assertTrue(unknown.contains("数学（改名后）") && unknown.contains("物理"))
        assertTrue(unknown.contains("仅当"))
        assertFalse(unknown.contains("disabled") || unknown.contains("orphaned"))
        val deleted = compilePromptRules(rules, subjects, 3)
        assertTrue(deleted.contains("global"))
        assertFalse(deleted.contains("math-only") || deleted.contains("orphaned"))
    }
}
