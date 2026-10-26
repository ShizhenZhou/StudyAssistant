package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses only the separate integration APK and its synthetic SAF provider. */
@RunWith(AndroidJUnit4::class)
class BackupDocumentsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { check(it.packageName == "com.zsz.studyassistant.integration") }
    private val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".fixture.backups", "root")
    private val parent get() = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
    @Before fun reset() {
        TestBackupDocumentsProvider.reset(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun folder() = File(context.filesDir, "saf-fixture")
    private fun workspace() = File(context.cacheDir, "saf-v4-${UUID.randomUUID()}").apply { mkdirs() }
    private fun set(work: File, at: Long = 1000): BackupArchive.ArchiveSet {
        val source = File(work, "source").apply { mkdirs() }
        File(source, "bytes").writeBytes(ByteArray(100_003) { (it % 251).toByte() })
        return BackupArchive.writeTree(source, File(work, "out"), snapshotAt = at, volumeBytes = 16384)
    }
    @Test fun singleRefusesNonemptyDestinationWithoutTruncation() = runBlocking {
        val work = workspace()
        try {
            val uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", "single")!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write("existing".toByteArray()) }
            val source = File(work, "input").apply { writeText("new") }
            try { BackupDocuments.writeSingle(context, source, uri); fail("nonempty overwritten") } catch (_: BackupWriteException) { }
            assertEquals("existing", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { work.deleteRecursively() }
    }
    @Test fun singleVerifiesClosedBytesAndRejectsCorruption() = runBlocking {
        val work = workspace()
        try {
            val source = File(work, "input").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
            val uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", "single")!!
            BackupDocuments.writeSingle(context, source, uri)
            assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally { work.deleteRecursively() }
    }
    @Test fun refusesProviderReturningPreexistingIdWithoutTouchingIt() = runBlocking {
        val work = workspace()
        try {
            val old = DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", "existing")!!
            context.contentResolver.openOutputStream(old)!!.use { it.write("keep".toByteArray()) }
            TestBackupDocumentsProvider.returnExistingId = DocumentsContract.getDocumentId(old)
            try { BackupDocuments.writeSet(context, set(work), tree, true); fail("reused ID accepted") } catch (_: BackupWriteException) { }
            assertEquals("keep", context.contentResolver.openInputStream(old)!!.bufferedReader().use { it.readText() })
            assertEquals(1, folder().listFiles()!!.size)
        } finally { work.deleteRecursively() }
    }
    @Test fun refusesUnlistableFolderBeforeCreatingAnything() = runBlocking {
        val work = workspace()
        try {
            TestBackupDocumentsProvider.denyListing = true
            try { BackupDocuments.writeSet(context, set(work), tree, true); fail("unlistable accepted") } catch (_: Exception) { }
            assertTrue(folder().listFiles().isNullOrEmpty())
        } finally { work.deleteRecursively() }
    }
    @Test fun wholeSetRetentionProtectsNewSetAfterClockRollbackAndManualFiles() = runBlocking {
        val works = mutableListOf<File>()
        try {
            File(folder().apply { mkdirs() }, "study-assistant-auto-malformed.json").writeText("unrelated malformed")
            val manual = workspace().also { works.add(it) }
            BackupDocuments.writeSet(context, set(manual, 5000), tree, false)
            val manualIds = folder().listFiles()!!.map { it.name }.toSet()
            for (at in listOf(4000L, 3000L, 2000L, 1L)) {
                val work = workspace().also { works.add(it) }
                val set = set(work, at)
                assertTrue(BackupDocuments.writeSet(context, set, tree, true).ok)
                if (at == 1L) assertTrue(folder().listFiles()!!.any { it.name.contains(set.setId) })
            }
            assertEquals(3, BackupDocuments.knownSetNames(context, tree).size)
            assertTrue(folder().listFiles()!!.map { it.name }.containsAll(manualIds))
        } finally { works.forEach { it.deleteRecursively() } }
    }
    @Test fun retentionPreservesAnOldSetWhoseDocumentIdNowContainsOtherBytes() = runBlocking {
        val works = mutableListOf<File>()
        try {
            val oldWork = workspace().also { works.add(it) }
            val old = set(oldWork, 1)
            BackupDocuments.writeSet(context, old, tree, true)
            val oldFiles = folder().listFiles()!!.filter { it.name.contains(old.setId) }
            val victim = oldFiles.first()
            val replacement = victim.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
            victim.writeBytes(replacement) // same ID and size, foreign/corrupt replacement
            for (at in 2L..4L) {
                val work = workspace().also { works.add(it) }
                BackupDocuments.writeSet(context, set(work, at), tree, true)
            }
            assertArrayEquals(replacement, victim.readBytes())
            assertTrue(oldFiles.all { it.isFile }) // no partial pruning of a rejected set
        } finally { works.forEach { it.deleteRecursively() } }
    }
    @Test fun retentionPreservesRemainingVolumesOfAnIncompleteOldSet() = runBlocking {
        val works = mutableListOf<File>()
        try {
            val oldWork = workspace().also { works.add(it) }
            val old = set(oldWork, 1)
            BackupDocuments.writeSet(context, old, tree, true)
            val oldFiles = folder().listFiles()!!.filter { it.name.contains(old.setId) }
            assertTrue(oldFiles.size > 1)
            assertTrue(oldFiles.first().delete())
            for (at in 2L..4L) {
                val work = workspace().also { works.add(it) }
                BackupDocuments.writeSet(context, set(work, at), tree, true)
            }
            assertTrue(oldFiles.drop(1).all { it.isFile })
        } finally { works.forEach { it.deleteRecursively() } }
    }
    @Test fun resumesOnlyClosedVerifiedVolumeCheckpoints() = runBlocking {
        val work = workspace()
        try {
            val set = set(work)
            assertTrue(set.files.size > 1)
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val key = "backup_job_parts_${set.setId}"
            try {
                BackupDocuments.writeSet(context, set, tree, true) {
                    val checkpoint = org.json.JSONObject(prefs.getString(key, "{}")!!)
                    if ((checkpoint.optJSONArray("parts")?.length() ?: 0) >= 1) throw CancellationException("synthetic stop")
                }
                fail("not interrupted")
            } catch (_: CancellationException) { }
            val id = org.json.JSONObject(prefs.getString(key, "{}")!!).getJSONArray("parts").getJSONObject(0).getString("id")
            assertEquals(1, folder().listFiles()!!.size)
            assertTrue(BackupDocuments.knownSetNames(context, tree).isEmpty())
            assertTrue(BackupDocuments.writeSet(context, set, tree, true).ok)
            assertTrue(File(folder(), id).isFile)
            assertEquals(set.files.size, folder().listFiles()!!.size)
            assertEquals(1, BackupDocuments.knownSetNames(context, tree).size)
            assertFalse(prefs.contains(key))
        } finally { work.deleteRecursively() }
    }
    @Test fun failedReadbackNeverCountsAsCompletedSet() = runBlocking {
        val work = workspace()
        try {
            TestBackupDocumentsProvider.corruptReads = true
            try { BackupDocuments.writeSet(context, set(work), tree, true); fail("corruption accepted") } catch (_: BackupWriteException) { }
            assertTrue(BackupDocuments.knownSetNames(context, tree).isEmpty())
            assertFalse(folder().listFiles()!!.any { it.name.startsWith(AutoBackup.PREFIX) })
        } finally { work.deleteRecursively() }
    }
}
