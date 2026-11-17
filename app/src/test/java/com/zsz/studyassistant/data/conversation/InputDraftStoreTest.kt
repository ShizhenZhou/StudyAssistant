package com.zsz.studyassistant.data

import com.zsz.studyassistant.QuoteAnchor
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InputDraftStoreTest {
    private fun withStore(block: (File, InputDraftStore) -> Unit) {
        val directory = Files.createTempDirectory("input-draft-fixture").toFile()
        try { block(directory, InputDraftStore(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun roundTripPreservesQuoteImageOcrAndDocumentSelections() = withStore { directory, store ->
        val hash = "a".repeat(64)
        val metadata = InputDraftMetadata(InputDraftStore.NEW_ASK, "unsent synthetic text", "quote",
            QuoteAnchor("message", "body", 1, 6, "hash"), listOf(InputImageMetadata("content://fixture/image", "corrected OCR")),
            listOf(ConversationDocument(hash, "fixture.pdf", 10, pages = listOf(0, 2)),
                ConversationDocument("b".repeat(64), "fixture.xlsx", 20, sheets = listOf("Sheet1"))), excludedHashes = setOf(hash))
        store.write(InputDraftSnapshot(metadata, listOf(byteArrayOf(1, 2, 3))))
        val restored = InputDraftStore(directory).read(InputDraftStore.NEW_ASK)!!
        assertEquals(metadata, restored.metadata)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.images.single())
        assertEquals(setOf(hash, "b".repeat(64)), store.documentHashes())
    }
    @Test fun corruptionBlocksOverwriteAndCleanupUntilExplicitDiscard() = withStore { directory, store ->
        val file = File(directory, "${InputDraftStore.NEW_ASK}.input").apply { writeText("broken original") }
        val old = file.readBytes()
        assertThrows(Exception::class.java) { store.read(InputDraftStore.NEW_ASK) }
        assertThrows(Exception::class.java) { store.write(InputDraftSnapshot(InputDraftMetadata(InputDraftStore.NEW_ASK, "replacement"))) }
        assertThrows(Exception::class.java) { store.documentHashes() }
        assertArrayEquals(old, file.readBytes())
        store.clear(InputDraftStore.NEW_ASK)
        assertNull(store.read(InputDraftStore.NEW_ASK))
        repeat(InputDraftStore.MAX_DRAFTS) { File(directory, "abandoned-$it.new").writeText("interrupted write") }
        assertThrows(Exception::class.java) { store.write(InputDraftSnapshot(InputDraftMetadata(InputDraftStore.NEW_ASK, "over capacity"))) }
    }
    @Test fun queuedSendClearCannotResurrectOrEraseAnotherSession() = withStore { directory, store -> runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failures = mutableListOf<String>()
        val queue = InputDraftPersistence(store, scope, { failures += it }, {})
        val a = InputDraftStore.NEW_ASK
        val b = QuestionIdentity.create()
        try {
            queue.save(InputDraftSnapshot(InputDraftMetadata(a, "old pending")))
            queue.save(InputDraftSnapshot(InputDraftMetadata(a, "last unsent text")))
            queue.save(InputDraftSnapshot(InputDraftMetadata(b, "other question")))
            queue.clear(a) // Accepted send replaces old pending generations synchronously.
            withTimeout(5000) { queue.awaitIdle() }
            assertNull(InputDraftStore(directory).read(a))
            assertEquals("other question", InputDraftStore(directory).read(b)!!.metadata.text)
            assertTrue(failures.isEmpty())
            queue.clearAll()
            assertNull(InputDraftStore(directory).read(b))
        } finally { scope.cancel() }
    } }

    @Test fun endedSubscriberCannotCancelWritesOrResurrectANewOwnersSentInput() = withStore { directory, store -> runBlocking {
        val processScope = CoroutineScope(coroutineContext + SupervisorJob())
        val subscriberScope = CoroutineScope(coroutineContext + SupervisorJob())
        val queue = InputDraftPersistence(store, processScope)
        val key = InputDraftStore.NEW_ASK
        try {
            subscriberScope.launch { queue.saveState.collect {} }
            queue.reserve(key, "old-view-model")
            queue.save(InputDraftSnapshot(InputDraftMetadata(key, "last keystroke")), "old-view-model")
            subscriberScope.cancel() // Equivalent to onCleared: only the UI observer is cancelled.
            withTimeout(5000) { queue.awaitIdle() }
            assertEquals("last keystroke", InputDraftStore(directory).read(key)!!.metadata.text)
            queue.reserve(key, "new-view-model")
            queue.clear(key, "new-view-model")
            // A late onStop/observer from the old model must not revive the accepted message.
            queue.save(InputDraftSnapshot(InputDraftMetadata(key, "old delayed snapshot")), "old-view-model")
            withTimeout(5000) { queue.awaitIdle() }
            assertNull(InputDraftStore(directory).read(key))
        } finally { subscriberScope.cancel(); processScope.cancel() }
    } }
}
