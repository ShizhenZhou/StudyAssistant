package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PromptRulesTest {
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
