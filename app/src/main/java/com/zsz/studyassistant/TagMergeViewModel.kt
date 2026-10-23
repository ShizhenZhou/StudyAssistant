package com.zsz.studyassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TagMergeDraft(val key: Long, val group: TagMergeGroup, val selected: Boolean = false)

/** Independent from solve sessions: analyzing labels must not interrupt or create a question conversation. */
class TagMergeViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    var inventory by mutableStateOf<TagInventory?>(null); private set
    var drafts by mutableStateOf<List<TagMergeDraft>>(emptyList()); private set
    var prepared by mutableStateOf<TagMergePreview?>(null); private set
    var operationBusy by mutableStateOf(false); private set
    var analysisBusy by mutableStateOf(false); private set
    val busy: Boolean get() = operationBusy || analysisBusy
    var errorKey by mutableStateOf<String?>(null); private set
    var errorHttpCode by mutableStateOf<Int?>(null); private set
    var noticeKey by mutableStateOf<String?>(null); private set
    var noticeCount by mutableStateOf(0); private set
    var noticeQuestions by mutableStateOf(0); private set
    var noticeSkipped by mutableStateOf(0); private set
    var undoPresent by mutableStateOf(false); private set
    var undoAllowed by mutableStateOf(false); private set
    var changeRevision by mutableStateOf(0); private set
    private var nextKey = 0L
    private var analysisJob: Job? = null
    private var analysisGeneration = 0L

    private fun state(base: TagInventory, undo: TagMergeUndo?) {
        inventory = base
        undoPresent = undo != null
        undoAllowed = TagMergeEngine.canUndo(base, undo)
    }
    private fun failed(e: Exception) {
        errorHttpCode = null
        errorKey = (e as? TagMergeException)?.messageKey ?: "tagMerge.failed"
    }

    fun load() {
        if (busy) return
        operationBusy = true
        viewModelScope.launch {
            try {
                val (base, undo) = TagMergeRepository.snapshot(db)
                if (inventory != null && inventory?.fingerprint() != base.fingerprint()) {
                    drafts = emptyList(); prepared = null
                    noticeKey = "tagMerge.reloaded"
                }
                state(base, undo)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { operationBusy = false }
        }
    }

    fun analyze(language: String) {
        if (busy) return
        val hasKey = try { KeyManager.getApiKey().isNotBlank() } catch (_: Exception) { false }
        if (!hasKey) { errorKey = "tagMerge.needKey"; return }
        analysisBusy = true; errorKey = null; noticeKey = null; prepared = null
        val generation = ++analysisGeneration
        analysisJob = viewModelScope.launch {
            try {
                val (base, undo) = TagMergeRepository.snapshot(db)
                if (base.tags.size < 2) throw TagMergeException("tagMerge.tooFew")
                val request = TagMergeSuggestions.request(base, language,
                    thinkingEnabled = CapturePrefs.solveModel(getApplication()) != "flash")
                val response = ApiClient.deepSeek.chat(request)
                UsageStats.add(getApplication(), response.usage)
                val result = withContext(Dispatchers.Default) { TagMergeSuggestions.parseResponse(response, base) }
                val groups = result.groups
                if (generation != analysisGeneration) return@launch
                state(base, undo)
                drafts = groups.map { TagMergeDraft(++nextKey, it) }
                noticeKey = when {
                    groups.isEmpty() -> "tagMerge.noSuggestions"
                    result.rejectedGroups > 0 -> "tagMerge.suggestionsPartial"
                    else -> "tagMerge.suggestionsReady"
                }
                noticeCount = groups.size
                noticeSkipped = result.rejectedGroups
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == analysisGeneration) {
                    val failure = TagAnalysisError.from(e, (e as? retrofit2.HttpException)?.code())
                    errorHttpCode = failure.httpCode
                    errorKey = failure.key
                }
            }
            finally { if (generation == analysisGeneration) analysisBusy = false }
        }
    }

    fun cancelAnalysis() {
        if (!analysisBusy) return
        analysisGeneration++; analysisJob?.cancel(); analysisBusy = false
    }

    fun addGroup() {
        if (busy) return
        prepared = null
        drafts = drafts + TagMergeDraft(++nextKey, TagMergeGroup(emptyList(), ""), true)
    }
    fun edit(key: Long, change: (TagMergeDraft) -> TagMergeDraft) {
        if (busy) return
        prepared = null; errorKey = null
        drafts = drafts.map { if (it.key == key) change(it) else it }
    }
    fun remove(key: Long) { if (!busy) { drafts = drafts.filter { it.key != key }; prepared = null } }
    fun selectAll() { if (!busy) { drafts = drafts.map { it.copy(selected = true) }; prepared = null } }
    fun preview() {
        if (busy) return
        errorKey = null
        try {
            prepared = TagMergeEngine.preview(inventory ?: throw TagMergeException("tagMerge.changed"),
                drafts.filter { it.selected }.map { it.group })
        } catch (e: Exception) { failed(e) }
    }
    fun dismissPreview() { prepared = null }
    fun dismissError() { errorKey = null; errorHttpCode = null }

    fun apply() {
        if (busy) return
        val plan = prepared ?: return
        prepared = null; operationBusy = true; errorKey = null; noticeKey = null
        viewModelScope.launch {
            try {
                val (result, base, undo) = TagMergeRepository.apply(db, plan)
                state(base, undo); drafts = emptyList(); changeRevision++
                noticeCount = result.removedTags; noticeQuestions = result.affectedQuestions; noticeKey = "tagMerge.applied"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { operationBusy = false }
        }
    }

    fun undo() {
        if (busy) return
        operationBusy = true; errorKey = null; noticeKey = null; prepared = null
        viewModelScope.launch {
            try {
                val (count, base) = TagMergeRepository.undo(db)
                state(base, null); drafts = emptyList(); changeRevision++
                noticeQuestions = count; noticeKey = "tagMerge.undone"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { operationBusy = false }
        }
    }
}
