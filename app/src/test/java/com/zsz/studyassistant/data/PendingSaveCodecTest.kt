package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PendingSaveCodecTest {
    private val payload = SavePayload(QuestionIdentity.create(1234), 1234, "question", "answer", "[]")
    @Test fun roundTripRetainsAnswerIdentityAndBase() {
        val entry = PendingSave(SaveTarget.NOTEBOOK, payload, PendingSaveCodec.fingerprint(payload.copy(answer = "old")))
        assertEquals(entry, PendingSaveCodec.decode(PendingSaveCodec.encode(entry)))
    }
    @Test fun changedAnswerCannotMatchOriginalBase() {
        assertNotEquals(PendingSaveCodec.fingerprint(payload), PendingSaveCodec.fingerprint(payload.copy(answer = "new")))
    }
    @Test fun historyImagesRoundTripWithoutAndroidBase64() {
        val row = SolveSession(payload.id, 1234, 2000, "ask", "q", "a", imageBytes = byteArrayOf(0, 7, -1))
        val restored = SavePayload.history(row).historyRow(null)
        assertEquals(row.id, restored.id)
        assertArrayEquals(row.imageBytes, restored.imageBytes)
    }
    @Test fun invalidIdentityAndFormatAreRejected() {
        for (entry in listOf(PendingSave(SaveTarget.HISTORY, payload.copy(id = "12"), null),
            PendingSave(SaveTarget.HISTORY, payload, null, version = 2),
            PendingSave(SaveTarget.HISTORY, payload.copy(createdAt = 99), null))) {
            try { PendingSaveCodec.encode(entry); fail("Invalid journal accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unavailableBaseIsPreservedWithoutPretendingToMatch() {
        val entry = PendingSave(SaveTarget.NOTEBOOK, payload, "unavailable")
        assertEquals("unavailable", PendingSaveCodec.decode(PendingSaveCodec.encode(entry)).expected)
        assertNotEquals(entry.expected, PendingSaveCodec.fingerprint(payload))
    }
}
