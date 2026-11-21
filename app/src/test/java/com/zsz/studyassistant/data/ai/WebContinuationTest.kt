package com.zsz.studyassistant.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebContinuationTest {
    @Test fun networkResumePreservesWhitespaceAcrossSegments() {
        val original = listOf(DeepSeekMessage("user", JsonPrimitive("fixture")))
        val blank = continuationContext(original, "", "", false, emptyList(), null, null)
        assertTrue(java.net.UnknownHostException("fixture.invalid").isConnectionFailure())
        assertTrue(java.net.SocketException("Connection reset").isConnectionFailure())
        assertFalse(DocumentProblem("document.missing").isConnectionFailure())
        assertEquals(original, blank.messagesForRequest(false))

        val joined = ThinkingOutput.split("<thinking>thought</thinking> value\n", "return")
        assertEquals("return value\n", joined.body)
        val retry = continuationContext(original, joined.body, "", false, emptyList(), null, null)
        assertEquals(joined.body, retry.messagesForRequest(false)[1].content.jsonPrimitive.content)
        assertEquals("return value\n    next()\n", ThinkingOutput.split("    next()\n", retry.prefix).body)
    }

    @Test fun ordinaryResumesAndLegacyDraftsSendTheWholePrefixOnlyOnce() {
        val original = listOf(DeepSeekMessage("user", JsonPrimitive("question")))
        fun plain(messages: List<DeepSeekMessage>, partial: String, previous: String = "") =
            continuationContext(messages, partial, previous, false, emptyList(), null, null)
        val first = plain(original, "A")
        val second = plain(first.messages, "AB")
        val nextRequest = second.messagesForRequest(false)
        assertEquals(3, nextRequest.size)
        assertEquals(original.single(), nextRequest[0])
        assertEquals(DeepSeekMessage("assistant", JsonPrimitive("AB")), nextRequest[1])
        assertEquals(first.messagesForRequest(false).last(), nextRequest[2])
        assertEquals(original, plain(original, "").messagesForRequest(false))

        // Older clients appended a new pair for each interruption and persisted the expanded request.
        val legacy = first.messagesForRequest(false) + nextRequest.takeLast(2)
        val draft = ConversationDraft(QuestionIdentity.create(10000), 10000, java.util.UUID.randomUUID().toString(),
            "fixture", "followup", "text", "question", false, true, false, true, null, emptyList(), emptyList(),
            legacy, raw = "ABC", requestPrefix = "AB")
        val restored = ConversationDraftCodec.decode(ConversationDraftCodec.encode(draft))
        assertEquals(plain(original, "ABC"), plain(restored.messages, restored.raw, restored.requestPrefix))
        // An ordinary message matching the instruction is preserved when there is no legacy prefix.
        assertEquals(legacy, plain(legacy, "ABC").messages)
    }

    @Test fun safeSegmentsKeepExactToolContextAcrossRepeatedContinuation() {
        val original = listOf(DeepSeekMessage("user", JsonPrimitive("question")))
        val tools = Json.parseToJsonElement("""[{"index":0,"calls":[{"signature":"synthetic"}]}]""") as JsonArray
        val first = AiResponseContext("first", "reasoning", tools)
        val next = webContinuation(original, "", emptyList(), null, first)
        assertEquals("first", next.prefix)
        assertEquals(tools, next.messages[1].serverToolCalls)
        assertEquals("reasoning", next.messages[1].reasoningContent)
        val second = webContinuation(next.messages, next.prefix, next.history, next.annotations,
            AiResponseContext("second", "next reasoning"))
        assertEquals("firstsecond", second.prefix)
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), second.messages.map { it.role })
        assertEquals("first", second.history.first().content.jsonPrimitive.content)
        val complete = completedWebContext(AiResponseContext("last"), second.history, second.annotations)!!
        assertEquals("last", complete.rawContent)
        assertEquals(second.history, complete.continuationHistory)
        val json = Json { encodeDefaults = true }
        assertEquals(complete, json.decodeFromString(AiResponseContext.serializer(), json.encodeToString(AiResponseContext.serializer(), complete)))
    }

    @Test fun missingTerminalContextRetriesOnlyCurrentSegmentWithoutAcceptingPartial() {
        val original = listOf(DeepSeekMessage("user", JsonPrimitive("question")))
        val next = webContinuation(original, "", emptyList(), null, AiResponseContext("safe"))
        val retry = webContinuation(next.messages, next.prefix, next.history, next.annotations, null)
        assertEquals(next, retry)
        assertEquals("safe", retry.prefix)
    }

    @Test fun citationsFromCompletedSegmentsRemainAvailable() {
        val citation = Json.parseToJsonElement("""[{"type":"url_citation","url_citation":{"url":"https://example.com","title":"Source"}}]""") as JsonArray
        val next = webContinuation(emptyList(), "", emptyList(), null, AiResponseContext("first", annotations = citation))
        val complete = completedWebContext(AiResponseContext("last"), next.history, next.annotations)!!
        assertEquals("https://example.com/", complete.sources().single().url)
        assertTrue(Json.decodeFromString(AiResponseContext.serializer(), """{"rawContent":"old"}""").continuationHistory.isEmpty())
    }

    @Test fun reopenedDraftReconstructsTheSameNextRequest() {
        val messages = listOf(DeepSeekMessage("user", JsonPrimitive("question")))
        val first = webContinuation(messages, "", emptyList(), null, AiResponseContext("first"))
        val draft = ConversationDraft(QuestionIdentity.create(10000), 10000, java.util.UUID.randomUUID().toString(),
            "fixture", "followup", "text", "question", false, true, false, true, null, emptyList(), emptyList(),
            first.messages, raw = "firstsecond", webEnabled = true, requestPrefix = first.prefix,
            continuationHistory = first.history, continuationAnnotations = first.annotations,
            resumableContext = AiResponseContext("second", "reasoning"))
        val restored = ConversationDraftCodec.decode(ConversationDraftCodec.encode(draft))
        val next = webContinuation(restored.messages, restored.requestPrefix, restored.continuationHistory,
            restored.continuationAnnotations, restored.resumableContext)
        assertEquals("firstsecond", next.prefix)
        assertEquals(5, next.messages.size)
        assertEquals("second", next.messages[3].content.jsonPrimitive.content)
        assertTrue(restored.webEnabled)
        val oldJson = Json.parseToJsonElement(ConversationDraftCodec.encode(draft)).jsonObject
            .filterKeys { it !in setOf("requestPrefix", "continuationHistory", "continuationAnnotations", "resumableContext") }
        val old = ConversationDraftCodec.decode(JsonObject(oldJson).toString())
        assertNull(old.resumableContext)
        assertTrue(old.continuationHistory.isEmpty())
        assertEquals("", old.requestPrefix)
    }
}
