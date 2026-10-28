package com.zsz.studyassistant.data

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic fixtures only. Gates suspend the real ViewModel tasks at their ownership boundaries. */
@RunWith(AndroidJUnit4::class)
class SessionOwnershipRegressionTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var model: MainViewModel
    private lateinit var pending: PendingSaveStore
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun field(name: String) = MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String, value: T) {
        (field(name + "\$delegate").get(model) as MutableState<T>).value = value
    }
    private fun similarIdentity(id: QuestionId) {
        field("similarIdentity").set(model, id)
        field("similarCreatedAt").setLong(model, QuestionIdentity.time(id))
        assertEquals("Fixture identity and creation time must agree", QuestionIdentity.time(id), field("similarCreatedAt").getLong(model))
    }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(test: suspend () -> Boolean) = withTimeout(8000) { while (!test()) delay(20) }
    private fun question(text: String, time: Long) = Question(QuestionIdentity.create(time), text, "answer $text", time)

    private suspend fun initialSimilarSavedWhileStreaming(outcome: String) {
        val entered = gate(); val release = gate()
        main {
            field("currentReviewQuestion").set(model, question("original", 10000))
            model.similarQuestionRequest = { _, _, _, delta ->
                delta("题目：new fixture\n解答：partial answer")
                entered.complete(Unit)
                release.await()
                if (outcome == "failure") throw StreamIncompleteException("err.streamInterrupted")
                StudyAssistant.SimilarResult("new fixture", "complete answer")
            }
            model.startSimilar()
        }
        withTimeout(8000) { entered.await() }
        val lock = field("notebookWriteMutex").get(model) as Mutex
        lock.lock()
        val id = field("similarIdentity").get(model) as QuestionId
        try {
            main { model.saveSimilarToNotebook(null, null, listOf("retained tag")) }
            if (outcome == "abort") main { model.abortSimilar() } else {
                release.complete(Unit)
                await { !model.similarBusy }
            }
            if (outcome != "success") assertTrue(model.similarInterrupted)
        } finally { lock.unlock() }
        val expected = if (outcome == "success") "complete answer" else "partial answer"
        await { db.questionDao().heavyOnce(id)?.answer?.contains(expected) == true }
        val saved = db.questionDao().heavyOnce(id)!!
        assertEquals("new fixture", saved.text)
        assertTrue(Json.decodeFromString<List<ChatItem>>(saved.conversationJson!!)[1].content.contains(expected))
        assertEquals("retained tag", db.questionDao().getAllTagsOnce().single().name)
        assertFalse(model.similarBusy)
    }

    @Test fun initialSimilarCompletionUpdatesAnInsertStillWaitingForTheWriteLock() = runBlocking {
        initialSimilarSavedWhileStreaming("success")
    }
    @Test fun abortAfterInitialQuestionKeepsPartialAnswerAndPersistsIt() = runBlocking {
        initialSimilarSavedWhileStreaming("abort")
    }
    @Test fun initialSimilarFailurePersistsPartialAnswerAndInterruption() = runBlocking {
        initialSimilarSavedWhileStreaming("failure")
    }
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        app = context.applicationContext as Application
        db = AppDatabase.get(app); db.clearAllTables()
        pending = PendingSaveStore(app); pending.discardAll()
        main { model = MainViewModel(app) }
    }
    @After fun cleanup() = runBlocking {
        gates.forEach { it.complete(Unit) }
        main { model.viewModelScope.cancel() }
        // Join cancellation so no delayed fixture writes reach the following test.
        withTimeout(8000) { model.viewModelScope.coroutineContext[Job]!!.join() }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fixture_fail_answer")
        pending.discardAll(); db.clearAllTables()
    }
    @Test fun pendingCandidateBlocksAutomaticAdvanceAndRetryDoesNotRepeatReview() = runBlocking {
        val first = question("first", 10000); val next = question("next", 20000)
        val dao = db.questionDao(); dao.insert(first); dao.insert(next)
        val value = SavePayload.notebook(next).copy(answer = "unsaved replacement")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_answer BEFORE UPDATE OF answer ON questions BEGIN SELECT RAISE(ABORT, 'synthetic IO failure'); END")
        val failed = runCatching { PendingSaveRepository(db, pending).saveNotebook(value) }
        assertTrue("The injected write failure must leave a recovery journal", failed.isFailure)
        assertEquals(1, pending.scan().entries.size)
        var reads = 0
        val tracked = object : QuestionDao by dao {
            override suspend fun heavyOnce(id: QuestionId): Question? {
                if (id == next.id) reads++
                return dao.heavyOnce(id)
            }
        }
        field("dao").set(model, tracked)
        main { model.startReviewSession(listOf(first, next), 0); model.loadQuestion(first, keepReview = true); model.reviewNext(2) {} }
        await { !model.reviewBusy && dao.reviewFor(first.id)?.reviewCount == 1 }
        assertEquals(first.id, model.savedQuestionId); assertNotNull(model.error)
        assertEquals("Pending content must block even reading the stale candidate", 0, reads)
        assertEquals("unsaved replacement", pending.scan().entries.single().payload.answer)
        main { model.reviewNext(2) {} }
        await { !model.reviewBusy }
        assertEquals(1, dao.reviewFor(first.id)!!.reviewCount)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_fail_answer")
        assertTrue(PendingSaveRepository(db, pending).retryAll(0).entries.isEmpty())
        main { model.reviewNext(2) {} }
        await { !model.reviewBusy && model.savedQuestionId == next.id }
        assertEquals(1, dao.reviewFor(first.id)!!.reviewCount)
        assertEquals("unsaved replacement", model.chatItems.last().content)
        assertEquals(1, model.reviewDone)
    }
    @Test fun suspendedCandidateReadCannotReplaceANewerSession() = runBlocking {
        val first = question("first", 10000); val next = question("next", 20000); val other = question("other", 30000)
        val dao = db.questionDao(); listOf(first, next, other).forEach { dao.insert(it) }
        val entered = gate(); val release = gate()
        val delayed = object : QuestionDao by dao {
            override suspend fun heavyOnce(id: QuestionId): Question? {
                if (id == next.id) { entered.complete(Unit); release.await() }
                return dao.heavyOnce(id)
            }
        }
        field("dao").set(model, delayed)
        var reviewJob: Job? = null
        main {
            model.startReviewSession(listOf(first, next), 0); model.loadQuestion(first, keepReview = true)
            val before = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
            model.reviewNext(2) {}
            reviewJob = model.viewModelScope.coroutineContext[Job]!!.children.first { it !in before }
        }
        withTimeout(8000) { entered.await() }
        main { model.loadQuestion(other); model.exitReviewMode() }
        release.complete(Unit)
        withTimeout(8000) { reviewJob!!.join() }
        assertEquals(other.id, model.savedQuestionId)
    }
    @Test fun similarSaveFreezesConversationAndCallerOwnedTagsBeforeMutexWait() = runBlocking {
        val id = QuestionIdentity.create(10000)
        val tags = mutableListOf("original tag")
        val lock = field("notebookWriteMutex").get(model) as Mutex
        lock.lock()
        try {
            main {
                similarIdentity(id)
                state("similarQuestion", "question A"); state("similarAnswer", "answer A")
                state("similarMessages", listOf(ChatItem(0, "user", "follow A", listOf("AAE="))))
                model.saveSimilarToNotebook(null, null, tags)
                tags[0] = "changed tag"
                field("similarSeq").setLong(model, field("similarSeq").getLong(model) + 1)
                similarIdentity(QuestionIdentity.create(20000))
                state("similarQuestion", "question B"); state("similarAnswer", "answer B"); state("similarMessages", emptyList<ChatItem>())
            }
        } finally { lock.unlock() }
        await { db.questionDao().questionExists(id) }
        val row = db.questionDao().heavyOnce(id)!!
        assertEquals("question A", row.text); assertTrue(row.answer.contains("follow A"))
        val chats = Json.decodeFromString<List<ChatItem>>(row.conversationJson!!)
        assertEquals(listOf("question A", "answer A", "follow A"), chats.map { it.content })
        assertEquals(listOf("AAE="), chats.last().images)
        assertEquals("original tag", db.questionDao().getAllTagsOnce().single().name)
        assertNull(model.similarSavedQuestionId)
    }
    @Test fun lateClassificationAndItsFinallyCannotWriteIntoNewRequest() = runBlocking {
        val releaseOld = gate(); val oldEntered = gate()
        val releaseNew = gate(); val newEntered = gate()
        var oldJob: Job? = null
        var newJob: Job? = null
        main {
            model.classifyRequest = { q, _, _, _, _, _ ->
                if (q == "first") {
                    oldEntered.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                    "old category" to listOf("old tag")
                } else { newEntered.complete(Unit); releaseNew.await(); "new category" to listOf("new tag") }
            }
            // Classification normally starts in an unsaved session. Loading a notebook fixture
            // also starts unrelated DAO metadata reads that can overwrite these suggestions.
            model.startNewQuestion(); field("questionText").set(model, "first"); model.classifyCurrentQuestion()
            oldJob = field("classificationJob").get(model) as Job
        }
        withTimeout(8000) { oldEntered.await() }
        main {
            model.startNewQuestion(); field("questionText").set(model, "second"); model.classifyCurrentQuestion()
            newJob = field("classificationJob").get(model) as Job
        }
        withTimeout(8000) { newEntered.await() }
        assertTrue(oldJob!!.isCancelled)
        releaseOld.complete(Unit); withTimeout(8000) { oldJob!!.join() }
        main {
            assertTrue("Old finally cleared the current request's busy flag", field("classifying").getBoolean(model))
            assertNull(model.suggestedCategory); assertTrue(model.suggestedTags.isEmpty())
        }
        releaseNew.complete(Unit)
        withTimeout(8000) { newJob!!.join() }
        main {
            assertFalse(field("classifying").getBoolean(model))
            assertEquals("new category", model.suggestedCategory); assertEquals(listOf("new tag"), model.suggestedTags)
        }
    }
    @Test fun similarFollowupSendsAnswerAndAllImagesExactlyOnceFromFrozenSnapshot() = runBlocking {
        val captured = CompletableDeferred<Pair<String, List<DeepSeekMessage>>>()
        val release = gate()
        main {
            model.similarChatRequest = { selected, messages, _, _ ->
                captured.complete(selected to messages); release.await(); "synthetic reply"
            }
            state("similarQuestion", "fixture question"); state("similarAnswer", "standard answer")
            state("similarMessages", listOf(ChatItem(0, "user", "earlier image", listOf("AAE=")), ChatItem(1, "assistant", "earlier reply")))
            val bytes = byteArrayOf(2, 3)
            model.sendSimilar("current followup", listOf(bytes))
            bytes[0] = 99
            state("similarAnswer", "changed answer"); state("similarMessages", emptyList<ChatItem>())
        }
        val (selected, messages) = withTimeout(8000) { captured.await() }
        assertEquals(StudyAssistant.MODEL_VISION, selected)
        val wire = messages.joinToString("\n") { it.content.toString() }
        assertEquals(1, Regex("current followup").findAll(wire).count())
        assertTrue(wire.contains("standard answer")); assertFalse(wire.contains("changed answer"))
        assertTrue(wire.contains("AAE=")); assertTrue(wire.contains("AgM="))
        assertTrue(wire.indexOf("standard answer") < wire.indexOf("earlier image"))
        release.complete(Unit)
        await { !model.similarBusy }
    }
    @Test fun historicalImageAloneSelectsVisionForATextFollowup() = runBlocking {
        val captured = CompletableDeferred<Pair<String, List<DeepSeekMessage>>>()
        main {
            model.similarChatRequest = { selected, messages, _, _ ->
                captured.complete(selected to messages); "synthetic reply"
            }
            state("similarQuestion", "fixture question"); state("similarAnswer", "standard answer")
            state("similarMessages", listOf(ChatItem(0, "user", "earlier image", listOf("AAE="))))
            model.sendSimilar("text only followup")
        }
        val (selected, messages) = withTimeout(8000) { captured.await() }
        assertEquals(StudyAssistant.MODEL_VISION, selected)
        assertTrue(messages.joinToString { it.content.toString() }.contains("AAE="))
        await { !model.similarBusy }
    }

    @Test fun deletingSavedSimilarFollowupPersistsBeforeAnotherQuestionStarts() = runBlocking {
        val id = QuestionIdentity.create(10000)
        main {
            similarIdentity(id)
            state("similarQuestion", "question A"); state("similarAnswer", "answer A")
            state("similarMessages", listOf(ChatItem(0, "user", "remove this followup"), ChatItem(1, "assistant", "keep reply")))
            model.saveSimilarToNotebook("keep category", null, listOf("keep tag"))
        }
        await { model.similarSavedQuestionId == id }
        main {
            model.deleteSimilarItems(listOf(1))
            // A queued save must already own A; the next screen may immediately display B.
            similarIdentity(QuestionIdentity.create(20000))
            state("similarQuestion", "question B"); state("similarAnswer", "answer B")
        }
        await { !db.questionDao().heavyOnce(id)!!.conversationJson!!.contains("remove this followup") }
        val saved = db.questionDao().heavyOnce(id)!!
        assertEquals("question A", saved.text)
        assertTrue(saved.conversationJson!!.contains("keep reply"))
        assertEquals(listOf("keep tag"), db.questionDao().getAllTagsOnce().map { it.name })
    }

    @Test fun followupDuringFirstSaveAndSameIdentityResaveRetainNewestConversation() = runBlocking {
        val id = QuestionIdentity.create(10000)
        val replyRequested = gate()
        val lock = field("notebookWriteMutex").get(model) as Mutex
        val existingJobs = withContext(Dispatchers.Main) {
            model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        }
        var writeJobs = emptyList<Job>()
        lock.lock()
        try {
            main {
                similarIdentity(id)
                state("similarQuestion", "question A"); state("similarAnswer", "answer A")
                model.similarChatRequest = { _, _, _, _ -> replyRequested.complete(Unit); "reply during save" }
                model.saveSimilarToNotebook("original category", null, listOf("original tag"))
                model.sendSimilar("followup during save")
            }
            withTimeout(8000) { replyRequested.await() }
            await {
                withContext(Dispatchers.Main) {
                    (field("pendingNotebookContent").get(model) as Map<*, *>).containsKey(id)
                }
            }
            writeJobs = withContext(Dispatchers.Main) {
                model.viewModelScope.coroutineContext[Job]!!.children.filter { it !in existingJobs }.toList()
            }
        } finally { lock.unlock() }
        // Busy is a request/UI flag, not a durable-save acknowledgment. Join both the
        // first insert and the separately launched pending-content writer before inspecting storage.
        withTimeout(8000) { writeJobs.joinAll() }
        val pendingIds = withContext(Dispatchers.Main) {
            (field("pendingNotebookContent").get(model) as Map<*, *>).keys.toList()
        }
        assertFalse("Content write did not finish: pending=$pendingIds journal=${pending.scan()}", id in pendingIds)
        assertFalse(model.similarBusy)
        assertEquals(id, model.similarSavedQuestionId)
        val dao = db.questionDao()
        val first = dao.heavyOnce(id)!!
        assertTrue("Persisted conversation: ${first.conversationJson}", first.conversationJson!!.contains("reply during save"))
        val category = first.categoryId
        main {
            state("similarMessages", model.similarMessages + ChatItem(9, "user", "newest snapshot"))
            model.saveSimilarToNotebook("must not replace category", null, listOf("must not replace tag"))
        }
        await { dao.heavyOnce(id)!!.conversationJson!!.contains("newest snapshot") }
        val latest = dao.heavyOnce(id)!!
        assertEquals(category, latest.categoryId)
        assertEquals(listOf("original tag"), dao.getAllTagsOnce().map { it.name })
        assertEquals(0, dao.reviewFor(id)!!.reviewCount)
    }

    @Test fun recreatedSimilarAcceptsFollowupWhileInsertWaitsAndLaterEdits() = runBlocking {
        val id = QuestionIdentity.create(10000)
        main {
            similarIdentity(id)
            state("similarQuestion", "recreated question"); state("similarAnswer", "original answer")
            model.saveSimilarToNotebook(null, null)
        }
        await { model.similarSavedQuestionId == id }
        main { model.unsaveSimilarFromNotebook() }
        await { !model.deletionState.value.active && db.questionDao().heavyOnce(id) == null }
        val lock = field("notebookWriteMutex").get(model) as Mutex
        lock.lock()
        try {
            main {
                model.similarChatRequest = { _, _, _, _ -> "reply while recreating" }
                model.saveSimilarToNotebook("restored category", null, listOf("restored tag"))
                model.sendSimilar("new followup")
            }
            await { !model.similarBusy }
            assertNull(db.questionDao().heavyOnce(id))
        } finally { lock.unlock() }
        await { db.questionDao().heavyOnce(id)?.conversationJson?.contains("reply while recreating") == true }
        main { model.deleteSimilarItems(listOf(1)) }
        await { db.questionDao().heavyOnce(id)?.conversationJson?.contains("new followup") == false }
        await { model.saveOperations == 0 }
        val saved = db.questionDao().heavyOnce(id)!!
        assertEquals("recreated question", saved.text)
        assertTrue(saved.conversationJson!!.contains("reply while recreating"))
        assertEquals("restored category", db.questionDao().allCategoriesOnce().single().name)
        assertEquals("restored tag", db.questionDao().getAllTagsOnce().single().name)
        assertEquals(0, db.questionDao().reviewFor(id)!!.reviewCount)
    }

    @Test fun deletingWhileSimilarSaveWaitsInvalidatesThatSaveAndItsLatestReply() = runBlocking {
        val id = QuestionIdentity.create(10000)
        val row = question("existing", 10000).copy(id = id)
        db.questionDao().insert(row)
        val lock = field("notebookWriteMutex").get(model) as Mutex
        lock.lock()
        try {
            main {
                similarIdentity(id)
                state("similarQuestion", "stale resave"); state("similarAnswer", "stale answer")
                model.similarChatRequest = { _, _, _, _ -> "must not resurrect" }
                model.saveSimilarToNotebook("must not create category", null)
                model.sendSimilar("queued followup")
            }
            await { !model.similarBusy }
            main { model.deleteFromNotebook(row) }
            await { model.deletionState.value.committing.isNotEmpty() }
        } finally { lock.unlock() }
        await { !model.deletionState.value.active }
        assertNull(db.questionDao().heavyOnce(id))
        assertNull(model.similarSavedQuestionId)
        assertTrue(db.questionDao().allCategoriesOnce().isEmpty())
        assertTrue(pending.scan().entries.isEmpty())
    }

    @Test fun importingDeletedIdentityAllowsNewContentWithoutReplayingPreDeleteSnapshot() = runBlocking {
        val id = QuestionIdentity.create(10000)
        val chats = listOf(ChatItem(0, "question", "backup question"), ChatItem(1, "assistant", "backup answer"), ChatItem(2, "user", "remove after restore"))
        val row = Question(id, "backup question", "backup answer", 10000, conversationJson = Json.encodeToString(chats))
        db.questionDao().insert(row)
        val workspace = java.io.File(app.noBackupFilesDir, "backup-manual/${java.util.UUID.randomUUID()}")
        try {
            val repo = BackupRepository(app, db)
            val archive = repo.export(java.io.File(workspace, "export"))
            val prepared = repo.prepareImportFiles(archive.files, java.io.File(workspace, "import"))
            main { model.loadQuestion(row) }
            val lock = field("notebookWriteMutex").get(model) as Mutex
            lock.lock()
            try {
                main {
                    state("chatItems", chats + ChatItem(3, "assistant", "old queued content"))
                    model.saveSessionOnExit()
                    model.deleteFromNotebook(row)
                }
                await { model.deletionState.value.committing.isNotEmpty() }
            } finally { lock.unlock() }
            await { !model.deletionState.value.active && db.questionDao().heavyOnce(id) == null }
            main { state("preparedBackup", prepared); model.importBackup() }
            await { !model.dataBusy }
            val restored = db.questionDao().heavyOnce(id)!!
            assertFalse(restored.conversationJson!!.contains("old queued content"))
            main { model.loadQuestion(restored); model.deleteMessages(listOf(2)); model.saveSessionOnExit() }
            await { db.questionDao().heavyOnce(id)?.conversationJson?.contains("remove after restore") == false }
            await { model.saveOperations == 0 }
            assertEquals("backup answer", db.questionDao().heavyOnce(id)!!.answer)
            assertTrue(pending.scan().entries.isEmpty())
        } finally { workspace.deleteRecursively() }
    }

}
