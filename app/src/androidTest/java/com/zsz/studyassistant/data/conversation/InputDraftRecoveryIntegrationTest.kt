package com.zsz.studyassistant.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit, tiny synthetic fixture; the two process methods are intentionally invoked separately. */
class InputDraftRecoveryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val directory get() = File(context.noBackupFilesDir, "input-draft-process-fixture")
    private val key = "12345678-1234-4123-8123-123456789abc-1700000000000"
    private fun fixture(key: String = InputDraftStore.NEW_ASK) = InputDraftSnapshot(
        InputDraftMetadata(key, "synthetic unsent text", "synthetic quote",
            com.zsz.studyassistant.QuoteAnchor("fixture-message", "body", 0, 5, "fixture-hash"),
            listOf(InputImageMetadata("content://synthetic/image", "synthetic OCR")),
            listOf(ConversationDocument("a".repeat(64), "fixture.pdf", 8, pages = listOf(0)),
                ConversationDocument("b".repeat(64), "fixture.xlsx", 9, sheets = listOf("Sheet1"))),
            excludedHashes = setOf("c".repeat(64))), listOf(byteArrayOf(1, 2, 3, 4)))

    @Test fun prepareInputDraftForProcessRestart() {
        val store = InputDraftStore(directory)
        store.clearAll()
        store.write(fixture())
        store.write(fixture(key).copy(metadata = fixture(key).metadata.copy(text = "second question")))
        assertEquals(2, directory.listFiles()!!.count { it.extension == "input" })
    }
    @Test fun restoreInputDraftAfterProcessRestart() {
        val store = InputDraftStore(directory)
        val restored = checkNotNull(store.read(InputDraftStore.NEW_ASK))
        assertEquals(fixture().metadata, restored.metadata)
        assertArrayEquals(fixture().images.single(), restored.images.single())
        val input = com.zsz.studyassistant.ui.SolveInputDraft(InputDraftStore.NEW_ASK)
        input.restore(restored)
        val roundTrip = checkNotNull(input.snapshot())
        assertEquals(restored.metadata, roundTrip.metadata)
        assertArrayEquals(restored.images.single(), roundTrip.images.single())
        input.clearInput()
        assertTrue(checkNotNull(input.snapshot()).empty)
        assertTrue(input.images.isEmpty())
        assertTrue(input.correctedTexts.isEmpty())
        assertEquals("second question", store.read(key)!!.metadata.text)
        store.clearAll()
    }
    @Test fun clearIsolationAndCorruptOriginalArePreserved() = runBlocking {
        val local = File(context.noBackupFilesDir, "input-draft-clear-fixture")
        val store = InputDraftStore(local)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = InputDraftPersistence(store, scope, { error("Unexpected fixture write failure") }, {})
        try {
            queue.save(fixture()); queue.save(fixture(key)); queue.clear(InputDraftStore.NEW_ASK)
            withTimeout(5000) { queue.awaitIdle() }
            assertNull(InputDraftStore(local).read(InputDraftStore.NEW_ASK))
            assertNotNull(InputDraftStore(local).read(key))
            val file = File(local, "${InputDraftStore.NEW_ASK}.input").apply { writeText("damaged fixture") }
            assertThrows(Exception::class.java) { store.read(InputDraftStore.NEW_ASK) }
            assertThrows(Exception::class.java) { store.write(fixture()) }
            assertEquals("damaged fixture", file.readText())
        } finally { scope.cancel(); queue.clearAll(); local.delete() }
    }
}
