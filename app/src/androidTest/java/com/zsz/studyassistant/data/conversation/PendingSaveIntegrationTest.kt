package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingSaveIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: PendingSaveStore
    private lateinit var name: String
    private fun open() { db = Room.databaseBuilder(context, AppDatabase::class.java, name).build() }
    private fun repository() = PendingSaveRepository(db, store)
    private fun payload() = SavePayload(QuestionIdentity.create(10000), 10000, "synthetic", "new answer", "[]")
    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        name = "pending-save-" + UUID.randomUUID() + ".db"
        store = PendingSaveStore(context)
        store.discardAll()
        open()
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(name); store.discardAll() }
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Injected write failure was not observed") } catch (_: Exception) { }
    }
    @Test fun failedHistoryWriteSurvivesReopenAndRetainsImages() = runBlocking {
        val p = payload()
        val row = p.historyRow(null).copy(imageBytes = byteArrayOf(0, 1, -1))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON solve_sessions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        expectFailure { repository().saveHistory(row, true, 0) }
        assertEquals(1, store.scan().entries.size)
        assertNull(db.questionDao().solveSessionOnce(p.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_history")
        db.close(); open()
        assertTrue(repository().retryAll(0).entries.isEmpty())
        val restored = db.questionDao().solveSessionOnce(p.id)!!
        assertEquals(p.createdAt, restored.createdAt)
        assertArrayEquals(row.imageBytes, restored.imageBytes)
    }
    @Test fun replayPreservesNewCategoryAndReviewMetadata() = runBlocking {
        val p = payload(); val dao = db.questionDao()
        val old = Question(p.id, p.text, "old", p.createdAt, conversationJson = "[]")
        dao.insert(old)
        dao.insertReview(Review(p.id, reviewCount = 4))
        store.write(PendingSave(SaveTarget.NOTEBOOK, p, PendingSaveCodec.fingerprint(SavePayload.notebook(old))))
        val cid = dao.insertCategory(Category(name = "synthetic category"))
        dao.setCategoryForIds(listOf(p.id), cid)
        assertTrue(repository().retryAll(0).entries.isEmpty())
        assertEquals(p.answer, dao.heavyOnce(p.id)!!.answer)
        assertEquals(cid, dao.heavyOnce(p.id)!!.categoryId)
        assertEquals(4, dao.allReviewsOnce().first { it.questionId == p.id }.reviewCount)
    }
    @Test fun changedAnswerOrDeletedQuestionIsNotOverwritten() = runBlocking {
        val p = payload(); val dao = db.questionDao()
        val old = Question(p.id, p.text, "old", p.createdAt)
        dao.insert(old)
        store.write(PendingSave(SaveTarget.NOTEBOOK, p, PendingSaveCodec.fingerprint(SavePayload.notebook(old))))
        dao.updateAnswerContent(p.id, p.text, "newer edit", null)
        assertEquals(1, repository().retryAll(0).entries.size)
        assertEquals("newer edit", dao.heavyOnce(p.id)!!.answer)
        dao.softDelete(p.id)
        assertEquals(1, repository().retryAll(0).entries.size)
        assertTrue(dao.heavyOnce(p.id)!!.deleted)
        dao.deleteById(p.id)
        assertEquals(1, repository().retryAll(0).entries.size)
        assertNull(dao.heavyOnce(p.id))
    }
    @Test fun historyDeletionInvalidatesRecoveryAndOldAckCannotDeleteNewerSnapshot() = runBlocking {
        val p = payload(); val old = PendingSave(SaveTarget.HISTORY, p, null)
        store.write(old)
        val newer = old.copy(payload = p.copy(answer = "latest"), token = UUID.randomUUID().toString())
        store.write(newer); store.remove(old)
        assertEquals(newer, store.scan().entries.single())
        store.invalidateHistory(listOf(p.id))
        assertTrue(repository().retryAll(0).entries.isEmpty())
        assertNull(db.questionDao().solveSessionOnce(p.id))
    }
    @Test fun corruptRecordIsRetainedAndReported() {
        val p = payload()
        val file = File(context.noBackupFilesDir, "pending-saves-v1/history-${p.id}.json")
        file.parentFile!!.mkdirs(); file.writeText("broken synthetic record")
        assertEquals(1, store.scan().unreadable)
        assertEquals("broken synthetic record", file.readText())
    }
}
