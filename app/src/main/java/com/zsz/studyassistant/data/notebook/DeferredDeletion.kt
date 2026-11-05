package com.zsz.studyassistant.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DeleteBatch(val token: Long, val questions: Set<QuestionId>, val histories: Set<QuestionId>,
    val categories: Set<Long>, val deadline: Long) {
    val count get() = questions.size + histories.size + categories.size
}
data class DeleteState(val pending: DeleteBatch? = null, val committing: List<DeleteBatch> = emptyList()) {
    val questionIds get() = committing.flatMap { it.questions }.toSet() + pending?.questions.orEmpty()
    val historyIds get() = committing.flatMap { it.histories }.toSet() + pending?.histories.orEmpty()
    val categoryIds get() = committing.flatMap { it.categories }.toSet() + pending?.categories.orEmpty()
    val active get() = pending != null || committing.isNotEmpty()
}
/** Main-thread owned. Nothing destructive happens before the undo window ends.
 * Rapid deletions merge into the visible batch and restart its 3-second window.
 */
class DeferredDeletion(private val scope: CoroutineScope, private val commit: suspend (DeleteBatch) -> Unit,
    private val failure: (DeleteBatch) -> Unit, private val windowMs: Long = 3000,
    private val now: () -> Long = { System.nanoTime()/1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) }) {
    private val mutable = MutableStateFlow(DeleteState())
    val state: StateFlow<DeleteState> = mutable
    private var sequence = 0L
    private var timer: Job? = null
    fun stage(questions: Set<QuestionId> = emptySet(), histories: Set<QuestionId> = emptySet(), categories: Set<Long> = emptySet()) {
        if (questions.isEmpty() && histories.isEmpty() && categories.isEmpty()) return
        val old = mutable.value.pending
        val batch = DeleteBatch(++sequence, old?.questions.orEmpty()+questions,
            old?.histories.orEmpty()+histories, old?.categories.orEmpty()+categories, now()+windowMs)
        timer?.cancel()
        mutable.value = mutable.value.copy(pending=batch)
        timer=scope.launch {
            wait(windowMs)
            if(mutable.value.pending?.token != batch.token) return@launch
            mutable.value = mutable.value.copy(pending=null, committing=mutable.value.committing+batch)
            scope.launch {
                withContext(NonCancellable) {
                    try { commit(batch) } catch (_: Exception) { failure(batch) }
                    finally { mutable.value = mutable.value.copy(committing=mutable.value.committing.filter { it.token!=batch.token }) }
                }
            }
        }
    }
    fun undo(token: Long): Boolean {
        val batch=mutable.value.pending ?: return false
        if(batch.token!=token || now()>batch.deadline) return false
        timer?.cancel(); mutable.value=mutable.value.copy(pending=null)
        return true
    }
}
