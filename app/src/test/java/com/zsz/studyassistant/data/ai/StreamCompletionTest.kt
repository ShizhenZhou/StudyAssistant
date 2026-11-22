package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class StreamCompletionTest {
    @Test fun cancellingPreparationRetainsRequestWithoutSendingIt() = kotlinx.coroutines.runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var retained: String? = null
        var sent = false
        val job = launch {
            SolveRequestLifecycle().prepareRetainingOnCancel(
                prepare = { started.complete(Unit); release.await(); "prepared request" },
                retain = { retained = it })
            sent = true
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("prepared request", retained)
        assertFalse(sent)
    }
    @Test fun imageReferencesSurviveRestartButNotExpiryAccountOrDamage() = kotlinx.coroutines.runBlocking {
        var stored: String? = null
        var now = 1000L
        var uploads = 0
        val path = java.io.File("synthetic-image-reference-cache")
        fun cache() = ReusableImageFiles(clock = { now }, readState = { stored }, writeState = { _, text -> stored = text }) { _, _, _ ->
            uploads++
            DeepSeekFile("file-fixture-$uploads", now + 86400)
        }
        val parts = kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]""")
        val original = listOf(DeepSeekMessage("user", parts))
        val first = cache()
        assertEquals(original, first.prepare(original, "account-a", path).messages)
        val uploaded = first.prepare(original, "account-a", path).messages
        assertNotEquals(original, uploaded)
        assertEquals(uploaded, cache().prepare(original, "account-a", path).messages)
        assertEquals(1, uploads)
        assertFalse(stored!!.contains("account-a"))
        assertFalse(stored!!.contains("AQID"))
        now += 86400
        assertEquals(original, cache().prepare(original, "account-a", path).messages)
        assertEquals(original, cache().prepare(original, "account-b", path).messages)
        stored = "damaged"
        assertEquals(original, cache().prepare(original, "account-a", path).messages)
        assertEquals(1, uploads)
    }

    @Test fun extractedTextCacheSeparatesSelectionsAndBoundsMemory() {
        val cache = DocumentTextCache(maxCharacters = 8)
        val doc = ConversationDocument("a".repeat(64), "fixture.xlsx", 4, sheets = listOf("A"))
        var reads = 0
        fun read(d: ConversationDocument, text: String) = cache.get(d) { reads++; text }
        assertEquals("abcd", read(doc, "abcd"))
        assertEquals("abcd", read(doc, "wrong"))
        assertEquals(1, reads)
        assertEquals("efghi", read(doc.copy(sheets = listOf("B")), "efghi"))
        assertEquals("abcd", read(doc, "abcd")) // Prior entry evicted, not a different sheet's text.
        assertEquals(3, reads)
        cache.clear()
        read(doc, "abcd")
        assertEquals(4, reads)
    }

    @Test fun replacingRequestCancelsOnlyPreviousOwnerAndAdvancesGeneration() {
        val lifecycle = SolveRequestLifecycle()
        var cancellations = 0
        val first = lifecycle.begin().also { it.attach { cancellations++ } }
        val generation = lifecycle.sequence
        assertTrue(lifecycle.owns(first, generation))
        val second = lifecycle.begin()
        assertFalse(lifecycle.owns(first, generation))
        assertTrue(lifecycle.owns(second, lifecycle.sequence))
        assertNotSame(first, second)
        assertEquals(1, cancellations)
        assertTrue(lifecycle.sequence > generation)
        assertSame(second, lifecycle.invalidate())
        assertNull(lifecycle.owner)
        assertFalse(lifecycle.owns(second, lifecycle.sequence))
    }

    @Test fun laterConversationOpenWinsAcrossHistoryNotebookAndDraft() = kotlinx.coroutines.runBlocking {
        val requests = ConversationOpenRequests()
        val releaseHistory = kotlinx.coroutines.CompletableDeferred<Unit>()
        var visible = "original"
        val history = requests.begin()
        val read = launch {
            releaseHistory.await()
            if (requests.isCurrent(history)) visible = "history"
        }
        val notebook = requests.begin()
        val draft = requests.begin()
        if (requests.isCurrent(draft)) visible = "draft"
        releaseHistory.complete(Unit)
        read.join()
        if (requests.isCurrent(notebook)) visible = "notebook"
        assertEquals("draft", visible)
    }
    @Test fun similarQuestionKeepsAllLinesAndFormulaBeforeAnswer() {
        val question = "求下列两项：\n(1) 电流 I\n(2) 电压 U = IR"
        val result = StudyAssistant.parseSimilar("题目：$question\n\n解答：逐项计算。")
        assertEquals(question, result.question)
        assertEquals("逐项计算。", result.answer)
        assertEquals(question, StudyAssistant.parseSimilar("$question\n解答:结果").question)
        val draft = "题目：请评价这份解答：\n(1) 电流 I"
        assertNull(SimilarExerciseOutput.parse(draft).readyQuestion)
        assertThrows(StreamIncompleteException::class.java) { StudyAssistant.parseSimilar(draft) }
        assertEquals(question, SimilarExerciseOutput.parse("题目：$question\n解答：").readyQuestion)
    }

    @Test fun repeatedSimilarAnswerContinuationKeepsOnlyTheCurrentAnswerPrefix() {
        val first = SimilarExerciseGeneration("fixture question", "first ").output("second ")
        val resumed = SimilarExerciseGeneration(first.readyQuestion, first.answer.orEmpty())
        val messages = resumed.continuationMessages(DeepSeekMessage("system", kotlinx.serialization.json.JsonPrimitive("system")))
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
        assertTrue(messages[1].content.toString().contains("fixture question"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("first second "), messages[2].content)
        assertEquals(SimilarExerciseOutput("fixture question", "first second third"), resumed.output("third"))
    }

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
        assertEquals(original, cache.prepare(original, "account-a").messages)
        val reused = cache.prepare(original, "account-a").messages
        assertNotEquals(original, reused); assertEquals(1, uploads)
        assertEquals(reused, cache.prepare(original, "account-a").messages); assertEquals(1, uploads)
        now += 86400
        assertNotEquals(reused, cache.prepare(original, "account-a").messages); assertEquals(2, uploads)
        assertEquals(original, cache.prepare(original, "account-b").messages)
    }

    @Test fun ineligibleMessagesBypassAnActiveImageUploadAndCacheIo() = kotlinx.coroutines.runBlocking {
        val release = CompletableDeferred<Unit>()
        var uploads = 0
        var reads = 0
        var writes = 0
        val path = java.io.File("synthetic-image-reference-cache")
        val cache = ReusableImageFiles(clock = { 1000L }, readState = { reads++; null },
            writeState = { _, _ -> writes++ }) { _, _, _ ->
            uploads++
            release.await()
            DeepSeekFile("file-shared", 90000L)
        }
        val image = imageMessages("AQID")
        val other = listOf(DeepSeekMessage("user", Json.parseToJsonElement("""[
            {"type":"text","text":"fixture"},
            {"type":"file","file_id":"file-document"},
            {"type":"image_url","image_url":{"url":"https://example.com/image"}},
            {"type":"image_url","image_url":{"detail":"low","url":"data:image/jpeg;base64,AQID"}}
        ]""")))
        cache.prepare(image, "a", path)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { cache.prepare(image, "a", path) }
        val sameImage = async(start = CoroutineStart.UNDISPATCHED) { cache.prepare(image, "a", path) }
        val bypass = async(start = CoroutineStart.UNDISPATCHED) { cache.prepare(other, "b", path) }
        try {
            assertTrue(bypass.isCompleted)
            assertSame(other, bypass.await().messages)
            assertNull(bypass.await().references)
            assertEquals(1, reads)
            assertEquals(1, writes) // The uncertain-upload cooldown is on disk before upload returns.
        } finally { release.complete(Unit) }
        assertEquals(pending.await().messages, sameImage.await().messages)
        assertEquals(1, uploads)
        assertEquals(2, writes)
    }

    @Test fun rejectedReferencesMatchOnlyTheSentImageAccountAndRevision() = kotlinx.coroutines.runBlocking {
        var now = 1000L
        var writes = 0
        val path = java.io.File("synthetic-image-reference-cache")
        val cache = ReusableImageFiles(clock = { now }, readState = { null }, writeState = { _, _ -> writes++ }) { bytes, _, _ ->
            DeepSeekFile("file-${bytes.first()}", now + 1000)
        }
        val first = imageMessages("AQID")
        val second = imageMessages("BAUG")
        cache.prepare(first + second, "a", path)
        val sent = cache.prepare(first, "a", path)
        val other = cache.prepare(second, "a", path)
        cache.invalidate(checkNotNull(sent.references))
        assertSame(first, cache.prepare(first, "a", path).messages)
        assertEquals(other.messages, cache.prepare(second, "a", path).messages)
        now += 900
        val renewed = cache.prepare(second, "a", path)
        val beforeStaleFailure = writes
        cache.invalidate(checkNotNull(other.references))
        assertEquals(renewed.messages, cache.prepare(second, "a", path).messages)
        assertEquals(beforeStaleFailure, writes)
        cache.prepare(second, "b", path)
        val switched = cache.prepare(second, "b", path)
        val beforeOtherAccountFailure = writes
        cache.invalidate(checkNotNull(renewed.references))
        assertEquals(switched.messages, cache.prepare(second, "b", path).messages)
        assertEquals(beforeOtherAccountFailure, writes)
    }

    private fun imageMessages(base64: String) = listOf(DeepSeekMessage("user",
        Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$base64"}}]""")))

    @Test fun failedUploadFallsBackButCancellationPropagates() = kotlinx.coroutines.runBlocking {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]""")
        val original = listOf(DeepSeekMessage("user", parts))
        var calls = 0
        var stored: String? = null
        var writes = 0
        val path = java.io.File("synthetic-image-reference-cache")
        fun failingCache() = ReusableImageFiles(clock = { 1000L }, readState = { stored },
            writeState = { _, text -> stored = text; writes++ }) { _, _, _ -> calls++; throw java.io.IOException() }
        val failed = failingCache()
        failed.prepare(original, "a", path)
        assertEquals(original, failed.prepare(original, "a", path).messages)
        assertEquals(original, failingCache().prepare(original, "a", path).messages)
        assertEquals(1, calls)
        assertEquals(1, writes)
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
