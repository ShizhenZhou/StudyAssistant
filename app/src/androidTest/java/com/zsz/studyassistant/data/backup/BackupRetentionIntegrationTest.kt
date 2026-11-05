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
    /** A real repository snapshot, re-packed only to give each synthetic fixture a deterministic time. */
    private suspend fun business(work: File, at: Long, multi: Boolean = false): BackupArchive.ArchiveSet {
        val name = "folder-summary-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val created = 1700000300000L
            db.questionDao().insert(Question(QuestionIdentity.create(created),
                if (multi) "甲".repeat(40000) else "synthetic question", "answer", created))
            val directory = File(work, "business-${UUID.randomUUID()}")
            BackupRepository(context, db).export(directory)
            return BackupArchive.writeTree(File(directory, "tree"), File(directory, "timed"), at,
                if (multi) 16384L else BackupArchive.DEFAULT_VOLUME_BYTES)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun external(set: BackupArchive.ArchiveSet, automatic: Boolean): List<Uri> =
        set.files.mapIndexed { i, source -> save(BackupNames.fileName(set.snapshotAt, automatic, i, set.files.size), source.readBytes()) }

    private fun assertSummary(expected: BackupRetention.FolderSummary, actual: BackupRetention.FolderSummary) {
        assertEquals(expected.manual, actual.manual)
        assertEquals(expected.automatic, actual.automatic)
        assertEquals(expected.latestAt, actual.latestAt)
        assertEquals(expected.backupDays, actual.backupDays)
    }

    @Test fun immediateManualWriteWithoutLedgerCountsAndUsesInternalSnapshotTime() = runBlocking {
        val work = workspace()
        try {
            val set = business(work, 1234)
            assertTrue(BackupDocuments.writeSet(context, set, tree, false).ok)
            assertEquals("[]", context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(BackupRetention.LEDGER, "[]"))
            File(context.filesDir, "saf-fixture").listFiles()!!.forEach { assertTrue(it.setLastModified(999999999999L)) }
            assertSummary(BackupRetention.FolderSummary(manual = 1, latestAt = 1234, backupDays = setOf(AutoBackup.dayKey(1234))), BackupRetention.folderSummary(context, tree))
            assertEquals(0, BackupRetention.listNames(context, tree).size)
        } finally { work.deleteRecursively() }
    }
    @Test fun freshFolderSummaryCountsUnrecordedMixedSetsAndLegacyWithOneCountPerSet() = runBlocking {
        val work = workspace()
        try {
            val manual = business(work, 100, multi = true)
            val automatic = business(work, 200, multi = true)
            assertTrue(manual.files.size > 1); assertTrue(automatic.files.size > 1)
            val documents = external(manual, false)
            external(automatic, true)
            val old = legacy(300)
            assertSummary(BackupRetention.FolderSummary(1, 2, 300, backupDays = setOf(AutoBackup.dayKey(300))), BackupRetention.folderSummary(context, tree))
            assertTrue(file(old).delete())
            assertTrue(file(documents.first()).delete())
            assertSummary(BackupRetention.FolderSummary(0, 1, 200, backupDays = setOf(AutoBackup.dayKey(200))), BackupRetention.folderSummary(context, tree))
        } finally { work.deleteRecursively() }
    }
    @Test fun folderSummaryExcludesCorruptUnrelatedAndIncompleteArchives() = runBlocking {
        val work = workspace()
        try {
            val valid = business(work, 100, multi = true)
            val documents = external(valid, false)
            assertTrue(file(documents.first()).delete())
            val corrupt = external(business(work, 200), true).single()
            file(corrupt).writeText("broken zip")
            external(generic(work, 300), false)
            save("SABak-19700101000001.sab", "ignored".toByteArray())
            save("unrelated.zip", "ignored".toByteArray())
            assertSummary(BackupRetention.FolderSummary(), BackupRetention.folderSummary(context, tree))
        } finally { work.deleteRecursively() }
    }
    @Test fun retentionNeverDeletesManualSetsIncludedInFolderSummary() = runBlocking {
        val work = workspace()
        try {
            val manual = external(business(work, 900, multi = true), false)
            (1L..4L).forEach { legacy(it) }
            assertSummary(BackupRetention.FolderSummary(1, 4, 900, backupDays = setOf(AutoBackup.dayKey(900))), BackupRetention.folderSummary(context, tree))
            assertEquals(2, BackupRetention.preTrim(context, tree))
            assertTrue(manual.all { file(it).isFile })
            assertSummary(BackupRetention.FolderSummary(1, 2, 900, backupDays = setOf(AutoBackup.dayKey(900))), BackupRetention.folderSummary(context, tree))
        } finally { work.deleteRecursively() }
    }

    @Test fun folderSummaryPropagatesFolderAccessFailureAndCancellation() {
        TestBackupDocumentsProvider.denyListing = true
        try {
            BackupRetention.folderSummary(context, tree)
            fail("folder listing failure hidden")
        } catch (_: Exception) {
        } finally { TestBackupDocumentsProvider.denyListing = false }
        try {
            BackupRetention.folderSummary(context, tree) { throw CancellationException("synthetic stop") }
            fail("folder summary cancellation hidden")
        } catch (_: CancellationException) { }
    }

    @Test fun staleLedgerAndRetentionProtectionCannotHideValidReplacementContents() = runBlocking {
        val work = workspace()
        try {
            assertTrue(BackupDocuments.writeSet(context, business(work, 100), tree, true).ok)
            val document = BackupRetention.list(context, tree).single()
            val replacement = business(work, 200)
            file(BackupRetention.uri(tree, document.id)).writeBytes(replacement.files.single().readBytes())
            BackupRetention.protectDocuments(context, tree, setOf(document.id))
            assertSummary(BackupRetention.FolderSummary(0, 1, 200, backupDays = setOf(AutoBackup.dayKey(200))), BackupRetention.folderSummary(context, tree))
            // Retention still refuses to delete protected/replaced owned documents.
            (1L..4L).forEach { legacy(it) }
            assertEquals(2, BackupRetention.preTrim(context, tree))
            assertTrue(file(BackupRetention.uri(tree, document.id)).isFile)
        } finally { work.deleteRecursively() }
    }

    @Test fun folderDaysRetainTodayDespiteFutureDatedCompleteBackup() = runBlocking {
        val work = workspace()
        try {
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val today = calendar.timeInMillis
            calendar.add(java.util.Calendar.DAY_OF_MONTH, 2)
            val future = calendar.timeInMillis
            external(business(work, future), true)
            external(business(work, today), false)
            val summary = BackupRetention.folderSummary(context, tree)
            assertEquals(1, summary.manual)
            assertEquals(1, summary.automatic)
            assertEquals(future, summary.latestAt)
            assertEquals(setOf(AutoBackup.dayKey(today), AutoBackup.dayKey(future)), summary.backupDays)
            // Both kinds contribute independently: a future manual set must not hide today's auto.
            TestBackupDocumentsProvider.reset(context)
            external(business(work, future), false)
            external(business(work, today), true)
            val swapped = BackupRetention.folderSummary(context, tree)
            assertEquals(future, swapped.latestAt)
            assertTrue(AutoBackup.dayKey(today) in swapped.backupDays)
        } finally { work.deleteRecursively() }
    }

    @Test fun customNamedBusinessZipAndLegacyJsonCountAsManualAndRemainOutsideRetention() = runBlocking {
        val work = workspace()
        try {
            val archive = business(work, 100, multi = true)
            val custom = archive.files.mapIndexed { index, source -> save("my-backup-$index.zip", source.readBytes()) }
            val json = legacy(200, "my-old-backup.json")
            val summary = BackupRetention.folderSummary(context, tree)
            assertEquals(2, summary.manual)
            assertEquals(0, summary.automatic)
            assertEquals(200L, summary.latestAt)
            (1L..4L).forEach { legacy(it) }
            assertEquals(2, BackupRetention.preTrim(context, tree))
            assertTrue(custom.all { file(it).isFile })
            assertTrue(file(json).isFile)
        } finally { work.deleteRecursively() }
    }

    @Test fun localSpaceFailureCannotBecomeAnEmptyOrPartialFolderSummary() {
        val documents = (1L..2L).map { legacy(it) }
        for (failAt in 1..2) {
            var copies = 0
            try {
                BackupRetention.folderSummary(context, tree, {}, { _, _ ->
                    if (++copies == failAt) throw BackupValidationException("data.backupSpaceInsufficient")
                })
                fail("local space failure became a summary")
            } catch (error: BackupValidationException) {
                assertEquals("data.backupSpaceInsufficient", error.messageKey)
            }
            assertTrue(documents.all { file(it).isFile })
            assertTrue(File(context.noBackupFilesDir, "backup-retention").listFiles().isNullOrEmpty())
        }
    }

    @Test fun incompleteCatalogNeverStartsRetentionDeletion() = runBlocking {
        val documents = (1L..5L).map { legacy(it) }
        var copies = 0
        try {
            BackupRetention.preTrim(context, tree, {}, { _, _ ->
                if (++copies == 5) throw java.io.IOException("synthetic private staging failure")
            })
            fail("partial catalog used for retention")
        } catch (_: java.io.IOException) { }
        assertTrue(documents.all { file(it).isFile })
        assertTrue(File(context.noBackupFilesDir, "backup-retention").listFiles().isNullOrEmpty())
    }

    @Test fun archiveGroupRevalidationSpaceFailureIsNotSkippedAfterSuccessfulHeaderProbe() = runBlocking {
        val work = workspace()
        try {
            val documents = external(business(work, 100), true)
            var firstDirectory: File? = null
            try {
                BackupRetention.folderSummary(context, tree, {}, { directory, _ ->
                    if (firstDirectory == null) firstDirectory = directory
                    else if (directory != firstDirectory) throw BackupValidationException("data.backupSpaceInsufficient")
                })
                fail("group staging failure became an empty summary")
            } catch (error: BackupValidationException) {
                assertEquals("data.backupSpaceInsufficient", error.messageKey)
            }
            assertTrue(documents.all { file(it).isFile })
        } finally { work.deleteRecursively() }
    }

    @Test fun providerReadFailureAfterSuccessfulListingPropagatesAndPreservesFiles() = runBlocking {
        val documents = (1L..5L).map { legacy(it) }
        var checks = 0
        try {
            BackupRetention.preTrim(context, tree) {
                if (++checks == 2) TestBackupDocumentsProvider.denyAccess = true
            }
            fail("provider read failure used as a partial catalog")
        } catch (_: SecurityException) {
        } finally { TestBackupDocumentsProvider.denyAccess = false }
        assertTrue(documents.all { file(it).isFile })
    }

    @Test fun truncatedLegacyIsPreservedAndSkippedWhileValidSummaryStillCounts() {
        val valid = legacy(123)
        val truncated = save("study-assistant-auto-truncated.json",
            """{"format":"study-assistant-backup","version":3,"questions":[""".toByteArray())
        val summary = BackupRetention.folderSummary(context, tree)
        assertEquals(1, summary.automatic)
        assertEquals(123L, summary.latestAt)
        assertTrue(file(valid).isFile)
        assertTrue(file(truncated).isFile)
    }

    @Test fun privateSqliteIoFailureIsNotReclassifiedAsMalformedBackup() = runBlocking {
        val source = legacy(123)
        val work = workspace()
        val failure = android.database.sqlite.SQLiteDiskIOException("synthetic staging IO failure")
        var validating = false
        try {
            try {
                BackupRepository(context).prepareImportFiles(listOf(file(source)), File(work, "validate"),
                    check = { if (validating) throw failure },
                    progress = { phase, _ -> if (phase == "data.backupValidate") validating = true })
                fail("SQLite disk failure hidden as backup validation")
            } catch (error: android.database.sqlite.SQLiteDiskIOException) {
                assertSame(failure, error)
            }
            assertTrue(file(source).isFile)
        } finally { work.deleteRecursively() }
    }

    @Test fun ledgerVerificationProviderFailureCannotBeSkippedBeforeRetention() = runBlocking {
        val documents = (1L..5L).map { legacy(it) }
        val known = documents.last()
        val local = file(known)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(local.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = org.json.JSONObject().put("tree", tree.toString()).put("automatic", true)
            .put("complete", true).put("id", "synthetic-ledger").put("at", 5L)
            .put("parts", org.json.JSONArray().put(org.json.JSONObject()
                .put("id", DocumentsContract.getDocumentId(known)).put("length", local.length()).put("sha", digest)))
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(BackupRetention.LEDGER, org.json.JSONArray().put(entry).toString()).commit()
        var checks = 0
        try {
            BackupRetention.preTrim(context, tree) {
                if (++checks == 2) TestBackupDocumentsProvider.denyAccess = true
            }
            fail("ledger provider failure used as a partial catalog")
        } catch (_: SecurityException) {
        } finally { TestBackupDocumentsProvider.denyAccess = false }
        assertTrue(documents.all { file(it).isFile })
    }

    @Test fun malformedBusinessJsonWithValidArchiveDigestsIsSkippedAndPreserved() = runBlocking {
        val work = workspace()
        try {
            val malformed = listOf("{}", "[1]", """[{"id":"not-a-number","role":"user","content":"text"}]""")
            val documents = malformed.mapIndexed { index, text ->
                val original = business(work, 100L + index)
                val treeDirectory = File(work, "malformed-tree-$index")
                BackupArchive.readParts(original.files, treeDirectory)
                val row = File(treeDirectory, "questions/000000000000.row")
                val fields = java.io.DataInputStream(row.inputStream()).use { input ->
                    List(BackupTree.tables.first { it.name == "questions" }.columns.size) {
                        val length = input.readInt()
                        if (length == -1) null else ByteArray(length).also { input.readFully(it) }
                    }.toMutableList()
                }
                fields[5] = text.toByteArray(Charsets.UTF_8) // conversationJson
                java.io.DataOutputStream(row.outputStream()).use { output ->
                    fields.forEach { bytes ->
                        output.writeInt(bytes?.size ?: -1)
                        if (bytes != null) output.write(bytes)
                    }
                }
                // Repack after mutation: ZIP CRC, block SHA and commit digests are all valid.
                external(BackupArchive.writeTree(treeDirectory, File(work, "malformed-out-$index"), 100L + index), false)
            }.flatten()
            val valid = legacy(999)
            val summary = BackupRetention.folderSummary(context, tree)
            assertEquals(0, summary.manual)
            assertEquals(1, summary.automatic)
            assertEquals(999L, summary.latestAt)
            assertTrue(documents.all { file(it).isFile })
            assertTrue(file(valid).isFile)
        } finally { work.deleteRecursively() }
    }

    @Test fun inMemoryBusinessJsonParserRejectsTypesAndPreservesOperationalFailures() {
        val work = workspace()
        try {
            listOf("{}", "[false]").forEach { text ->
                try {
                    BackupTree.imagesJson(text, work, restore = true, conversation = false, check = {})
                    fail("wrong image JSON type accepted")
                } catch (error: BackupValidationException) { assertEquals("data.backupInvalid", error.messageKey) }
            }
            val ioFailure = java.io.IOException("synthetic image validation IO")
            val spaceFailure = BackupValidationException("data.backupSpaceInsufficient")
            val cancellation = CancellationException("synthetic image validation cancellation")
            for (failure in listOf(ioFailure, spaceFailure, cancellation)) {
                try {
                    BackupTree.imagesJson("[1]", work, restore = true, conversation = true, check = { throw failure })
                    fail("operational failure swallowed")
                } catch (actual: Exception) { assertSame(failure, actual) }
            }
        } finally { work.deleteRecursively() }
    }

}
