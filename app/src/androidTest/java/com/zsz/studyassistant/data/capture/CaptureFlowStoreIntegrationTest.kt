package com.zsz.studyassistant.data

import android.content.Context
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFlowStoreIntegrationTest {
    private lateinit var context: Context
    private lateinit var store: CaptureFlowStore
    private lateinit var sources: File
    private lateinit var root: File

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        store = CaptureFlowStore(context)
        root = File(context.noBackupFilesDir, "capture-flow-v1")
        store.clear(store.reserve())
        sources = File(context.cacheDir, "capture-fixture-${UUID.randomUUID()}")
        check(sources.mkdir())
    }
    @After fun tearDown() { store.clear(store.reserve()); sources.deleteRecursively() }

    private fun state(two: Boolean = true): CaptureFlowState {
        val paths = (0 until if (two) 2 else 1).map { index ->
            File(sources, "$index.bin").apply { writeBytes(byteArrayOf(1, index.toByte(), -1)) }.absolutePath
        }
        return CaptureFlowState(paths, if (two) 1 else 0, false, 2, false,
            if (two) listOf(byteArrayOf(9, 8, 7)) else emptyList(), QuestionIdentity.create(123456L), 123456L)
    }
    private fun expectIo(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        fail("Expected IOException")
    }
    private fun metadata() = File(root, "state.json")

    @Test fun reopenRestoresFirstResultIdentityAndSourcesAfterCacheRemoval() {
        val initial = state()
        assertTrue(store.write(initial, store.reserve()))
        sources.deleteRecursively()
        val restored = CaptureFlowStore(context).read()!!
        assertEquals(initial.index, restored.index)
        assertEquals(initial.questionId, restored.questionId)
        assertEquals(initial.createdAt, restored.createdAt)
        assertArrayEquals(initial.results.single(), restored.results.single())
        restored.paths.forEachIndexed { index, path ->
            assertArrayEquals(byteArrayOf(1, index.toByte(), -1), File(path).readBytes())
        }
        // Further checkpoints must not invalidate the paths still held by the ViewModel.
        assertTrue(store.write(restored, store.reserve()))
        restored.paths.forEach { assertTrue(File(it).isFile) }
    }

    @Test fun staleTicketAcrossStoreObjectsCannotWriteOrClear() {
        val initial = state()
        val stale = store.reserve()
        val other = CaptureFlowStore(context)
        val current = other.reserve()
        assertTrue(other.write(initial, current))
        store.clear(stale)
        assertFalse(store.write(initial.copy(index = 0, results = emptyList()), stale))
        assertEquals(1, other.read()!!.index)
        other.clear(other.reserve())
        assertFalse(store.write(initial, current))
        assertNull(store.read())
    }

    @Test fun reserveDoesNotWaitForResourceCopyAndInvalidatedCopyKeepsPrevious() {
        val initial = state()
        assertTrue(store.write(initial, store.reserve()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Any>()
        val ticket = store.reserve()
        val writer = Thread {
            try {
                outcome.set(store.write(initial, ticket) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                })
            } catch (error: Throwable) { outcome.set(error) }
        }
        writer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reserved = CountDownLatch(1)
        val reserver = Thread { CaptureFlowStore(context).reserve(); reserved.countDown() }
        reserver.start()
        try { assertTrue("reserve blocked behind disk work", reserved.await(1, TimeUnit.SECONDS)) }
        finally { release.countDown(); writer.join(5000); reserver.join(5000) }
        assertEquals(false, outcome.get())
        assertEquals(initial.questionId, store.read()!!.questionId)
    }

    @Test fun failedCopyAndInjectedWriteKeepPreviousCheckpoint() {
        val initial = state()
        assertTrue(store.write(initial, store.reserve()))
        val originalMetadata = metadata().readText()
        expectIo { store.write(initial.copy(paths = listOf(File(sources, "missing").absolutePath, initial.paths[1])), store.reserve()) }
        expectIo { store.write(initial, store.reserve()) { throw IOException("synthetic") } }
        assertEquals(originalMetadata, metadata().readText())
        assertEquals(initial.questionId, store.read()!!.questionId)
    }

    @Test fun missingResourceAndIllegalMetadataAreRetainedForRecovery() {
        assertNull(store.read())
        assertTrue(store.write(state(), store.reserve()))
        val json = JSONObject(metadata().readText())
        val generation = File(root, json.getString("generation"))
        val first = File(generation, "source-0.bin")
        val bytes = first.readBytes()
        assertTrue(first.delete())
        expectIo { store.read() }
        assertTrue(metadata().isFile)
        assertTrue(generation.isDirectory)
        first.writeBytes(bytes)
        json.put("generation", "../escape")
        metadata().writeText(json.toString())
        expectIo { store.read() }
        assertTrue(metadata().isFile)
        assertTrue(first.isFile)
    }

    @Test fun invalidIdentityTraversalSymlinkAndOversizedResultAreRejected() {
        val initial = state(false)
        expectIo { store.write(initial.copy(questionId = QuestionIdentity.create(99)), store.reserve()) }
        val traversal = File(sources, "../${sources.name}/0.bin").absolutePath
        expectIo { store.write(initial.copy(paths = listOf(traversal)), store.reserve()) }
        val link = File(sources, "linked.bin")
        Os.symlink(initial.paths.single(), link.absolutePath)
        expectIo { store.write(initial.copy(paths = listOf(link.absolutePath)), store.reserve()) }
        expectIo { store.write(initial.copy(results = listOf(ByteArray(16 * 1024 * 1024 + 1)), index = 1,
            needsMore = true), store.reserve()) }
        assertNull(store.read())
    }

    @Test fun needsMoreAcceptsBothCropAndSkipIndicesAndGradeHasNoIdentity() {
        val initial = state(false).copy(grade = true, questionId = null, needsMore = true,
            results = listOf(byteArrayOf(4)), index = 0)
        // Normal crop finishes the current image without incrementing its last index.
        assertTrue(store.write(initial, store.reserve()))
        assertTrue(store.read()!!.needsMore)
        assertNull(store.read()!!.questionId)
        // Skip increments through paths.size, including when a decoder returned no result.
        assertTrue(store.write(initial.copy(index = 1, results = emptyList()), store.reserve()))
        assertEquals(1, store.read()!!.index)
        assertTrue(store.read()!!.results.isEmpty())
    }
}
