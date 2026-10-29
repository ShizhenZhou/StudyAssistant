package com.zsz.studyassistant.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.MainViewModel
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Regression cases use synthetic data in the independent integration package only. */
@RunWith(AndroidJUnit4::class)
class ReliabilityRegressionTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var store: ConversationDraftStore
    private var vm: MainViewModel?=null
    private val ids=mutableListOf<String>()
    private fun id(time: Long=10000)=QuestionIdentity.create(time).also { ids+=it }
    private fun draft(id: String, gen: String=UUID.randomUUID().toString())=ConversationDraft(id,10000,gen,
        "fixture","initial","ask","synthetic question",false,true,false,true,null,emptyList(),
        listOf(ChatItem(0,"question","synthetic question")),emptyList(),raw="synthetic partial")
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration")
        app=context.applicationContext as Application
        com.zsz.studyassistant.ui.UiLangStore.save(app,com.zsz.studyassistant.ui.UiLang.EN)
        db=AppDatabase.get(app);db.clearAllTables();store=ConversationDraftStore(app)
    }
    private fun model(): MainViewModel {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm=MainViewModel(app) }
        return checkNotNull(vm)
    }
    private suspend fun awaitCondition(test: suspend ()->Boolean)=withTimeout(8000) {
        while(!test()) delay(40)
    }
    @After fun cleanup()=runBlocking {
        withContext(Dispatchers.Main) { vm?.viewModelScope?.cancel() }
        delay(80)
        store.invalidate(ids)
        db.clearAllTables()
    }
    @Test fun newDraftMustSurviveAnOlderAsynchronousDiscard()=runBlocking {
        val id=id();val old=draft(id);val fresh=old.copy(generation=UUID.randomUUID().toString(),raw="new request")
        val model=model()
        val storeField=MainViewModel::class.java.getDeclaredField("conversationDrafts").apply { isAccessible=true }
        val activeField=MainViewModel::class.java.getDeclaredField("activeDraft").apply { isAccessible=true }
        val ownedStore=storeField.get(model) as ConversationDraftStore
        val lock=ConversationDraftStore::class.java.getDeclaredField("lock").apply { isAccessible=true }.get(ownedStore)
        ownedStore.reserve(id,old.generation);ownedStore.write(old,null)
        var cleanupJob: Job?=null
        withContext(Dispatchers.Main) {
            // Hold only the test-owned store lock to deterministically delay the IO cleanup.
            synchronized(lock) {
                activeField.set(model,old)
                val before=model.viewModelScope.coroutineContext[Job]!!.children.toSet()
                model.discardInterrupted()
                cleanupJob=model.viewModelScope.coroutineContext[Job]!!.children.first { it !in before }
                ownedStore.reserve(id,fresh.generation);ownedStore.write(fresh,null)
                activeField.set(model,fresh)
            }
        }
        withTimeout(5000) { checkNotNull(cleanupJob).join() }
        assertNotNull("An older ViewModel discard removed the newer generation",ownedStore.read(id))
    }
    @Test fun oldCompletionMustNotForgetNewActiveDraft() { verifyCompletionRace(false) }
    @Test fun canceledOldCompletionMustNotForgetNewActiveDraft() { verifyCompletionRace(true) }
    private fun verifyCompletionRace(cancelCleanup: Boolean)=runBlocking {
        val id=id();val old=draft(id);val fresh=old.copy(generation=UUID.randomUUID().toString(),raw="new request")
        val model=model()
        val storeField=MainViewModel::class.java.getDeclaredField("conversationDrafts").apply { isAccessible=true }
        val activeField=MainViewModel::class.java.getDeclaredField("activeDraft").apply { isAccessible=true }
        val ownedStore=storeField.get(model) as ConversationDraftStore
        val lock=ConversationDraftStore::class.java.getDeclaredField("lock").apply { isAccessible=true }.get(ownedStore)
        ownedStore.reserve(id,old.generation);ownedStore.write(old,null)
        var cleanupJob:Job?=null
        withContext(Dispatchers.Main) {
            synchronized(lock) {
                activeField.set(model,old)
                cleanupJob=model.viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) { model.finishDraftCleanup(old) }
                ownedStore.reserve(id,fresh.generation);ownedStore.write(fresh,null)
                activeField.set(model,fresh)
                if(cancelCleanup) cleanupJob!!.cancel()
            }
        }
        withTimeout(5000) { cleanupJob!!.join() }
        assertEquals("Older completion forgot the new active draft",fresh.generation,(activeField.get(model) as ConversationDraft?)?.generation)
        assertEquals(fresh.generation,ownedStore.read(id)!!.generation)
    }
    @Test fun clearAllMustNotLeaveRecoverableConversationContent()=runBlocking {
        val id=id();val value=draft(id)
        store.reserve(id,value.generation);store.write(value,null)
        db.questionDao().insert(Question(id,"synthetic question","synthetic answer",10000))
        val model=model()
        withContext(Dispatchers.Main) { model.clearAllData() }
        awaitCondition { model.menuMessage!=null && !model.dataBusy }
        assertEquals(0,db.questionDao().countQuestions())
        assertTrue("Clear-all succeeded while conversation draft content remained",store.summaries().first.isEmpty())
    }
    @Test fun completedOrphanDraftMustRemainUntilItHasAPersistentDestination()=runBlocking {
        val id=id();val value=draft(id).copy(complete=true,completedQuestion="synthetic question",
            completedChats=listOf(ChatItem(0,"question","synthetic question"),ChatItem(1,"assistant","recovered answer")))
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError)
        assertFalse(db.questionDao().questionExists(id));assertNull(db.questionDao().solveSessionOnce(id))
        assertEquals("recovered answer",model.chatItems.last().content)
        assertNotNull("Completed draft was cleared although neither notebook nor history received it",store.read(id))
    }
    @Test fun completedDraftWithExistingHistoryIsSavedBeforeRemoval()=runBlocking {
        val id=id();val value=draft(id).copy(complete=true,completedQuestion="synthetic question",
            completedChats=listOf(ChatItem(0,"question","synthetic question"),ChatItem(1,"assistant","durable answer")))
        val dao=db.questionDao()
        dao.insertSolveSession(SolveSession(id,10000,10000,"ask","synthetic question","old"))
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError)
        assertEquals("durable answer",dao.solveSessionOnce(id)!!.answer)
        assertNull(store.read(id))
    }
    private fun completedGrade(id: String): ConversationDraft = draft(id).copy(
        createdAt=QuestionIdentity.time(id), source="grade", action="grade", grade=true, ask=false,
        historyAllowed=false, complete=true, completedQuestion="synthetic graded question",
        completedChats=listOf(ChatItem(0,"question","synthetic graded question"),ChatItem(1,"assistant","synthetic grade result")))
    @Test fun completedLegacyGradeWithoutNotebookMovesToHistoryAndClearsDraft()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id)
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError)
        assertEquals("synthetic grade result",db.questionDao().solveSessionOnce(id)?.answer)
        assertEquals("grade",db.questionDao().solveSessionOnce(id)?.source)
        assertFalse(db.questionDao().questionExists(id));assertNull(store.read(id))
        withContext(Dispatchers.Main) { assertTrue(model.loadSolveSession(db.questionDao().solveSessionOnce(id)!!)) }
        assertTrue("History must reopen as grading",model.gradeMode)
    }
    @Test fun completedLegacyGradeSavedToNotebookAlsoMovesToHistoryAndClearsDraft()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id)
        db.questionDao().insert(Question(id,value.completedQuestion!!,"synthetic grade result",value.createdAt,
            conversationJson=kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatItem.serializer()),value.completedChats!!)))
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError)
        assertEquals(id,db.questionDao().solveSessionOnce(id)?.savedQuestionId)
        assertEquals("synthetic grade result",db.questionDao().heavyOnce(id)?.answer)
        assertNull(store.read(id))
        withContext(Dispatchers.Main) { assertTrue(model.loadSolveSession(db.questionDao().solveSessionOnce(id)!!)) }
        assertTrue("Linked history must reopen as grading",model.gradeMode)
    }
    @Test fun newGradeCreatesHistoryAtInitialInputEvenWithoutNotebook()=runBlocking {
        val model=model()
        withContext(Dispatchers.Main) { model.startGrade(byteArrayOf(1),byteArrayOf(2)) }
        awaitCondition { db.questionDao().allSolveSessionsOnce().isNotEmpty() }
        val rows=db.questionDao().allSolveSessionsOnce()
        assertEquals(1,rows.size); ids+=rows.single().id
        assertEquals("grade",rows.single().source)
        assertFalse(db.questionDao().questionExists(rows.single().id))
    }
    @Test fun failedGradeHistoryKeepsTheDraftUntilRetrySucceeds()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id)
        store.reserve(id,value.generation);store.write(value,null)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_grade_insert BEFORE INSERT ON solve_sessions BEGIN SELECT RAISE(ABORT, 'synthetic IO failure'); END")
        try {
            val model=model()
            withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
            awaitCondition { model.draftSolveRequested || model.draftError!=null }
            assertNotNull(store.read(id));assertNull(db.questionDao().solveSessionOnce(id))
            assertTrue(model.failedHistoryWrites>0)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_grade_insert")
            withContext(Dispatchers.Main) { model.retryFailedHistoryWrites() }
            awaitCondition { !model.retryingHistoryWrites }
            assertEquals("synthetic grade result",db.questionDao().solveSessionOnce(id)?.answer)
            assertNull(store.read(id))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fixture_grade_insert") }
    }
    @Test fun managedGradeDoesNotRecreateAnExplicitlyDeletedHistory()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id).copy(historyAllowed=true,gradeHistoryEnabled=true)
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError);assertNull(db.questionDao().solveSessionOnce(id));assertNotNull(store.read(id))
    }
    @Test fun interruptedLegacyGradeRemainsRecoverableAfterHistoryMigration()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id).copy(complete=false,completedChats=null)
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError);assertTrue(model.streamInterrupted)
        assertNotNull(db.questionDao().solveSessionOnce(id));assertNotNull(store.read(id));assertFalse(store.read(id)!!.complete)
    }
    @Test fun restoredTextGradingHistoryKeepsItsQuestionForRegrade()=runBlocking {
        val id=id(System.currentTimeMillis())
        val value=completedGrade(id).copy(completedQuestion="Actual text question",images=listOf("AQ=="),
            completedChats=listOf(ChatItem(0,"question","Actual text question\nMy answer"),ChatItem(1,"assistant","synthetic grade result")))
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNull(model.draftError)
        withContext(Dispatchers.Main) { assertTrue(model.loadSolveSession(db.questionDao().solveSessionOnce(id)!!)) }
        assertTrue(model.gradeMode)
        val text=MainViewModel::class.java.getDeclaredField("gradeTextQuestion").apply { isAccessible=true }.get(model)
        assertEquals("Actual text question",text)
    }
    @Test fun gradeRecoveryMustNotOverwriteANewerNotebookAnswer()=runBlocking {
        val id=id(System.currentTimeMillis());val value=completedGrade(id)
        db.questionDao().insert(Question(id,"newer question","newer answer",value.createdAt,conversationJson="[]"))
        store.reserve(id,value.generation);store.write(value,null)
        val model=model()
        withContext(Dispatchers.Main) { model.resumeDraft(id,"home") }
        awaitCondition { model.draftSolveRequested || model.draftError!=null }
        assertNotNull(model.draftError)
        assertEquals("newer answer",db.questionDao().heavyOnce(id)?.answer)
        assertEquals("newer question",db.questionDao().heavyOnce(id)?.text)
        assertNotNull(store.read(id));assertNull(db.questionDao().solveSessionOnce(id))
    }
    @Test fun explicitEraseRejectsAlreadyQueuedDraftWrites() {
        val id=id();val value=draft(id)
        store.reserve(id,value.generation);store.write(value,null)
        store.clearAll();store.write(value.copy(raw="late"),null)
        assertNull(store.read(id))
        val fresh=value.copy(generation=UUID.randomUUID().toString())
        store.reserve(id,fresh.generation);store.write(fresh,null)
        store.clear(id,value.generation)
        assertEquals(fresh.generation,store.read(id)!!.generation)
    }
    @Test fun notebookListMustRemainReadableWithALongImportedAnswer()=runBlocking {
        val isolated=Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).build()
        try {
            isolated.questionDao().insert(Question(id(),"synthetic question","x".repeat(3*1024*1024),10000))
            assertEquals(1,isolated.questionDao().getAll().first().size)
        } finally { isolated.close() }
    }
    @Test fun retryAfterNextQuestionReadFailureMustNotRecordTheSameReviewTwice()=runBlocking {
        val first=Question(id(),"first","answer",10000)
        val second=Question(id(20000),"next","answer",20000)
        val dao=db.questionDao();dao.insert(first);dao.insert(second);dao.insertReview(Review(first.id))
        val model=model()
        var failNext=true
        // Deliberately inject a storage failure, independent of the large-field regression.
        val failingDao=object : QuestionDao by dao {
            override suspend fun heavyOnce(id: QuestionId): Question? {
                if(id==second.id && failNext) throw java.io.IOException("synthetic next-row failure")
                return dao.heavyOnce(id)
            }
        }
        MainViewModel::class.java.getDeclaredField("dao").apply { isAccessible=true }.set(model,failingDao)
        withContext(Dispatchers.Main) { model.startReviewSession(listOf(first,second),0);model.loadQuestion(first,keepReview=true) }
        withContext(Dispatchers.Main) { model.reviewNext(2) { } }
        awaitCondition { !model.reviewBusy && dao.reviewFor(first.id)?.reviewCount==1 }
        assertEquals(first.id,model.savedQuestionId)
        assertNotNull("Expected next-row loading error",model.error)
        withContext(Dispatchers.Main) { model.reviewNext(2) { } }
        awaitCondition { !model.reviewBusy }
        assertEquals("Retrying a next-question load failure recorded the previous review twice",1,dao.reviewFor(first.id)!!.reviewCount)
        assertEquals(1,model.reviewDone)
        failNext=false
        withContext(Dispatchers.Main) { model.reviewNext(2) { } }
        awaitCondition { !model.reviewBusy && model.savedQuestionId==second.id }
        assertEquals(1,dao.reviewFor(first.id)!!.reviewCount)
        assertEquals(1,model.reviewDone)
        assertNull(model.error)
    }
}
