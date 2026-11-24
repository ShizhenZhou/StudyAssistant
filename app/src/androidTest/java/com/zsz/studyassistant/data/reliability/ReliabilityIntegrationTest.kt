package com.zsz.studyassistant.data

import androidx.room3.support.getSupportWrapper
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.room3.Room
import androidx.room3.withWriteTransaction
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
            .addMigrations(MIGRATION_9_10, MIGRATION_10_11).addCallback(SOLVE_SESSION_TITLE_CALLBACK)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
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
        val initial = db.questionDao().solveSessionOnce(id)!!
        db.questionDao().updateSolveSession(initial.copy(conversationJson =
            """[{"id":0,"role":"question","content":"synthetic question","conversationTitle":"Backup title"}]"""))
        val text = BackupManager.export(db)
        BackupManager.clearAll(db)
        assertEquals(1, BackupManager.import(db, text).questions)
        assertEquals(0, BackupManager.import(db, text).questions)
        val dao = db.questionDao()
        assertEquals(id, dao.allQuestionsOnce().single().id)
        assertEquals(id, dao.allSolveSessionsOnce().single().savedQuestionId)
        assertEquals("Backup title", dao.sessionLightOnce(id)!!.title)
        assertEquals(4, dao.allQuestionTagsOnce().size)
        assertEquals(3, dao.allReviewsOnce().single().reviewCount)
        val images = dao.imagesForQuestion(id).sortedBy { it.position }
        assertEquals(2, images.size)
        assertArrayEquals(byteArrayOf(3, 4), images[1].bytes)
    }

    @Test fun failedImportRollsBackPreviouslyWrittenCategoriesAndTags() = runBlocking {
        db.getSupportWrapper().execSQL("CREATE TRIGGER fixture_fail BEFORE INSERT ON questions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
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
        db.withWriteTransaction {
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
            db.withWriteTransaction {
                TagMergeEngine.apply(db.questionDao(), TagMergeEngine.preview(before,
                    listOf(TagMergeGroup(tags.drop(2), "matrix"))), stageUndo = store::stage)
                error("Injected interruption before commit")
            }
        } catch (_: IllegalStateException) { }
        reopen()
        assertEquals(before, TagMergeRepository.snapshot(db).first)
        assertEquals(previousUndo, store.read(1)); assertNull(store.read(2))
    }

    @Test fun schema10MigratesTitleCacheAndKeepsLegacyPreview() = runBlocking {
        db.close(); store.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val sql = InstrumentationRegistry.getInstrumentation().context.assets
                .open("room2-v10.sql").bufferedReader().use { it.readText() }
            sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
            old.execSQL("INSERT INTO questions (id,text,answer,createdAt,conversationJson,deleted) VALUES (?,?,?,?,?,0)",
                arrayOf<Any>(id, "Room 2 fixture", "preserved answer", 10000,
                    """[{"role":"question","images":["AQI=","AwQ="]}]"""))
            old.execSQL("INSERT INTO solve_sessions (id,createdAt,updatedAt,source,text,answer,conversationJson,savedQuestionId) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf<Any>(id, 10000, 11000, "text", "Complete original question", "preserved answer",
                    """[{"id":0,"role":"question","content":"Complete original question","conversationTitle":"Migrated title"}]""", id))
            // More than one migration batch; one conversation exceeds CursorWindow size.
            repeat(65) { index ->
                val payload = if (index == 64) "x".repeat(2_200_000) else "fixture"
                old.execSQL("INSERT INTO solve_sessions (id,createdAt,updatedAt,source,text,answer,conversationJson) VALUES (?,?,?,?,?,?,?)",
                    arrayOf<Any>(QuestionIdentity.create(30000L + index), 30000L + index, 30000L + index,
                        "text", "fixture", "answer", """[{"content":"$payload","conversationTitle":"Batch $index"}]"""))
            }
            old.version = 10
        }
        open()
        assertEquals("preserved answer", db.questionDao().allQuestionsOnce().single().answer)
        val images = db.previewImages(id)
        assertEquals(2, images.size)
        assertArrayEquals(byteArrayOf(1, 2), images[0])
        assertArrayEquals(byteArrayOf(3, 4), images[1])
        assertEquals(2, db.previewCard(id, "Room 2 fixture").images.size)
        db.getSupportWrapper().query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst())
            assertNotEquals("d66e3125b1edecdae1ee701ddbb0c951", it.getString(0))
        }
        assertEquals(11, db.getSupportWrapper().version)
        val migrated = db.questionDao().solveSessionOnce(id)!!
        assertEquals("Migrated title", migrated.title)
        assertEquals(66, db.questionDao().countSolveSessions())
        assertEquals("Batch 64", db.questionDao().solveSessionsLightOnce().single { it.createdAt == 30064L }.title)
        assertEquals("Complete original question", migrated.text)
        assertEquals(10000L, migrated.createdAt)
        assertEquals(11000L, migrated.updatedAt)
        assertEquals(id, migrated.savedQuestionId)
        reopen()
        assertEquals(id, db.questionDao().allQuestionsOnce().single().id)
        assertEquals("Migrated title", db.questionDao().sessionLightOnce(id)!!.title)
        val sql = db.getSupportWrapper()
        sql.execSQL("UPDATE questions SET conversationJson=? WHERE id=?", arrayOf(
            """[{"role":"question","content":"题目 + 我的作答","images":["AQI=","AwQ="],"ocrCorrections":["题干 OCR","作答 OCR"]}]""", id
        ))
        val reviewCard = db.previewCard(id, "Room 2 fixture")
        assertEquals("题干 OCR", reviewCard.questionText)
        assertArrayEquals(byteArrayOf(1, 2), reviewCard.images.single())
        sql.execSQL("UPDATE questions SET text=?, conversationJson=? WHERE id=?", arrayOf("题目",
            """[{"role":"question","content":"题目","images":["AQI="],"ocrCorrections":["单图 OCR"]}]""", id
        ))
        val singlePhotoCard = db.previewCard(id, "题目")
        assertEquals("单图 OCR", singlePhotoCard.questionText)
        assertArrayEquals(byteArrayOf(1, 2), singlePhotoCard.images.single())
        // DAO writes derive the cache without SQLite JSON extensions. Bak v4 has its own
        // bounded row hydration (covered by archiveRoundTripPreservesFieldsAndGlobalImageDedup).
        val restoredId = QuestionIdentity.create(20000)
        val restored = SolveSession(restoredId, 20000, 20000, "text", "Restored source", "Restored answer",
            """[{"id":0,"role":"question","content":"Restored source","conversationTitle":"Restored title"}]""")
        db.questionDao().insertSolveSessions(listOf(restored.copy(title = "stale cache")))
        assertEquals("Restored title", db.questionDao().sessionLightOnce(restoredId)!!.title)
        for (invalid in listOf("not-json", "[]", """[{"conversationTitle":{"unexpected":"object"}}]""",
            """[{"conversationTitle":"valid prefix"}] trailing""")) {
            db.questionDao().updateSolveSession(restored.copy(conversationJson = invalid))
            assertNull(db.questionDao().sessionLightOnce(restoredId)!!.title)
        }
        db.questionDao().updateSolveSession(migrated.copy(title = "stale cache"))
        assertEquals("Migrated title", db.questionDao().sessionLightOnce(id)!!.title)
        db.questionDao().updateSolveSession(restored.copy(conversationJson = """[{"conversationTitle":"Updated title"}]"""))
        assertEquals("Updated title", db.questionDao().sessionLightOnce(restoredId)!!.title)
        sql.query("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'solve_sessions_title_%'").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        sql.execSQL("CREATE TRIGGER solve_sessions_title_update AFTER UPDATE OF conversationJson ON solve_sessions " +
            "BEGIN SELECT RAISE(ABORT, 'legacy JSON1 trigger must be removed'); END")
        reopen()
        db.questionDao().updateSolveSession(restored)
        assertEquals("Restored title", db.questionDao().sessionLightOnce(restoredId)!!.title)
    }

    @Test fun documentCleanupStreamsLargeReferencesAndFailsClosedOnMalformedJson() = runBlocking {
        val work = File(context.cacheDir, "document-maintenance-${UUID.randomUUID()}").apply { mkdirs() }
        val storageContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = File(work, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(work, "private").apply { mkdirs() }
        }
        try {
            val originals = File(storageContext.filesDir, "ai-documents").apply { mkdirs() }
            val importTemp = File.createTempFile("import-", ".tmp", originals)
            val installTemp = File.createTempFile("install-", ".tmp", originals)
            val unknown = File(originals, "import-other.tmp").apply { writeText("keep") }
            val hash = "a".repeat(64)
            val original = File(originals, hash).apply { writeText("synthetic original") }
            val old = System.currentTimeMillis() - 9L * 24 * 60 * 60 * 1000
            assertTrue(original.setLastModified(old))
            val registry = File(storageContext.noBackupFilesDir, "ai-document-maintenance-v1/state.json")
            registry.parentFile!!.mkdirs()
            val observed = """{"version":1,"entries":[{"sha256":"$hash","bytes":${original.length()},"modifiedAt":${original.lastModified()},"firstUnreferencedAt":$old}]}"""
            registry.writeText(observed)
            val body = "x".repeat(2_200_000)
            val valid = """[{"content":"$body","documents":[{"sha256":"${"b".repeat(64)}","localDocuments":[{"sha256":"$hash"}]}]}]"""
            val row = Question(id, "synthetic", "answer", conversationJson = valid, deleted = true)
            db.questionDao().insert(row)
            val result = DocumentStorageMaintenance.inspect(storageContext, db, emptySet(), clean = true)
            assertEquals(0, result.removedCount)
            assertTrue(original.isFile)
            assertFalse(importTemp.exists())
            assertFalse(installTemp.exists())
            assertTrue(unknown.isFile)
            val currentTemp = File.createTempFile("import-", ".tmp", originals)
            DocumentStore.recoverInterruptedWrites(originals)
            assertTrue(currentTemp.exists())
            for (invalid in listOf("not-json", """[{"documents":null}]""",
                """[{"documents":[{"sha256":"$hash","sha256":"$hash"}]}]""")) {
                db.getSupportWrapper().execSQL("UPDATE questions SET conversationJson=? WHERE id=?", arrayOf(invalid, id))
                registry.writeText(observed) // otherwise eligible for deletion on this pass
                try {
                    DocumentStorageMaintenance.inspect(storageContext, db, emptySet(), clean = true)
                    fail("Unsafe attachment scan accepted")
                } catch (_: DocumentProblem) { }
                assertTrue(original.isFile)
                assertEquals(observed, registry.readText())
            }
        } finally { work.deleteRecursively() }
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
        assertEquals(11, db.getSupportWrapper().version)
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
        val before = BackupRetention.listNames(context, tree)
        TestBackupDocumentsProvider.corruptReads = true
        assertFalse(AutoBackup.runBackup(context, "unverified replacement", 20000).ok)
        assertEquals(before, BackupRetention.listNames(context, tree))
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
