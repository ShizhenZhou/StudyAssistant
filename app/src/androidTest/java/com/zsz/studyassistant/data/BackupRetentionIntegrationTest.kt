package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
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

/** Only synthetic documents in the separate integration package are eligible for these tests. */
@RunWith(AndroidJUnit4::class)
class BackupRetentionIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { check(it.packageName == "com.zsz.studyassistant.integration") }
    private val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".fixture.backups", "root")
    private val parent get() = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
    @Before fun reset() {
        TestBackupDocumentsProvider.reset(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun workspace() = File(context.cacheDir, "retention-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    private fun file(uri: Uri) = File(context.filesDir, "saf-fixture/${DocumentsContract.getDocumentId(uri)}")
    private fun save(name: String, bytes: ByteArray): Uri {
        val uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)!!
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }
    private fun legacy(at: Long, name: String = "study-assistant-auto-$at.json") = save(name,
        """{"format":"study-assistant-backup","version":3,"exportedAt":$at,"questions":[],"categories":[],"tags":[],"questionTags":[],"reviews":[],"solveSessions":[]}""".toByteArray())
    private fun generic(work: File, at: Long): BackupArchive.ArchiveSet {
        val source = File(work, "source").apply { mkdirs() }
        File(source, "bytes").writeBytes(ByteArray(70_001) { (it % 251).toByte() })
        return BackupArchive.writeTree(source, File(work, "out"), snapshotAt = at, volumeBytes = 16384)
    }
    private fun external(set: BackupArchive.ArchiveSet): List<Uri> = set.files.mapIndexed { i, source ->
        save(BackupNames.fileName(set.snapshotAt, true, i, set.files.size), source.readBytes())
    }
    @Test fun fourValidLegacySetsAreTrimmedToNewestTwoBeforeStartingNewBackup() = runBlocking {
        val old = (1L..4L).map { legacy(it) }
        assertEquals(2, BackupRetention.preTrim(context, tree))
        assertFalse(file(old[0]).exists()); assertFalse(file(old[1]).exists())
        assertTrue(file(old[2]).isFile); assertTrue(file(old[3]).isFile)
        assertEquals(2, BackupRetention.listNames(context, tree).size)
        // A failed subsequent create leaves the two retained verified sets intact.
        val work = workspace()
        try {
            TestBackupDocumentsProvider.returnExistingId = DocumentsContract.getDocumentId(old[2])
            try { BackupDocuments.writeSet(context, generic(work, 5), tree, true); fail("reused destination accepted") } catch (_: BackupWriteException) { }
            assertTrue(file(old[2]).isFile); assertTrue(file(old[3]).isFile)
        } finally { work.deleteRecursively() }
    }
    @Test fun ExactlyThreeLegacySetsAreKeptUntilNewVerifiedSetThenTotalIsThree() = runBlocking {
        val old = (1L..3L).map { legacy(it) }
        assertEquals(0, BackupRetention.preTrim(context, tree))
        assertTrue(old.all { file(it).isFile })
        val work = workspace()
        try {
            val result = BackupDocuments.writeSet(context, generic(work, 4), tree, true)
            assertTrue(result.ok); assertEquals(1, result.pruned)
            assertTrue(result.fileName!!.startsWith("SABak_auto-"))
            assertEquals(3, BackupRetention.listNames(context, tree).size)
            assertFalse(file(old[0]).exists()); assertTrue(file(old[1]).isFile); assertTrue(file(old[2]).isFile)
        } finally { work.deleteRecursively() }
    }
    @Test fun postTrimProtectsNewMultiVolumeSetAfterClockRollbackAmongLegacySets() = runBlocking {
        val old = listOf(legacy(5000), legacy(6000), legacy(7000))
        val work = workspace()
        try {
            val set = generic(work, 1)
            assertTrue(set.files.size > 1)
            assertTrue(BackupDocuments.writeSet(context, set, tree, true).ok)
            assertEquals(3, BackupRetention.listNames(context, tree).size)
            assertFalse(file(old[0]).exists()); assertTrue(file(old[1]).isFile); assertTrue(file(old[2]).isFile)
            assertEquals(set.files.size + 2, File(context.filesDir, "saf-fixture").listFiles()!!.size)
        } finally { work.deleteRecursively() }
    }
    @Test fun manualMalformedAndNonBusinessZipArePreserved() = runBlocking {
        val old = (1L..4L).map { legacy(it) }
        val manual = legacy(10, "SABak-19700101000010.sab")
        val malformed = save("study-assistant-auto-malformed.json", "not a backup".toByteArray())
        val work = workspace()
        try {
            val unknown = external(generic(work, 20)) // ZIP complete, but not a valid application backup tree.
            assertEquals(2, BackupRetention.preTrim(context, tree))
            assertTrue(file(manual).isFile); assertTrue(file(malformed).isFile)
            assertTrue(unknown.all { file(it).isFile })
            assertTrue(file(old[2]).isFile); assertTrue(file(old[3]).isFile)
        } finally { work.deleteRecursively() }
    }
    @Test fun unrecordedValidMultiVolumeArchiveCountsAsOneAndMissingSetIsProtected() = runBlocking {
        val name = "retention-source-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val work = workspace()
        try {
            val id = QuestionIdentity.create(1700000300000L)
            db.questionDao().insert(Question(id, "甲".repeat(40000), "answer", 1700000300000L))
            val exported = BackupRepository(context, db).export(File(work, "export"), volumeBytes = 16384)
            assertTrue(exported.files.size > 1)
            val documents = external(exported)
            listOf(legacy(1), legacy(2))
            assertEquals(3, BackupRetention.listNames(context, tree).size)
            assertEquals(0, BackupRetention.preTrim(context, tree))
            assertTrue(file(documents.first()).delete())
            legacy(3); legacy(4)
            assertEquals(2, BackupRetention.preTrim(context, tree))
            assertTrue(documents.drop(1).all { file(it).isFile })
        } finally { db.close(); context.deleteDatabase(name); work.deleteRecursively() }
    }
    @Test fun resumedJobDoesNotPreTrimItsExternallyCompletedClockRolledBackSet() = runBlocking {
        val old = listOf(legacy(5000), legacy(6000), legacy(7000))
        val work = workspace()
        val token = UUID.randomUUID().toString()
        try {
            assertEquals(0, BackupJobs.preTrimOnce(context, tree, token))
            val set = generic(work, 1)
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            try {
                BackupDocuments.writeSet(context, set, tree, true) {
                    if (prefs.getString("backup_job_sets", "[]")!!.contains(set.setId)) throw CancellationException("after external completion")
                }
                fail("not interrupted")
            } catch (_: CancellationException) { }
            val checkpoint = org.json.JSONObject(prefs.getString("backup_job_parts_${set.setId}", "{}")!!).getJSONArray("parts")
            val completed = (0 until checkpoint.length()).map { File(context.filesDir, "saf-fixture/${checkpoint.getJSONObject(it).getString("id")}") }
            assertTrue(completed.all { it.isFile })
            assertEquals(0, BackupJobs.preTrimOnce(context, tree, token))
            assertTrue(completed.all { it.isFile })
            assertTrue(BackupDocuments.writeSet(context, set, tree, true).ok)
            assertTrue(completed.all { it.isFile })
            assertEquals(3, BackupRetention.listNames(context, tree).size)
            assertFalse(file(old[0]).exists())
        } finally { work.deleteRecursively() }
    }
    @Test fun canceledScanOnlyRemovesItsPrivateTemporaryDirectory() = runBlocking {
        val old = (1L..4L).map { legacy(it) }
        var checks = 0
        try { BackupRetention.preTrim(context, tree) { if (++checks > 5) throw CancellationException("synthetic stop") }; fail("not canceled") }
        catch (_: CancellationException) { }
        assertTrue(old.all { file(it).isFile })
        assertTrue(File(context.noBackupFilesDir, "backup-retention").listFiles().isNullOrEmpty())
    }
}
