package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class NotebookSortingTest {
    @Test fun backgroundProjectionKeepsOrderingCountsAndIntersectsFilters() = kotlinx.coroutines.runBlocking {
        val rows = listOf(q("one", 1).copy(categoryId = 10), q("two", 2), q("three", 3).copy(categoryId = 10))
        val relations = listOf(QuestionTag("one", 20), QuestionTag("three", 21))
        val projected = com.zsz.studyassistant.ui.projectNotebook(rows, relations, false, 10, setOf(20, 21), setOf("three"))
        assertEquals(listOf("three"), projected.rows.map { it.id })
        assertEquals(2, projected.counts[10])
        assertEquals(1, projected.counts[UNCATEGORIZED_ORDER_ID])
        assertEquals(listOf(21L), projected.tags["three"])
        assertEquals(listOf("two"), com.zsz.studyassistant.ui.projectNotebook(rows, relations, true, null, emptySet(), null).rows.map { it.id })
    }
    private fun q(id: String, time: Long) = QuestionPreview(id=id,text=id,answer="",createdAt=time)
    private val questions = listOf(q("one",100),q("two",300),q("three",200))
    private fun ids(mode: NotebookSortMode, reviews: Map<String,Review> = emptyMap()) =
        NotebookSorting.sort(questions,reviews,mode).map { it.id }

    @Test fun newestAndOldest() {
        assertEquals(listOf("two","three","one"),ids(NotebookSortMode.NEWEST))
        assertEquals(listOf("one","three","two"),ids(NotebookSortMode.OLDEST))
    }
    @Test fun nextReviewMissingLast() {
        assertEquals(listOf("three","one","two"),ids(NotebookSortMode.NEXT_REVIEW,
            mapOf("one" to Review("one",nextReviewAt=5000),"three" to Review("three",nextReviewAt=1000))))
    }
    @Test fun mostReviewed() {
        assertEquals(listOf("three","one","two"),ids(NotebookSortMode.MOST_REVIEWED,
            mapOf("one" to Review("one",reviewCount=2),"three" to Review("three",reviewCount=7))))
    }
    @Test fun weakestMissingFirst() {
        assertEquals(listOf("two","three","one"),ids(NotebookSortMode.WEAKEST,
            mapOf("one" to Review("one",intervalStep=5),"three" to Review("three",intervalStep=1))))
    }
    @Test fun doesNotMutateOrLoseQuestions() {
        val before=questions.toList()
        NotebookSortMode.menuOrder.forEach {
            val sorted=NotebookSorting.sort(questions,emptyMap(),it)
            assertEquals(questions.size,sorted.size)
            assertEquals(questions.toSet(),sorted.toSet())
        }
        assertEquals(before,questions)
    }
    @Test fun emptyAndSingleton() {
        assertTrue(NotebookSorting.sort(emptyList(),emptyMap(),NotebookSortMode.NEWEST).isEmpty())
        assertEquals(listOf(questions.first()),NotebookSorting.sort(listOf(questions.first()),emptyMap(),NotebookSortMode.WEAKEST))
    }
    @Test fun modesAndPersistence() {
        assertEquals(NotebookSortMode.NEWEST,NotebookSortMode.DEFAULT)
        assertEquals(NotebookSortMode.WEAKEST,NotebookSortMode.fromId("weak"))
        assertEquals(NotebookSortMode.NEWEST,NotebookSortMode.fromId("missing"))
        assertEquals(NotebookSortMode.NEWEST,NotebookSortMode.fromId(null))
        assertEquals(5,NotebookSortMode.menuOrder.size)
    }
    @Test fun tiesUseOriginalTime() {
        assertEquals(listOf("two","three","one"),ids(NotebookSortMode.MOST_REVIEWED))
        assertEquals(ids(NotebookSortMode.MOST_REVIEWED),ids(NotebookSortMode.MOST_REVIEWED))
    }
}
