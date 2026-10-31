package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PendingSaveCodecTest {
    @Test fun quotedConversationRetainsFullContentAcrossSaveAndRestore() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val quote = com.zsz.studyassistant.QuotedFollowUp("Formula: \\(x^2\\)", "Why?")
        val message = com.zsz.studyassistant.ChatItem(2, "user", quote.text + "\n\n" + quote.question, quote = quote)
        val encoded = json.encodeToString(com.zsz.studyassistant.ChatItem.serializer(), message)
        val entry = PendingSave(SaveTarget.NOTEBOOK, payload.copy(conversationJson = "[$encoded]"), PendingSaveCodec.fingerprint(payload))
        val restored = PendingSaveCodec.decode(PendingSaveCodec.encode(entry))
        assertEquals(entry.payload.conversationJson, restored.payload.conversationJson)
        assertEquals(message, json.decodeFromString(com.zsz.studyassistant.ChatItem.serializer(), encoded))
        val legacy = """{"id":2,"role":"user","content":"old follow-up"}"""
        assertNull(json.decodeFromString(com.zsz.studyassistant.ChatItem.serializer(), legacy).quote)
        // Older clients ignore quote metadata and continue reading the complete content.
        val legacyView = json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        assertEquals(quote.text + "\n\n" + quote.question,
            (legacyView["content"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
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
