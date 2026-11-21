package com.zsz.studyassistant.data

import android.app.Application
import android.os.SystemClock
import kotlinx.coroutines.*

/** Owns one stream from preparation to service cleanup. Events are delivered on Main only
 * while the request still owns its conversation; usage/service cleanup also runs for stale jobs.
 */
internal class ConversationGeneration(
    private val app: Application,
    private val scope: CoroutineScope,
    private val lifecycle: SolveRequestLifecycle,
    private val documentStore: () -> DocumentStore,
    private val recordUsage: (DeepSeekUsage?) -> Unit
) {
    data class Request(
        val model: String,
        val messages: List<DeepSeekMessage>,
        val prefix: String,
        val action: String,
        val webEnabled: Boolean,
        val requestTitle: Boolean,
        val prepare: (() -> List<DeepSeekMessage>)?
    )

    interface Events {
        fun isCurrent(): Boolean
        fun started()
        suspend fun prepared(messages: List<DeepSeekMessage>)
        fun body(text: String, thinking: String?, checkpoint: Boolean)
        fun reasoning(text: String, checkpoint: Boolean)
        fun toolProgress(key: String)
        suspend fun completed(body: String, thinking: String?)
        suspend fun interrupted(error: Exception, partial: String, thinking: String?,
                                messages: List<DeepSeekMessage>, aborted: Boolean, keepPartial: Boolean)
        fun finished(keepPartial: Boolean)
    }

    fun start(request: Request, eventsFor: (StreamOwner) -> Events) {
        val owner = lifecycle.begin()
        val sequence = lifecycle.sequence
        val events = eventsFor(owner)
        fun current() = lifecycle.owns(owner, sequence) && events.isCurrent()
        lifecycle.job = scope.launch {
            var messages = request.messages
            val body = StringBuilder()
            val thinking = StringBuilder()
            var nextBodyEmit = 0L
            var nextThinkEmit = 0L
            var nextDraftWrite = 0L
            var keepPartial = false
            var completed = false
            try {
                events.started()
                AnswerForegroundService.start(app, owner.id)
                lifecycle.prepareRetainingOnCancel(
                    prepare = { request.prepare?.let { withContext(Dispatchers.Default) { it() } } ?: messages },
                    // Retain the frozen request even if cancelled during local encoding.
                    retain = { messages = it; events.prepared(it) }
                )
                if (!current() || messages.isEmpty()) return@launch
                val reply = withContext(Dispatchers.IO) {
                    StudyAssistant.chatStream(request.model, messages, documentStore = documentStore(),
                        onDelta = delta@{ delta ->
                            body.append(delta)
                            val now = SystemClock.elapsedRealtime()
                            if (now < nextBodyEmit) return@delta
                            nextBodyEmit = now + 180
                            val parsed = ThinkingOutput.split(body.toString())
                            val checkpoint = now >= nextDraftWrite
                            if (checkpoint) nextDraftWrite = now + 1000
                            withContext(Dispatchers.Main) {
                                if (current()) events.body(request.prefix + parsed.body, parsed.thinking, checkpoint)
                            }
                        },
                        thinkingEnabled = CapturePrefs.solveModel(app) != "flash", owner = owner,
                        task = request.action, webEnabled = request.webEnabled, requestTitle = request.requestTitle,
                        onToolProgress = { key -> withContext(Dispatchers.Main) { if (current()) events.toolProgress(key) } },
                        onReasoning = { delta ->
                            thinking.append(delta)
                            val now = SystemClock.elapsedRealtime()
                            if (now >= nextThinkEmit) {
                                nextThinkEmit = now + 120
                                val snapshot = thinking.toString()
                                // Share the body stream's budget: reasoning-only responses must also
                                // survive process death, without doubling checkpoint writes.
                                val checkpoint = now >= nextDraftWrite
                                if (checkpoint) nextDraftWrite = now + 1000
                                withContext(Dispatchers.Main) { if (current()) events.reasoning(snapshot, checkpoint) }
                            }
                        })
                }
                if (!current()) return@launch
                val parsed = ThinkingOutput.split(reply)
                events.completed(parsed.body, thinking.toString().takeIf { it.isNotEmpty() } ?: parsed.thinking)
                completed = current() && currentCoroutineContext().isActive
            } catch (e: Exception) {
                val aborted = e is CancellationException || !currentCoroutineContext().isActive
                val parsed = ThinkingOutput.split(body.toString())
                val partial = request.prefix + parsed.body
                keepPartial = partial.isNotBlank() || aborted || e is StreamIncompleteException
                if (current()) events.interrupted(e, partial,
                    thinking.toString().takeIf { it.isNotEmpty() } ?: parsed.thinking, messages, aborted, keepPartial)
                if (e is CancellationException) throw e
            } finally {
                try {
                    recordUsage(owner.usage)
                } finally {
                    if (current()) {
                        events.finished(keepPartial)
                        lifecycle.job = null
                    }
                    AnswerForegroundService.stop(app, owner.id,
                        completed = completed && current() && currentCoroutineContext().isActive)
                }
            }
        }
    }
}
