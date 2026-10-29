package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class CategoryOrderingTest {
    @Test fun emptyUncategorizedComesLastAndNamedSubjectsSortByCountWithStableTies() {
        assertEquals(listOf(2L, 3L, 1L, UNCATEGORIZED_ORDER_ID),
            categoryChipOrder(listOf(1, 2, 3), mapOf(1L to 1, 2L to 5, 3L to 5), false, emptyList()))
    }
    @Test fun nonemptyUncategorizedRemainsBeforeNamedSubjects() {
        assertEquals(listOf(UNCATEGORIZED_ORDER_ID, 2L, 1L),
            categoryChipOrder(listOf(1, 2), mapOf(UNCATEGORIZED_ORDER_ID to 1, 2L to 8), false, emptyList()))
    }
    @Test fun customOrderIgnoresDeletedAndDuplicateIdsAndAppendsNewSubjects() {
        assertEquals(listOf(1L, UNCATEGORIZED_ORDER_ID, 3L, 2L),
            categoryChipOrder(listOf(1, 2, 3), mapOf(3L to 5), true, listOf(99, 1, 1, UNCATEGORIZED_ORDER_ID)))
    }
    @Test fun disablingCustomOrderRestoresCountOrderWithoutChangingSavedOrder() {
        val saved = listOf(1L, 2L, UNCATEGORIZED_ORDER_ID)
        assertEquals(listOf(2L, 1L, UNCATEGORIZED_ORDER_ID),
            categoryChipOrder(listOf(1, 2), mapOf(2L to 5), false, saved))
        assertEquals(listOf(1L, 2L, UNCATEGORIZED_ORDER_ID), saved)
    }
    @Test fun movingUpDownAndUncategorizedPreservesEveryKeyAndIgnoresStaleHandles() {
        val original = listOf(1L, 2L, UNCATEGORIZED_ORDER_ID)
        assertEquals(listOf(2L, UNCATEGORIZED_ORDER_ID, 1L), moveCategoryOrder(original, 1, UNCATEGORIZED_ORDER_ID))
        assertEquals(listOf(UNCATEGORIZED_ORDER_ID, 1L, 2L), moveCategoryOrder(original, UNCATEGORIZED_ORDER_ID, 1))
        assertEquals(original, moveCategoryOrder(original, 99, 1))
    }
}
