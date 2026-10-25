package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.ChatItem
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic fixtures in the isolated integration APK; no production database access. */
@RunWith(AndroidJUnit4::class)
class CloseoutIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: ConversationDraftStore
    private val ids=mutableListOf<String>()
    private fun id(time: Long=10000)=QuestionIdentity.create(time).also { ids+=it }
    @Before fun setup() {
        context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration")
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        store=ConversationDraftStore(context)
    }
    @After fun cleanup() { db.close();store.invalidate(ids) }
    @Test fun hugeConversationCanBeReadWithoutOneCursorWindowAndListStaysLight() = runBlocking {
        val dao=db.questionDao();val id=id();val conversation="汉字🔬".repeat(350000)
        dao.insert(Question(id,"fixture","answer",10000,conversationJson=conversation))
        dao.insertSolveSession(SolveSession(id,createdAt=10000,updatedAt=10000,conversationJson=conversation))
        val light=dao.getAll().first().single()
        assertNull(light.conversationJson);assertNull(light.imageBytes)
        assertEquals(conversation,dao.heavyOnce(id)!!.conversationJson)
        assertEquals(conversation,dao.solveSessionOnce(id)!!.conversationJson)
    }
    @Test fun pendingDraftReservationsRejectLateWritersAcrossGenerationAndDeletion() {
        val id=id();val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        val initial=ConversationDraft(id,10000,a,"fixture","initial","text","fixture",false,true,false,true,null,emptyList(),listOf(ChatItem(0,"question","q")),emptyList())
        store.reserve(id,a);store.write(initial.copy(raw="new",revision=2),null)
        store.write(initial.copy(raw="old",revision=1),null)
        assertEquals("new",store.read(id)!!.raw)
        store.reserve(id,b);store.write(initial.copy(generation=b,raw="second"),null)
        store.write(initial.copy(raw="late",revision=100),null)
        assertEquals("second",store.read(id)!!.raw)
        store.invalidate(listOf(id));store.write(initial.copy(generation=b,raw="resurrect"),null)
        assertNull(store.read(id))
    }
    @Test fun skippedQuestionsDoNotReintroduceMergedTagsOrCategories() = runBlocking {
        val dao=db.questionDao();val old=id();val fresh=id(20000)
        dao.insert(Question(old,"local","latest",10000))
        val backup=BackupFile(categories=listOf(BackupCategory(1,"old unused"),BackupCategory(2,"needed")),
            tags=listOf(BackupTag(1,"merged away"),BackupTag(2,"needed")),
            questions=listOf(BackupQuestion(old,"old","old",10000,categoryId=1),BackupQuestion(fresh,"new","new",20000,categoryId=2)),
            questionTags=listOf(BackupQuestionTag(old,1),BackupQuestionTag(fresh,2)))
        db.withTransaction { BackupImporter.merge(dao,backup) }
        assertEquals(listOf("needed"),dao.allCategoriesOnce().map { it.name })
        assertEquals(listOf("needed"),dao.getAllTagsOnce().map { it.name })
        assertEquals("latest",dao.heavyOnce(old)!!.answer)
        assertEquals(1,dao.tagIdsForQuestion(fresh).size)
    }
    @Test fun reviewInsertAndUpdateRollbackTogetherOnFailure() = runBlocking {
        val dao=db.questionDao();val id=id();dao.insert(Question(id,"fixture","a",10000))
        try { db.withTransaction {
            dao.insertReview(Review(id));dao.updateReview(id,2,30000,20000);error("synthetic failure")
        } } catch (_:IllegalStateException) { }
        assertNull(dao.reviewFor(id))
        db.withTransaction { dao.insertReview(Review(id));dao.updateReview(id,2,30000,20000) }
        assertEquals(1,dao.reviewFor(id)!!.reviewCount)
    }
}
