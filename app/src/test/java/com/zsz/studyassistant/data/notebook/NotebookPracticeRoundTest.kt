package com.zsz.studyassistant.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotebookPracticeRoundTest {
    @Test fun restoredRoundKeepsCursorAndRetriesOnlySelectedRatingsInOrder() {
        val ids = (1L..6L).map(QuestionIdentity::create)
        val partial = NotebookPracticeRound.start(ids)
            .advance(ids[0], PracticeRating.FORGOT)
            .advance(ids[1], PracticeRating.SKIPPED)
            .advance(ids[2], PracticeRating.FAMILIAR)
            .advance(ids[3], PracticeRating.VAGUE)
        val restored = Json.decodeFromString<NotebookPracticeRound>(Json.encodeToString(partial))
        restored.validate()
        assertEquals(ids[4], restored.items[restored.cursor].id)
        val afterDeletion = restored.advance(ids[4], null)
        assertEquals(ids[5], afterDeletion.items[afterDeletion.cursor].id)
        assertEquals(listOf(ids[0], ids[3]), afterDeletion.retryIds(false))
        assertEquals(listOf(ids[0], ids[1], ids[3]), afterDeletion.retryIds(true))

        val retry = NotebookPracticeRound.start(afterDeletion.retryIds(false))
        assertNotEquals(restored.roundId, retry.roundId)
        assertEquals(0, retry.cursor)
        retry.items.forEach { assertNull(it.rating) }
    }
}
