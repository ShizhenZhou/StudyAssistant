package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class StreamCompletionTest {
    @org.junit.Test fun boundedReaderPreservesUtf8AndRejectsOversizedLines() {
        val input = okio.Buffer().writeUtf8("你好\r\n末行")
        org.junit.Assert.assertEquals("你好", readStreamLine(input, 8))
        org.junit.Assert.assertEquals("末行", readStreamLine(input, 8))
        org.junit.Assert.assertNull(readStreamLine(input, 8))
        org.junit.Assert.assertThrows(StreamIncompleteException::class.java) { readStreamLine(okio.Buffer().writeUtf8("123456789"), 8) }
        val completion = StreamCompletion(maxCharacters = 3)
        completion.accept("""{"choices":[{"delta":{"content":"abc"}}]}""")
        org.junit.Assert.assertThrows(StreamIncompleteException::class.java) {
            completion.accept("""{"choices":[{"delta":{"reasoning_content":"x"}}]}""")
        }
    }
    @Test fun webMetadataAndTerminalErrorsArePreserved() {
        val tracker = StreamCompletion()
        val chunk = tracker.accept("""{"choices":[{"delta":{"content":"answer","server_tool_calls":[{"index":0,"calls":[{"signature":"synthetic"}]}],"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com","title":"Source"}}]},"finish_reason":"stop"}],"usage":{"server_tool_use":{"tool_search":2,"tool_fetch":1}}}""")!!
        assertEquals(2, chunk.usage!!.serverToolUse!!.searches)
        val metadata = AiResponseContext("answer", serverToolCalls = chunk.choices[0].delta!!.serverToolCalls,
            annotations = chunk.choices[0].delta!!.annotations)
        assertEquals("https://example.com/", metadata.sources().single().url)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(AiResponseContext.serializer(), metadata)
        assertEquals(metadata, json.decodeFromString(AiResponseContext.serializer(), text))
        val deleted = com.zsz.studyassistant.deleteConversationMessages(listOf(
            com.zsz.studyassistant.ChatItem(0, "question", "Q"),
            com.zsz.studyassistant.ChatItem(1, "assistant", "answer", aiContext = metadata)), setOf(1))
        assertTrue(deleted[1].answerPending)
        assertNull(deleted[1].aiContext)
        tracker.accept("""{"choices":[],"error":{"message":"private service detail"}}""")
        expect("err.streamInterrupted", tracker)
        assertNull(safeSourceUrl("javascript:alert(1)"))
        assertNull(safeSourceUrl("https://user:password@example.com"))
    }

    @Test fun outputBudgetsSeparateFollowupSolveAndGrade() {
        assertEquals(4096, AiRequestPolicy.budget("followup", false).tokens)
        assertEquals(8192, AiRequestPolicy.budget("initial", false).tokens)
        assertEquals("low", AiRequestPolicy.budget("followup", true).effort)
        assertEquals("high", AiRequestPolicy.budget("followup", true, true).effort)
        assertTrue(AiRequestPolicy.budget("grade", true).tokens > AiRequestPolicy.budget("initial", true).tokens)
    }

    @Test fun imageReferencesRespectFirstUseExpiryAndAccount() = kotlinx.coroutines.runBlocking {
        var now = 1000L
        var uploads = 0
        val cache = ReusableImageFiles(clock = { now }) { bytes, mime, key ->
            assertEquals("account-a", key)
            assertEquals("image/jpeg", mime); assertArrayEquals(byteArrayOf(1,2,3), bytes)
            DeepSeekFile("file-api-${++uploads}", now + 86400)
        }
        val parts = kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]""")
        val original = listOf(DeepSeekMessage("user", parts))
        assertEquals(original, cache.prepare(original, "account-a"))
        val reused = cache.prepare(original, "account-a")
        assertNotEquals(original, reused); assertEquals(1, uploads)
        assertEquals(reused, cache.prepare(original, "account-a")); assertEquals(1, uploads)
        now += 86400
        assertNotEquals(reused, cache.prepare(original, "account-a")); assertEquals(2, uploads)
        assertEquals(original, cache.prepare(original, "account-b"))
        cache.invalidate("account-b")
        assertEquals(original, cache.prepare(original, "account-b"))
    }

    @Test fun failedUploadFallsBackButCancellationPropagates() = kotlinx.coroutines.runBlocking {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]""")
        val original = listOf(DeepSeekMessage("user", parts))
        var calls = 0
        val failed = ReusableImageFiles { _, _, _ -> calls++; throw java.io.IOException() }
        failed.prepare(original, "a")
        assertEquals(original, failed.prepare(original, "a"))
        assertEquals(original, failed.prepare(original, "a")); assertEquals(1, calls)
        val cancelled = ReusableImageFiles { _, _, _ -> throw kotlinx.coroutines.CancellationException() }
        cancelled.prepare(original, "a")
        try { cancelled.prepare(original, "a"); fail("Cancellation swallowed") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun thinkingModeAlwaysSendsItsRequiredType() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (mode in listOf("enabled", "disabled")) {
            val request = DeepSeekRequest("deepseek-flash", listOf(DeepSeekMessage("user", kotlinx.serialization.json.JsonPrimitive("synthetic"))),
                thinking = ThinkingOption(mode), stream = true, streamOptions = StreamOptions(true))
            val serialized = json.encodeToString(DeepSeekRequest.serializer(), request)
            val obj = json.parseToJsonElement(serialized) as kotlinx.serialization.json.JsonObject
            assertEquals(kotlinx.serialization.json.JsonPrimitive(4096), obj["max_tokens"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(0.3), obj["temperature"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(mode), (obj["thinking"] as kotlinx.serialization.json.JsonObject)["type"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(true), (obj["stream_options"] as kotlinx.serialization.json.JsonObject)["include_usage"])
            val message = (obj["messages"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
            assertFalse(message.containsKey("localDocuments"))
            assertFalse(obj.containsKey("title"))
        }
        val request = DeepSeekRequest("deepseek-flash", emptyList(), title = ConversationTitleOption(true))
        val obj = json.parseToJsonElement(json.encodeToString(DeepSeekRequest.serializer(), request)) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), obj["stream"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), (obj["title"] as kotlinx.serialization.json.JsonObject)["enabled"])
    }

    @Test fun titleFooterKeepsAnswerAndToolContextSeparateAndSurvivesConversationStorage() {
        val tracker = StreamCompletion()
        val answer = tracker.accept("""{"choices":[{"delta":{"content":"Full original answer","server_tool_calls":[{"signature":"synthetic"}],"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com","title":"Source"}}]},"finish_reason":"stop"}]}""")!!.choices.single().delta!!
        val footer = tracker.accept("""{"choices":[{"delta":{"title":"  Short\n title  "}}]}""")!!.choices.single().delta!!
        tracker.accept("[DONE]")
        tracker.requireComplete()
        assertNull(footer.content)
        val body = checkNotNull(answer.content)
        val context = AiResponseContext(body, serverToolCalls = answer.serverToolCalls, annotations = answer.annotations)
        val original = listOf(com.zsz.studyassistant.ChatItem(0, "question", "Full original question"),
            com.zsz.studyassistant.ChatItem(1, "assistant", body, aiContext = context))
        val titled = com.zsz.studyassistant.withConversationTitle(original, footer.title)
        assertEquals("Short title", titled.first().conversationTitle)
        assertEquals(original.first().content, titled.first().content)
        assertEquals(original[1], titled[1])
        val serializer = kotlinx.serialization.builtins.ListSerializer(com.zsz.studyassistant.ChatItem.serializer())
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(titled, json.decodeFromString(serializer, json.encodeToString(serializer, titled)))
        assertNull(json.decodeFromString(serializer, """[{"id":0,"role":"question","content":"Legacy question"}]""").first().conversationTitle)
        assertSame(titled, com.zsz.studyassistant.withConversationTitle(titled, "Another response title"))
        assertSame(original, com.zsz.studyassistant.withConversationTitle(original, null))
        val firstRequest = StreamOwner().apply { conversationTitle = titled.first().conversationTitle }
        val laterRequest = StreamOwner()
        assertNull(laterRequest.conversationTitle)
        assertEquals("Short title", firstRequest.conversationTitle)
    }

    @Test fun titlesAreBoundedPlainLabelsWithoutSplittingUnicodeCharacters() {
        assertEquals("First second", normalizedConversationTitle("\tFirst\u0000\u202e\n\u00a0 second\r"))
        assertNull(normalizedConversationTitle("\u0000\n\t\u202e"))
        val title = normalizedConversationTitle("😀".repeat(130))!!
        assertEquals(120, title.codePointCount(0, title.length))
        assertFalse(Character.isHighSurrogate(title.last()))
    }

    @Test fun fileFallbackRequiresExplicitFileRejection() {
        assertTrue(classifyAiError(400, """{"error":{"code":"file_expired"}}""").fileReferenceRejected)
        assertTrue(classifyAiError(400, """{"error":{"code":"invalid_file_id"}}""").fileReferenceRejected)
        assertFalse(classifyAiError(422, """{"detail":[{"loc":["body","thinking","type"],"input":"private"}]}""").fileReferenceRejected)
        assertEquals("thinking", classifyAiError(422, """{"detail":[{"loc":["body","thinking","type"]}]}""").parameter)
        assertFalse(classifyAiError(401, """{"error":{"code":"file_expired"}}""").fileReferenceRejected)
        assertFalse(classifyAiError(400, """{"error":{"message":"invalid model"}}""").fileReferenceRejected)
    }

    private fun expect(key: String, tracker: StreamCompletion) {
        try { tracker.requireComplete(); fail("Incomplete answer was accepted") }
        catch (e: StreamIncompleteException) { assertEquals(key, e.messageKey) }
    }
    @Test fun `stop plus usage and done completes normally`() {
        val tracker = StreamCompletion()
        val chunk = tracker.accept("""{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""")!!
        assertEquals("answer", chunk.choices[0].delta!!.content)
        tracker.accept("""{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""")
        tracker.accept("[DONE]")
        assertTrue(tracker.done)
        tracker.requireComplete()
    }
    @Test fun `explicit stop remains complete if done footer is missing`() {
        val tracker = StreamCompletion()
        tracker.accept("""{"choices":[{"finish_reason":"stop"}]}""")
        tracker.requireComplete()
    }
    @Test fun `done alone cannot claim a complete answer`() {
        val tracker = StreamCompletion(); tracker.accept("[DONE]")
        expect("err.streamInterrupted", tracker)
    }
    @Test fun `EOF without a completion reason is interrupted`() {
        val tracker = StreamCompletion()
        tracker.accept("""{"choices":[{"delta":{"content":"partial"}}]}""")
        expect("err.streamInterrupted", tracker)
    }
    @Test fun `length limit stays incomplete even with done`() {
        val tracker = StreamCompletion()
        tracker.accept("""{"choices":[{"finish_reason":"length"}]}""")
        tracker.accept("[DONE]")
        expect("err.streamLength", tracker)
    }
    @Test fun `filtered and other terminal reasons are never success`() {
        for (reason in listOf("content_filter", "insufficient_system_resource", "aborted", "tool_calls", "future_reason")) {
            val tracker = StreamCompletion()
            tracker.accept("""{"choices":[{"finish_reason":"$reason"}]}""")
            expect(if (reason == "content_filter") "err.streamFiltered" else "err.streamInterrupted", tracker)
        }
    }
    @Test fun `invalid stream data is not silently dropped or leaked`() {
        try { StreamCompletion().accept("synthetic private response must not appear in errors"); fail("Bad data accepted") }
        catch (e: StreamIncompleteException) { assertEquals("err.streamInvalid", e.message) }
    }
    @Test fun `reasoning and empty heartbeat payload do not signal completion`() {
        val tracker = StreamCompletion()
        assertNull(tracker.accept(""))
        val chunk = tracker.accept("""{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""")!!
        assertEquals("thinking", chunk.choices[0].delta!!.reasoningContent)
        expect("err.streamInterrupted", tracker)
    }
}
