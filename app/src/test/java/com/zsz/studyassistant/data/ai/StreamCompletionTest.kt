package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class StreamCompletionTest {
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
