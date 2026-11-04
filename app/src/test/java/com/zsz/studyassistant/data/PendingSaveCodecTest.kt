package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class PendingSaveCodecTest {
    @Test fun unsavedDraftCannotOverwriteLaterNotebookEdits() {
        val base = listOf(com.zsz.studyassistant.ChatItem(0, "question", "q"),
            com.zsz.studyassistant.ChatItem(1, "assistant", "a"))
        val draft = ConversationDraft(payload.id, 1234, java.util.UUID.randomUUID().toString(),
            "model", "followup", "ask", "q", false, true, false, true, null,
            emptyList(), base, emptyList())
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.zsz.studyassistant.ChatItem.serializer()), base)
        val row = Question(id=payload.id, createdAt=1234, text="q", answer="a", conversationJson=encoded)
        assertTrue(ConversationDraftCodec.matchesNotebook(draft, row))
        assertFalse(ConversationDraftCodec.matchesNotebook(draft, row.copy(answer="edited")))
        assertFalse(ConversationDraftCodec.matchesNotebook(draft, row.copy(conversationJson="[]")))
        assertFalse(ConversationDraftCodec.matchesNotebook(draft, row.copy(deleted=true)))
        assertTrue(ConversationDraftCodec.matchesNotebook(draft.copy(base=emptyList(), completedChats=base), row))
    }
    @Test fun gradingRolesSurviveRestoreAndProtectOnlyOriginalAnswer() {
        val original = com.zsz.studyassistant.ChatItem(0, "question", "conditions", listOf("one", "two", "three"), gradePart="question")
        val answer = com.zsz.studyassistant.ChatItem(1, "user", "answer", listOf("handwriting"), gradePart="answer")
        val result = com.zsz.studyassistant.ChatItem(2, "assistant", "grade")
        val follow = com.zsz.studyassistant.ChatItem(3, "user", "followup", listOf("new-image"))
        val serializer = kotlinx.serialization.builtins.ListSerializer(com.zsz.studyassistant.ChatItem.serializer())
        val items = listOf(original, answer, result, follow)
        val restored = kotlinx.serialization.json.Json.decodeFromString(serializer,
            kotlinx.serialization.json.Json.encodeToString(serializer, items))
        assertEquals(items, restored)
        assertEquals(setOf(0, 1, 2), com.zsz.studyassistant.protectedMessageIndices(restored))
        assertEquals(listOf("one", "two", "three"), questionImageBase64(
            kotlinx.serialization.json.Json.encodeToString(serializer, restored)))
        val input = gradingInput(false, "conditions", restored, emptyList())!!
        assertEquals(GradingInput("conditions", original.images!!, "handwriting"), input)
        val parts = StudyAssistant.gradeUserMessage(input, AiLang.DEFAULT).content as kotlinx.serialization.json.JsonArray
        val sentImages = parts.drop(1).map {
            val image = (it as kotlinx.serialization.json.JsonObject)["image_url"] as kotlinx.serialization.json.JsonObject
            (image.getValue("url") as kotlinx.serialization.json.JsonPrimitive).content.substringAfter("base64,")
        }
        assertEquals(listOf("one", "two", "three", "handwriting"), sentImages)
        val followUp = StudyAssistant.userMessageWithEncodedImages("conditions", input.questionImages)
            .content as kotlinx.serialization.json.JsonArray
        assertEquals(parts.subList(1, 4), followUp.drop(1))
        assertFalse(gradingInput(true, "conditions", listOf(original, follow), listOf("one"))!!.canGrade)
        val old = kotlinx.serialization.json.Json.decodeFromString(com.zsz.studyassistant.ChatItem.serializer(),
            """{"id":0,"role":"question","content":"legacy"}""")
        assertNull(old.gradePart)
        val legacy = old.copy(content = "conditions\nMy answer", images = listOf("handwriting"))
        assertEquals(GradingInput("conditions", emptyList(), "handwriting"),
            gradingInput(true, "conditions", listOf(legacy, follow), emptyList()))
        for (photos in listOf(listOf("photo"), listOf("photo", "answer"))) {
            assertEquals(GradingInput(null, listOf("photo"), photos.getOrNull(1)),
                gradingInput(true, "legacy", listOf(old, follow), photos))
        }
        assertNull(gradingInput(false, "legacy", listOf(old, follow), listOf("photo", "second question photo")))
    }
    @Test fun quotedConversationRetainsFullContentAcrossSaveAndRestore() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val legacySource = com.zsz.studyassistant.ChatItem(1, "assistant", "Formula: \\(x^2\\)")
        val source = com.zsz.studyassistant.withStableMessageKeys(listOf(legacySource)).single()
        val anchor = com.zsz.studyassistant.QuoteAnchor(source.messageKey!!, "body", 9, 16, "a".repeat(64))
        val quote = com.zsz.studyassistant.QuotedFollowUp("Formula: \\(x^2\\)", "Why?", anchor)
        val message = com.zsz.studyassistant.ChatItem(2, "user", quote.text + "\n\n" + quote.question, quote = quote)
        val encoded = json.encodeToString(com.zsz.studyassistant.ChatItem.serializer(), message)
        val fragment = message.copy(quote = quote.copy(text = "x", anchor = anchor.copy(visualMath = true)))
        val fragmentJson = json.encodeToString(com.zsz.studyassistant.ChatItem.serializer(), fragment)
        assertEquals(fragment, json.decodeFromString(com.zsz.studyassistant.ChatItem.serializer(), fragmentJson))
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
        assertEquals(source, com.zsz.studyassistant.withStableMessageKeys(listOf(source)).single())
        val restoredSource = json.decodeFromString(com.zsz.studyassistant.ChatItem.serializer(),
            json.encodeToString(com.zsz.studyassistant.ChatItem.serializer(), source))
        assertEquals(anchor.messageKey, restoredSource.messageKey)
        // Reused numeric IDs and imported duplicate keys must never redirect an old quote to a new message.
        val afterDelete = com.zsz.studyassistant.withStableMessageKeys(listOf(legacySource)).single()
        assertNotEquals(source.messageKey, afterDelete.messageKey)
        val duplicates = com.zsz.studyassistant.withStableMessageKeys(listOf(source, source.copy(id = 2)))
        assertEquals(2, duplicates.map { it.messageKey }.distinct().size)
        assertTrue(duplicates.none { it.messageKey == source.messageKey })
        // A hidden duplicate question shifts display indices; deletion must still target the selected key.
        val stored = com.zsz.studyassistant.withStableMessageKeys(listOf(
            legacySource.copy(id = 0, role = "question", content = "q"),
            legacySource.copy(id = 1, role = "question", content = "q"), source, message))
        val displayed = listOf(null, stored[0].messageKey, stored[2].messageKey, stored[3].messageKey)
        assertEquals(listOf(null, 0, 2, 3), com.zsz.studyassistant.messageIndicesForKeys(stored, displayed))
        assertEquals(listOf(null, 0, 2, null), com.zsz.studyassistant.messageIndicesForKeys(stored.dropLast(1), displayed))
        assertEquals(listOf(null), com.zsz.studyassistant.messageIndicesForKeys(listOf(source, source), listOf(source.messageKey)))
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
