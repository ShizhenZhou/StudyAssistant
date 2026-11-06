package com.zsz.studyassistant.data

import android.content.Context
import kotlinx.coroutines.*

/** Owns draft scheduling and disk access; the VM only supplies frozen session snapshots. */
internal class ConversationDraftPersistence(
    context: Context,
    scope: CoroutineScope,
    onSaved: (ConversationDraft) -> Unit,
    onFailure: () -> Unit,
    refresh: suspend () -> Unit
) {
    private val store = ConversationDraftStore(context)
    private data class Write(val draft: ConversationDraft, val baseline: SavePayload?, val refresh: Boolean)
    private val queue = LatestWriteQueue<Pair<QuestionId, String>, Write, ConversationDraft>(
        scope, { it.draft.id to it.draft.generation },
        merge = { old, new -> new.copy(refresh = old.refresh || new.refresh) },
        write = { withContext(Dispatchers.IO) { store.write(it.draft, it.baseline) } },
        onFinished = { request, result ->
            result.fold(onSaved) { onFailure() }
            if (request.refresh) refresh()
        }
    )

    fun reserve(id: QuestionId, generation: String) = store.reserve(id, generation)
    fun checkpoint(value: ConversationDraft, baseline: SavePayload?, refresh: Boolean) {
        queue.submit(Write(value, baseline, refresh))
    }
    suspend fun write(value: ConversationDraft, baseline: SavePayload?) = withContext(NonCancellable) {
        queue.submit(Write(value, baseline, false), awaitResult = true)!!.await()
    }
    suspend fun read(id: QuestionId): ConversationDraft? {
        queue.awaitIdle()
        return withContext(Dispatchers.IO) { store.read(id) }
    }
    suspend fun summaries() = withContext(Dispatchers.IO) { store.summaries() }
    suspend fun clear(id: QuestionId, generation: String) = withContext(NonCancellable + Dispatchers.IO) {
        store.clear(id, generation)
    }
    suspend fun invalidate(ids: Collection<QuestionId>) = withContext(NonCancellable + Dispatchers.IO) { store.invalidate(ids) }
    suspend fun clearAll() = withContext(NonCancellable + Dispatchers.IO) { store.clearAll() }
    suspend fun exportable(): List<ConversationDraft> {
        queue.awaitIdle()
        return withContext(Dispatchers.IO) { store.exportable() }
    }
}
