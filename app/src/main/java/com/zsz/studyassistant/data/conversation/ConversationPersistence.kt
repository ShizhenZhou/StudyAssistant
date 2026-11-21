package com.zsz.studyassistant.data

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One owner for frozen conversation writes and recovery. All callers share [writeMutex].
 * Queue admission runs on Main before any suspension; retries never rebuild from current UI state.
 */
internal class ConversationPersistence(
    context: Context,
    private val scope: CoroutineScope,
    val writeMutex: Mutex,
    private val historyCutoff: () -> Long,
    private val isNotebookBlocked: (QuestionId) -> Boolean,
    private val isNotebookDeleted: (QuestionId) -> Boolean,
    private val onNotebookSaved: (SavePayload) -> Unit
) {
    private val dao = AppDatabase.get(context).questionDao()
    private val json = Json { ignoreUnknownKeys = true }
    val pendingSaveStore = PendingSaveStore(context)
    private val pendingSaveRepository = PendingSaveRepository(AppDatabase.get(context), pendingSaveStore)
    val pendingNotebookContent = linkedMapOf<QuestionId, SaveSnapshot<SavePayload>>()
    val historyWriteQueue = HistoryWriteQueue()
    private val solveSessionWriteMutex get() = writeMutex
    var failedHistoryWrites by mutableStateOf(0)
    var retryingHistoryWrites by mutableStateOf(false)
        private set
    var hasSaveActivity by mutableStateOf(false)
    var saveRecoveryReady by mutableStateOf(false)
        private set
    var pendingSaveCount by mutableStateOf(0)
        private set
    var saveJournalIssue by mutableStateOf(false)
    var saveOperations by mutableStateOf(0)
        private set
    var saveRevision by mutableStateOf(0L)
        private set
    private var statusDirty = false

    suspend fun recover() = writeMutex.withLock {
        try {
            val scan = withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            hasSaveActivity = scan.entries.isNotEmpty() || scan.unreadable > 0
            pendingSaveRepository.retryAll(historyCutoff())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { saveJournalIssue = true }
        refresh()
    }

    suspend fun awaitWrites() { writeMutex.withLock { } }

    /** A completion may suspend while its draft is written. Its save must still use the
     * original conversation, even if the visible question changes during that write. */
    class FrozenSave(
        val history: SaveSnapshot<SolveSession>?,
        val notebook: SaveSnapshot<SavePayload>?
    )

    fun enqueue(save: FrozenSave) {
        save.history?.let { queueHistory(it, create = false) }
        save.notebook?.let(::queueNotebookSave)
    }

    suspend fun cleanupCompletedDrafts(
        drafts: ConversationDraftPersistence,
        onCleared: (ConversationDraft) -> Unit
    ) {
        awaitWrites()
        for (summary in drafts.summaries().first) {
            val value = drafts.read(summary.id) ?: continue
            if (value.complete && draftCanBeCleared(value)) {
                drafts.clear(value.id, value.generation)
                onCleared(value)
            }
        }
    }

    fun queueHistory(snapshot: SaveSnapshot<SolveSession>, create: Boolean) {
        hasSaveActivity = true
        val ticket = historyWriteQueue.enqueue(snapshot, create)
        failedHistoryWrites = historyWriteQueue.failedTickets().size
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { persistHistoryTicket(ticket) }
        }
    }
    suspend fun refresh() {
        try {
            val scan = withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            val durable = scan.entries.map { "${it.target}:${it.payload.id}" }.toSet()
            val keys = durable.toMutableSet()
            historyWriteQueue.failedTickets().forEach { keys += "HISTORY:${it.id}" }
            pendingNotebookContent.keys.forEach { keys += "NOTEBOOK:$it" }
            pendingSaveCount = keys.size
            val copies = scan.entries.associateBy { "${it.target}:${it.payload.id}" }
            val memory = historyWriteQueue.failedTickets().associate { "HISTORY:${it.id}" to
                withContext(Dispatchers.Default) { SavePayload.history(it.row()) } } +
                pendingNotebookContent.values.toList().associate { "NOTEBOOK:${it.id}" to it.value() }
            saveRecoveryReady = scan.unreadable == 0 && memory.all { (key, payload) ->
                copies[key]?.payload == payload
            }
            saveJournalIssue = scan.unreadable > 0
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { saveJournalIssue = true }
        saveRevision++
        statusDirty = false
    }

    // Called under writeMutex. All admitted writes are counted before suspending; the last
    // writer scans once, including when its own snapshot has already been superseded.
    private suspend fun refreshAfterWrite(enabled: Boolean) {
        if (enabled && statusDirty && saveOperations == 1) refresh()
    }

    fun queueNotebookSave(payload: SavePayload) =
        queueNotebookSave(SaveSnapshot(payload.id) { payload })

    fun queueNotebookSave(snapshot: SaveSnapshot<SavePayload>) {
        if(isNotebookBlocked(snapshot.id)) return
        hasSaveActivity = true
        pendingNotebookContent[snapshot.id] = snapshot
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withContext(kotlinx.coroutines.NonCancellable) { persistNotebookContent(snapshot) }
        }
    }
    private suspend fun persistNotebookContent(snapshot: SaveSnapshot<SavePayload>, refreshAfter: Boolean = true) {
        saveOperations++
        try {
            solveSessionWriteMutex.withLock {
                try {
                    fun current() = pendingNotebookContent[snapshot.id] === snapshot &&
                        !isNotebookDeleted(snapshot.id)
                    if (!current()) return@withLock
                    statusDirty = true
                    try {
                        val payload = snapshot.value()
                        if (!current()) return@withLock
                        pendingSaveRepository.saveNotebook(payload)
                        if (pendingNotebookContent[snapshot.id] === snapshot) pendingNotebookContent.remove(snapshot.id)
                        onNotebookSaved(payload)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the frozen snapshot if preparation or journal creation failed. */ }
                } finally { refreshAfterWrite(refreshAfter) }
            }
        } finally { saveOperations-- }
    }

    private suspend fun persistHistoryTicket(ticket: HistoryWriteQueue.Ticket, refreshAfter: Boolean = true) {
        saveOperations++
        try { solveSessionWriteMutex.withLock {
            try {
                if (!historyWriteQueue.isCurrent(ticket)) return@withLock
                statusDirty = true
                try {
                    val row = ticket.row()
                    if (!historyWriteQueue.isCurrent(ticket)) return@withLock
                    pendingSaveRepository.saveHistory(row, historyWriteQueue.canCreate(ticket), historyCutoff())
                    historyWriteQueue.complete(ticket)
                } catch (e: CancellationException) {
                    historyWriteQueue.fail(ticket)
                    throw e
                } catch (_: Exception) {
                    // Retain the snapshot for explicit retry; never expose exception payloads or question text.
                    historyWriteQueue.fail(ticket)
                } finally {
                    failedHistoryWrites = historyWriteQueue.failedTickets().size
                }
            } finally { refreshAfterWrite(refreshAfter) }
        } } finally { saveOperations-- }
    }

    fun retryFailedHistoryWrites(afterRetry: suspend () -> Unit) {
        if (retryingHistoryWrites) return
        retryingHistoryWrites = true
        val tickets = historyWriteQueue.failedTickets()
        scope.launch {
            try {
                tickets.forEach { persistHistoryTicket(it, refreshAfter = false) }
                pendingNotebookContent.values.toList().forEach { persistNotebookContent(it, refreshAfter = false) }
                solveSessionWriteMutex.withLock {
                    try { pendingSaveRepository.retryAll(historyCutoff()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { saveJournalIssue = true }
                    refresh()
                }
                afterRetry()
            } finally {
                // A cancelled retry must also publish the state of writes it already finished.
                withContext(NonCancellable) { writeMutex.withLock { if (statusDirty) refresh() } }
                retryingHistoryWrites = false
            }
        }
    }

    suspend fun draftCanBeCleared(value: ConversationDraft): Boolean {
        val chats=value.completedChats ?: return false
        val text=value.completedQuestion ?: value.question
        val answer=chats.lastOrNull { it.role=="assistant" }?.content.orEmpty()
        val conversation=withContext(Dispatchers.Default) { json.encodeToString(chats) }
        return try {
            val scan=withContext(Dispatchers.IO) { pendingSaveStore.scan() }
            if(scan.unreadable!=0 || scan.entries.any { it.payload.id==value.id } ||
                value.id in pendingNotebookContent || historyWriteQueue.failedTickets().any { it.id==value.id }) return false
            // Absence of an error is not evidence of a save: read back a durable destination.
            val notebook=dao.heavyOnce(value.id)
            val history=dao.solveSessionOnce(value.id)
            (notebook!=null && !notebook.deleted && notebook.text==text && notebook.answer==answer && notebook.conversationJson==conversation) ||
                (history!=null && history.text==text && history.answer==answer && history.conversationJson==conversation)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { saveJournalIssue=true; false }
    }
    suspend fun exportEntries(): PendingSaveScan {
        val scan = withContext(Dispatchers.IO) { pendingSaveStore.scan() }
        val entries = scan.entries.associateBy { it.target to it.payload.id }.toMutableMap()
        var skipped = scan.unreadable
        suspend fun include(target: SaveTarget, id: QuestionId, prepare: suspend () -> SavePayload) {
            try {
                entries[target to id] = PendingSave(target, prepare(), null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { skipped++ }
        }
        historyWriteQueue.failedTickets().forEach { ticket ->
            include(SaveTarget.HISTORY, ticket.id) { withContext(Dispatchers.Default) { SavePayload.history(ticket.row()) } }
        }
        pendingNotebookContent.values.toList().forEach { snapshot ->
            include(SaveTarget.NOTEBOOK, snapshot.id) { snapshot.value() }
        }
        return PendingSaveScan(entries.values.toList(), skipped)
    }
}
