package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** All files belong to the independent .integration.test APK, never the installed personal app. */
@RunWith(AndroidJUnit4::class)
class ReliabilityIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: SqliteTagUndoStore
    private lateinit var name: String
    private val id = QuestionIdentity.create(10000)

    private fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_9_10).build()
        store = SqliteTagUndoStore(context)
        db.tagUndoStore = store
    }
    private fun reopen() { db.close(); store.close(); open() }

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        name = "reliability-" + UUID.randomUUID() + ".db"
        TestBackupDocumentsProvider.reset(context)
        context.deleteDatabase("tag_merge_undo.db")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        TagDraftStore(context).write(null)
        AnalysisDiagnosticsStore(context).clear()
        open()
    }
    @After fun tearDown() {
        db.close(); store.close()
        // Exact test-owned database names only, guarded by the package assertion in setUp.
        context.deleteDatabase(name)
        context.deleteDatabase("tag_merge_undo.db")
        TagDraftStore(context).write(null)
        AnalysisDiagnosticsStore(context).clear()
    }
    private suspend fun seed(): List<Long> {
        val dao = db.questionDao()
        val category = dao.insertCategory(Category(name = "fixture", createdAt = 10000))
        dao.insert(Question(id, "synthetic question", "synthetic answer", 10000,
            byteArrayOf(1, 2), "[]", categoryId = category))
        dao.insertImages(listOf(QuestionImage(questionId = id, position = 0, bytes = byteArrayOf(1, 2), createdAt = 10000),
            QuestionImage(questionId = id, position = 1, bytes = byteArrayOf(3, 4), createdAt = 10000)))
        dao.insertReviews(listOf(Review(id, reviewCount = 3)))
        val tags = listOf("vector", "vectors", "matrix", "matrices").map { dao.insertTag(Tag(name = it, createdAt = 10000)) }
        dao.insertQuestionTags(tags.map { QuestionTag(id, it) })
        dao.insertSolveSession(SolveSession(id = id, createdAt = 10000, updatedAt = 10000,
            source = "text", text = "synthetic question", answer = "synthetic answer", savedQuestionId = id))
        return tags
    }

    @Test fun backupRoundTripDeduplicatesAndPreservesImagesAndLinks() = runBlocking {
        seed()
        val text = BackupManager.export(db)
        BackupManager.clearAll(db)
        assertEquals(1, BackupManager.import(db, text).questions)
        assertEquals(0, BackupManager.import(db, text).questions)
        val dao = db.questionDao()
        assertEquals(id, dao.allQuestionsOnce().single().id)
        assertEquals(id, dao.allSolveSessionsOnce().single().savedQuestionId)
        assertEquals(4, dao.allQuestionTagsOnce().size)
        assertEquals(3, dao.allReviewsOnce().single().reviewCount)
        val images = dao.imagesForQuestion(id).sortedBy { it.position }
        assertEquals(2, images.size)
        assertArrayEquals(byteArrayOf(3, 4), images[1].bytes)
    }

    @Test fun failedImportRollsBackPreviouslyWrittenCategoriesAndTags() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_fail BEFORE INSERT ON questions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        val backup = BackupFile(categories = listOf(BackupCategory(1, "category")),
            tags = listOf(BackupTag(1, "tag")),
            questions = listOf(BackupQuestion(id, "fixture", "answer", 10000, categoryId = 1)))
        var failed = false
        try { BackupManager.importFile(db, backup) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertTrue(db.questionDao().allQuestionsOnce().isEmpty())
        assertTrue(db.questionDao().allCategoriesOnce().isEmpty())
        assertTrue(db.questionDao().getAllTagsOnce().isEmpty())
    }

    @Test fun committedMergeCanBeUndoneAfterBothDatabasesReopen() = runBlocking {
        val tags = seed()
        val before = TagMergeRepository.snapshot(db).first
        TagMergeRepository.apply(db, TagMergeEngine.preview(before, listOf(TagMergeGroup(tags.take(2), "vector"))))
        reopen()
        val (current, undo) = TagMergeRepository.snapshot(db)
        assertTrue(TagMergeEngine.canUndo(current, undo))
        TagMergeRepository.undo(db)
        assertEquals(before, TagMergeRepository.snapshot(db).first)
        assertEquals(id, db.questionDao().allQuestionsOnce().single().id)
        assertArrayEquals(byteArrayOf(1, 2), db.questionDao().imagesForQuestion(id).first().bytes)
    }

    @Test fun committedCandidateIsPromotedAfterReopen() = runBlocking {
        val tags = seed()
        val before = TagMergeRepository.snapshot(db).first
        db.withTransaction {
            TagMergeEngine.apply(db.questionDao(), TagMergeEngine.preview(before,
                listOf(TagMergeGroup(tags.take(2), "vector"))), stageUndo = store::stage)
        }
        assertNotNull(store.read(2))
        reopen()
        val (current, undo) = TagMergeRepository.snapshot(db)
        assertTrue(TagMergeEngine.canUndo(current, undo))
        assertNull(store.read(2)); assertNotNull(store.read(1))
    }

    @Test fun rolledBackCandidateDoesNotReplacePreviousUndo() = runBlocking {
        val tags = seed()
        TagMergeRepository.apply(db, TagMergeEngine.preview(TagMergeRepository.snapshot(db).first,
            listOf(TagMergeGroup(tags.take(2), "vector"))))
        val previousUndo = store.read(1)
        val before = TagMergeRepository.snapshot(db).first
        try {
            db.withTransaction {
                TagMergeEngine.apply(db.questionDao(), TagMergeEngine.preview(before,
                    listOf(TagMergeGroup(tags.drop(2), "matrix"))), stageUndo = store::stage)
                error("Injected interruption before commit")
            }
        } catch (_: IllegalStateException) { }
        reopen()
        assertEquals(before, TagMergeRepository.snapshot(db).first)
        assertEquals(previousUndo, store.read(1)); assertNull(store.read(2))
    }

    @Test fun actualRoomUpgradeKeepsOldQuestionsAndImageBytes() = runBlocking {
        db.close(); store.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("legacy-v9.sql").bufferedReader().use { it.readText() }
            sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
            old.version = 9
        }
        open()
        val questions = db.questionDao().allQuestionsOnce()
        assertEquals(10, db.openHelper.readableDatabase.version)
        assertEquals(3, questions.size)
        assertTrue(questions.all { QuestionIdentity.valid(it.id) })
        val one = questions.first { it.text == "one" }
        assertArrayEquals(byteArrayOf(1, 2), db.questionDao().imagesForQuestion(one.id).first().bytes)
        assertEquals(7, db.questionDao().allReviewsOnce().single().reviewCount)
    }

    @Test fun backupFailurePersistsWithoutExceptionTextAndCancellationDoesNotReplaceIt() = runBlocking {
        AutoBackup.setTreeUri(context, Uri.parse("content://fixture/tree/one"))
        val result = AutoBackup.runIfDue(context) { error("Synthetic private exception text") }
        assertEquals("data.autoBackup.snapshotFailed", result?.errorKey)
        val failure = AutoBackup.lastFailure(context)
        try { AutoBackup.runIfDue(context) { throw CancellationException() } } catch (_: CancellationException) { }
        assertEquals(failure, AutoBackup.lastFailure(context))
        val strings = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.values.filterIsInstance<String>()
        assertFalse(strings.any { it.contains("Synthetic private exception") })
        AutoBackup.setTreeUri(context, Uri.parse("content://fixture/tree/two"))
        assertNull(AutoBackup.lastFailure(context)); assertEquals(0, AutoBackup.lastBackupAt(context))
    }

    @Test fun deniedSafWritePersistsWarningUntilVerifiedRetry() = runBlocking {
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(context.packageName + ".fixture.backups", "root")
        AutoBackup.setTreeUri(context, tree)
        TestBackupDocumentsProvider.denyAccess = true
        val failed = AutoBackup.runBackup(context, "synthetic backup", 10000)
        assertFalse(failed.ok)
        assertEquals("data.autoBackup.permissionFailed", AutoBackup.lastFailure(context)?.key)
        TestBackupDocumentsProvider.denyAccess = false
        val success = AutoBackup.runBackup(context, "synthetic backup", 20000)
        assertTrue(success.ok); assertNull(AutoBackup.lastFailure(context))
        assertEquals(20000L, AutoBackup.lastBackupAt(context))
    }

    @Test fun badSafReadbackPreservesOldBackupAndSuccessTimestamp() = runBlocking {
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(context.packageName + ".fixture.backups", "root")
        AutoBackup.setTreeUri(context, tree)
        assertTrue(AutoBackup.runBackup(context, "verified original", 10000).ok)
        val before = AutoBackup.listBackups(context)
        TestBackupDocumentsProvider.corruptReads = true
        assertFalse(AutoBackup.runBackup(context, "unverified replacement", 20000).ok)
        assertEquals(before, AutoBackup.listBackups(context))
        assertEquals(10000L, AutoBackup.lastBackupAt(context))
        assertEquals("data.autoBackup.verifyFailed", AutoBackup.lastFailure(context)?.key)
        TestBackupDocumentsProvider.corruptReads = false
    }

    @Test fun olderViewModelWriteCannotReplaceNewerDraftOrClear() {
        val base = TagInventory(listOf(Tag(1, "fixture", 10000)), emptyList())
        val old = TagDraftDocument(fingerprint = base.fingerprint(), savedAt = 10000,
            drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(1), "old"))))
        val newer = old.copy(savedAt = 20000, drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(1), "new"))))
        val oldTicket = TagDraftStore.reserveRevision()
        val newTicket = TagDraftStore.reserveRevision()
        assertTrue(TagDraftStore(context).write(newer, newTicket))
        assertFalse(TagDraftStore(context).write(old, oldTicket))
        assertEquals(newer, TagDraftStore(context).read())
        val clearTicket = TagDraftStore.reserveRevision()
        assertTrue(TagDraftStore(context).write(null, clearTicket))
        assertFalse(TagDraftStore(context).write(old, newTicket))
        assertNull(TagDraftStore(context).read())
    }

    @Test fun atomicDraftAndDiagnosticsSurviveStorageRecreation() {
        val tag = Tag(1, "synthetic", 10000)
        val base = TagInventory(listOf(tag), emptyList())
        val document = TagDraftDocument(fingerprint = base.fingerprint(), savedAt = 10000,
            drafts = listOf(StoredTagDraft(TagMergeGroup(listOf(1), "unfinished"), true)))
        TagDraftStore(context).write(document)
        assertEquals(document, TagDraftStore(context).read())
        val path = File(context.noBackupFilesDir, "atomic-fixture.json")
        val file = PrivateJsonFile(path, 64)
        file.write("original")
        AtomicFile(path).startWrite().use { it.write("interrupted".toByteArray()) }
        assertEquals("original", file.read())
        file.clear()
        val diagnostics = AnalysisDiagnosticsStore(context)
        diagnostics.append(AnalysisDiagnostic(UUID.randomUUID().toString(), 10000, 12,
            AnalysisMode.FAST, 2, AnalysisOutcome.SUCCESS, AnalysisFinish.STOP, AnalysisFailure.NONE,
            200, 1, 0))
        assertEquals(1, AnalysisDiagnosticsStore(context).read().records.size)
        assertTrue(AnalysisDiagnosticsStore(context).export().contains("SUCCESS"))
    }
}
