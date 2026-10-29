package com.zsz.studyassistant.data

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.zsz.studyassistant.MainViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CaptureRecoveryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }
    private val directory = File(context.cacheDir, "capture-recovery-test-" + UUID.randomUUID()).apply { mkdirs() }
    private val models = mutableListOf<ViewModelStore>()
    private val journal get() = CaptureFlowStore(context)
    private suspend fun model(): Pair<MainViewModel, ViewModelStore> = withContext(Dispatchers.Main) {
        val owner = ViewModelStore().also { models += it }
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
        ViewModelProvider(owner, factory)[MainViewModel::class.java] to owner
    }
    private fun image(name: String) = File(directory, name).apply { writeBytes("synthetic-$name".toByteArray()) }.absolutePath
    @Before fun reset() { journal.clear(journal.reserve()) }
    @After fun cleanup(): Unit = runBlocking {
        withContext(Dispatchers.Main) { models.forEach { it.clear() } }
        withContext(Dispatchers.IO) { journal.clear(journal.reserve()); directory.deleteRecursively() }
    }
    @Test fun newModelRestoresFirstCropModeAndPermanentIdentityWithoutRequestingAi() = runBlocking {
        val (first, owner) = model()
        val original = image("first.bin")
        withContext(Dispatchers.Main) {
            first.startCropFlow(listOf(original), grade = false, expect = 2)
            first.submitCropResult(byteArrayOf(1, 2, 3))
        }
        first.awaitCaptureCheckpoint()
        val saved = checkNotNull(journal.read())
        assertNotNull(saved.questionId)
        withContext(Dispatchers.Main) { owner.clear() }
        File(original).delete()
        val (restored, _) = model()
        withContext(Dispatchers.Main) { restored.restoreCaptureFlowIfNeeded() }
        assertTrue(restored.cropNeedsMore)
        assertEquals(2, restored.cropExpect)
        assertFalse(restored.cropGrade)
        assertTrue(restored.cropPaths.all { File(it).isFile })
        assertFalse(restored.busy)
        assertArrayEquals(byteArrayOf(1, 2, 3), journal.read()!!.results.single())
        withContext(Dispatchers.Main) { restored.appendCropPath(image("second.bin")) }
        restored.awaitCaptureCheckpoint()
        assertEquals(1, restored.cropIndex)
        assertEquals(saved.questionId, journal.read()!!.questionId)
        assertEquals(saved.createdAt, journal.read()!!.createdAt)
        assertTrue(restored.cropPaths.all { File(it).isFile })
    }
    @Test fun gradeQueueRestoresWithoutStartingGradingOrInventingHistoryIdentity() = runBlocking {
        val (first, owner) = model()
        withContext(Dispatchers.Main) {
            first.startCropFlow(listOf(image("grade.bin")), grade = true, expect = 2)
            first.submitCropResult(byteArrayOf(4, 5))
        }
        first.awaitCaptureCheckpoint()
        withContext(Dispatchers.Main) { owner.clear() }
        val (restored, _) = model()
        withContext(Dispatchers.Main) { restored.restoreCaptureFlowIfNeeded() }
        assertTrue(restored.cropGrade)
        assertTrue(restored.cropNeedsMore)
        assertEquals(2, restored.cropExpect)
        assertNull(journal.read()!!.questionId)
        assertFalse(restored.busy)
    }
    @Test fun explicitCancelClearsDurableQueueAndResetsMode() = runBlocking {
        val (vm, _) = model()
        withContext(Dispatchers.Main) { vm.startCropFlow(listOf(image("cancel.bin")), grade = true, expect = 2) }
        vm.awaitCaptureCheckpoint()
        withContext(Dispatchers.Main) { vm.cancelCropFlow() }
        vm.awaitCaptureCheckpoint()
        assertNull(journal.read())
        assertTrue(vm.cropPaths.isEmpty())
        assertFalse(vm.cropGrade)
        assertEquals(1, vm.cropExpect)
        val (fresh, _) = model()
        withContext(Dispatchers.Main) { fresh.restoreCaptureFlowIfNeeded() }
        assertTrue(fresh.cropPaths.isEmpty())
        assertNull(fresh.captureRecoveryNoticeKey)
    }

    @Test fun returningFromSecondCameraReplacesTheFirstCropInsteadOfDuplicatingIt() = runBlocking {
        val (vm, _) = model()
        withContext(Dispatchers.Main) {
            vm.startCropFlow(listOf(image("revisit-first.bin")), grade = true, expect = 2)
            vm.submitCropResult(byteArrayOf(4, 5))
            vm.cropGoBackOne()
        }
        vm.awaitCaptureCheckpoint()
        assertFalse("Revisiting must exit the awaiting-second-photo stage", vm.cropNeedsMore)
        assertEquals(0, vm.cropIndex)
        assertTrue("The old first crop must be removed before resubmitting", journal.read()!!.results.isEmpty())
        withContext(Dispatchers.Main) { vm.submitCropResult(byteArrayOf(8)) }
        vm.awaitCaptureCheckpoint()
        assertTrue(vm.cropNeedsMore)
        assertArrayEquals(byteArrayOf(8), journal.read()!!.results.single())
        assertFalse(vm.busy)
    }

    @Test fun cancelledSystemCaptureRemovesOnlyItsConsumedSources() = runBlocking {
        val (vm, _) = model()
        val dir = File(context.cacheDir, "system-camera").apply { mkdirs() }
        val source = File(dir, "capture-consumed.jpg").apply { writeBytes(File(image("cleanup.bin")).readBytes()) }
        val pending = File(dir, "capture-still-pending.jpg").apply { writeBytes(byteArrayOf(9)) }
        val other = File(dir, "unrelated.jpg").apply { writeBytes(byteArrayOf(7)) }
        try {
            withContext(Dispatchers.Main) { vm.startCropFlow(listOf(source.absolutePath), true, 2) }
            vm.awaitCaptureCheckpoint()
            withContext(Dispatchers.Main) { vm.cancelCropFlow() }
            vm.awaitCaptureCheckpoint()
            assertFalse("Consumed system-camera source should be removed", source.exists())
            assertTrue("Do not delete a file still owned by an external capture", pending.exists())
            assertTrue(other.exists())
        } finally { source.delete(); pending.delete(); other.delete() }
    }

    @Test fun failedWholeImageBatchPreservesBothInputOrderAndCurrentIndex() = runBlocking {
        val (vm, _) = model()
        val paths = listOf(image("question.bin"), image("answer.bin"))
        withContext(Dispatchers.Main) {
            vm.startCropFlow(paths, grade = true, expect = 2)
            vm.skipRemainingCrop { if (it == paths.first()) null else byteArrayOf(9) }
        }
        vm.awaitCaptureCheckpoint()
        assertEquals(paths, vm.cropPaths)
        assertEquals(0, vm.cropIndex)
        assertFalse(vm.cropNeedsMore)
        assertTrue(journal.read()!!.results.isEmpty())
    }
}
