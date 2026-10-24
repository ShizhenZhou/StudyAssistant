package com.zsz.studyassistant

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zsz.studyassistant.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
    private var analysisInFlight by mutableStateOf(false)
    val busy: Boolean get() = operationBusy || analysisBusy || analysisInFlight
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
    var draftStatusKey by mutableStateOf<String?>(null); private set
    private val draftStore = TagDraftStore(app)
    private val draftWrites = Channel<Pair<Long, TagDraftDocument?>>(Channel.CONFLATED)
    private var draftRevision = 0L
    private var draftLoaded = false
    private val diagnostics = AnalysisDiagnosticsStore(app)
    var diagnosticId by mutableStateOf<String?>(null); private set
    var diagnosticCount by mutableStateOf(0); private set
    var diagnosticStatusKey by mutableStateOf<String?>(null); private set

    private suspend fun readDiagnostics() {
        try {
            val document = withContext(Dispatchers.IO) { diagnostics.read() }
            diagnosticId = document.records.lastOrNull()?.id
            diagnosticCount = document.records.size
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { diagnosticStatusKey = "tagMerge.diagnosticUnavailable" }
    }

    fun exportDiagnostics(uri: Uri) {
        if (busy) return
        operationBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = diagnostics.export()
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("No output stream")
                }
                diagnosticStatusKey = "tagMerge.diagnosticExported"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diagnosticStatusKey = "tagMerge.diagnosticExportFailed" }
            finally { operationBusy = false }
        }
    }

    fun clearDiagnostics() {
        if (busy) return
        operationBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { diagnostics.clear() }
                diagnosticId = null; diagnosticCount = 0; diagnosticStatusKey = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diagnosticStatusKey = "tagMerge.diagnosticUnavailable" }
            finally { operationBusy = false }
        }
    }

    init {
        viewModelScope.launch {
            for ((revision, document) in draftWrites) {
                try {
                    val saved = withContext(NonCancellable + Dispatchers.IO) { draftStore.write(document, revision) }
                    if (revision == draftRevision) draftStatusKey = if (saved) document?.let { "tagMerge.draftSaved" }
                        else "tagMerge.draftStale"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (revision == draftRevision) draftStatusKey = "tagMerge.draftSaveFailed" }
            }
        }
    }

    private fun saveDraft() {
        val base = inventory ?: return
        val document = if (drafts.isEmpty()) null else TagDraftDocument(
            fingerprint = base.fingerprint(), savedAt = System.currentTimeMillis(),
            drafts = drafts.map { StoredTagDraft(it.group, it.selected) })
        draftStatusKey = "tagMerge.draftSaving"
        draftRevision = TagDraftStore.reserveRevision()
        draftWrites.trySend(draftRevision to document)
    }

    private suspend fun restoreDraft(base: TagInventory) {
        if (draftLoaded) return
        draftLoaded = true
        try {
            val document = withContext(Dispatchers.IO) { draftStore.read() } ?: return
            if (!TagDraftCodec.matches(document, base)) {
                draftStatusKey = "tagMerge.draftStale"
                return
            }
            drafts = document.drafts.map { TagMergeDraft(++nextKey, it.group, it.selected) }
            prepared = null
            draftStatusKey = "tagMerge.draftRestored"
        } catch (e: CancellationException) { draftLoaded = false; throw e }
        catch (_: Exception) { draftStatusKey = "tagMerge.draftUnreadable" }
    }

    fun discardDraft() {
        if (busy) return
        drafts = emptyList(); prepared = null; noticeKey = null
        saveDraft()
    }

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
                    draftRevision = TagDraftStore.reserveRevision()
                    drafts = emptyList(); prepared = null
                    noticeKey = "tagMerge.reloaded"
                    draftStatusKey = "tagMerge.draftStale"
                }
                state(base, undo)
                restoreDraft(base)
                readDiagnostics()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { operationBusy = false }
        }
    }

    fun analyze(language: String) {
        if (busy) return
        val hasKey = try { KeyManager.getApiKey().isNotBlank() } catch (_: Exception) { false }
        if (!hasKey) { errorHttpCode = null; errorKey = "tagMerge.needKey"; return }
        analysisBusy = true; errorKey = null; errorHttpCode = null; noticeKey = null; prepared = null
        val thinking = CapturePrefs.solveModel(getApplication()) != "flash"
        val recordId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val startedClock = SystemClock.elapsedRealtime()
        val generation = ++analysisGeneration
        analysisInFlight = true
        analysisJob = viewModelScope.launch {
            var tagCount = 0
            var finish = AnalysisFinish.MISSING
            var outcome = AnalysisOutcome.FAILURE
            var failure = AnalysisFailure.UNKNOWN
            var httpCode: Int? = null
            var accepted: Int? = null
            var rejected: Int? = null
            try {
                val (base, undo) = TagMergeRepository.snapshot(db)
                tagCount = base.tags.size
                if (base.tags.size < 2) throw TagMergeException("tagMerge.tooFew")
                val request = TagMergeSuggestions.request(base, language,
                    thinkingEnabled = thinking)
                val response = ApiClient.deepSeek.chat(request)
                httpCode = 200
                finish = AnalysisFinish.from(response.choices.firstOrNull()?.finishReason)
                UsageStats.add(getApplication(), response.usage)
                val result = withContext(Dispatchers.Default) { TagMergeSuggestions.parseResponse(response, base) }
                val groups = result.groups
                if (generation != analysisGeneration) { outcome = AnalysisOutcome.CANCELLED; return@launch }
                if (TagMergeRepository.snapshot(db).first.fingerprint() != base.fingerprint())
                    throw TagMergeException("tagMerge.changed")
                state(base, undo)
                drafts = groups.map { TagMergeDraft(++nextKey, it) }
                noticeKey = when {
                    groups.isEmpty() -> "tagMerge.noSuggestions"
                    result.rejectedGroups > 0 -> "tagMerge.suggestionsPartial"
                    else -> "tagMerge.suggestionsReady"
                }
                noticeCount = groups.size
                noticeSkipped = result.rejectedGroups
                saveDraft()
                accepted = groups.size; rejected = result.rejectedGroups
                outcome = AnalysisOutcome.SUCCESS; failure = AnalysisFailure.NONE
            } catch (e: CancellationException) { outcome = AnalysisOutcome.CANCELLED; throw e }
            catch (e: Exception) {
                val mapped = TagAnalysisError.from(e, (e as? retrofit2.HttpException)?.code())
                failure = AnalysisFailure.from(mapped.key)
                httpCode = mapped.httpCode ?: httpCode
                if (generation == analysisGeneration) {
                    errorHttpCode = mapped.httpCode
                    errorKey = mapped.key
                }
            }
            finally {
                val record = AnalysisDiagnostic(recordId, startedAt,
                    (SystemClock.elapsedRealtime() - startedClock).coerceAtLeast(0),
                    if (thinking) AnalysisMode.THINKING else AnalysisMode.FAST, tagCount, outcome, finish,
                    if (outcome == AnalysisOutcome.CANCELLED) AnalysisFailure.NONE else failure,
                    httpCode?.takeIf { it in 100..599 }, accepted, rejected)
                val saved = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { diagnostics.append(record) }.getOrNull()
                }
                if (generation == analysisGeneration) {
                    analysisBusy = false
                    if (saved != null) {
                        diagnosticId = recordId; diagnosticCount = saved.records.size
                        diagnosticStatusKey = null
                    } else diagnosticStatusKey = "tagMerge.diagnosticUnavailable"
                }
            }
        }
        analysisJob?.invokeOnCompletion {
            viewModelScope.launch { analysisInFlight = false }
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
        saveDraft()
    }
    fun edit(key: Long, change: (TagMergeDraft) -> TagMergeDraft) {
        if (busy) return
        prepared = null; errorKey = null
        drafts = drafts.map { if (it.key == key) change(it) else it }
        saveDraft()
    }
    fun remove(key: Long) { if (!busy) { drafts = drafts.filter { it.key != key }; prepared = null; saveDraft() } }
    fun selectAll() { if (!busy) { drafts = drafts.map { it.copy(selected = true) }; prepared = null; saveDraft() } }
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
                state(base, undo); drafts = emptyList(); saveDraft(); changeRevision++
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
                state(base, null); drafts = emptyList(); saveDraft(); changeRevision++
                noticeQuestions = count; noticeKey = "tagMerge.undone"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { operationBusy = false }
        }
    }
}
