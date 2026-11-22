package com.zsz.studyassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.NotebookPracticeRound
import com.zsz.studyassistant.data.NotebookPracticeStore
import com.zsz.studyassistant.data.PracticeFileState
import com.zsz.studyassistant.data.PracticeRating
import com.zsz.studyassistant.data.QuestionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private class PracticeWriter(private val store: NotebookPracticeStore, private val scope: CoroutineScope) {
    private var writing by mutableStateOf<PracticeFileState.Ready?>(null)
    val busy get() = writing != null
    var failed by mutableStateOf(false)

    fun save(previous: PracticeFileState.Ready, next: NotebookPracticeRound?, saved: () -> Unit = {}) {
        if (writing === previous || store.state.value !== previous) return
        writing = previous
        failed = false
        scope.launch {
            try {
                if (store.save(previous, next)) saved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { if (writing === previous) writing = null }
        }
    }
}

@Composable
private fun rememberPracticeWriter(store: NotebookPracticeStore): PracticeWriter {
    val scope = rememberCoroutineScope()
    return remember(store, scope) { PracticeWriter(store, scope) }
}

@Composable
private fun PracticeError(text: String, dismiss: () -> Unit, retry: (() -> Unit)? = null) {
    val s = LocalStrings.current
    AlertDialog(onDismissRequest = dismiss, text = { Text(text) },
        confirmButton = { TextButton(onClick = retry ?: dismiss) { Text(s[if (retry == null) "common.ok" else "update.retry"]) } },
        dismissButton = { if (retry != null) TextButton(onClick = dismiss) { Text(s["common.cancel"]) } }
    )
}

@Composable
internal fun NotebookPracticeEntry(
    store: NotebookPracticeStore, canStart: Boolean, ids: () -> List<QuestionId>, open: () -> Unit
) {
    val s = LocalStrings.current
    val state by store.state.collectAsStateWithLifecycle()
    val ready = state as? PracticeFileState.Ready
    val writer = rememberPracticeWriter(store)
    val scope = rememberCoroutineScope()
    var choose by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(store) { store.load() }
    fun start() {
        if (ready == null || !canStart) return
        writer.save(ready, NotebookPracticeRound.start(ids())) { choose = false; open() }
    }
    TextButton(enabled = !writer.busy && state != PracticeFileState.Loading &&
        (canStart || ready?.round != null || state == PracticeFileState.ReadFailed),
        onClick = { if (ready != null && ready.round == null) start() else choose = true }
    ) { Text(s["practice.title"]) }
    val round = ready?.round
    if (choose && round != null) {
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text(s["practice.title"]) },
            text = { Text(s.format("practice.saved", "done" to "${round.items.count { it.rating != null }}", "total" to "${round.items.size}")) },
            confirmButton = {
                TextButton(enabled = !writer.busy, onClick = { choose = false; open() }) {
                    Text(s[if (round.cursor < 0) "practice.summary" else "practice.continue"])
                }
            },
            dismissButton = {
                TextButton(enabled = canStart && !writer.busy, onClick = ::start) { Text(s["practice.new"]) }
            }
        )
    }
    if (choose && state == PracticeFileState.ReadFailed) {
        PracticeError(s["practice.readFailed"], { choose = false }) { scope.launch { store.load() } }
    }
    if (writer.failed) PracticeError(s["practice.saveFailed"], { writer.failed = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotebookPracticeScreen(nav: NavHostController, vm: MainViewModel) {
    val store = vm.notebookPractice
    val state by store.state.collectAsStateWithLifecycle()
    val ready = state as? PracticeFileState.Ready
    val round = ready?.round
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val writer = rememberPracticeWriter(store)
    LaunchedEffect(store) { store.load() }
    fun backToNotebook() {
        if (nav.currentDestination?.route == AppNav.NOTEBOOK_PRACTICE) nav.popBackStack()
    }
    LaunchedEffect(ready) { if (ready != null && ready.round == null) backToNotebook() }
    if (round == null) {
        if (state == PracticeFileState.ReadFailed) {
            PracticeError(s["practice.readFailed"], ::backToNotebook) { scope.launch { store.load() } }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    key(round.roundId) {
        var ended by rememberSaveable { mutableStateOf(false) }
        val index = remember(round) { round.cursor }
        val complete = index < 0
        val showSummary = ended || complete
        fun leave() {
            if (writer.busy) return
            when {
                !showSummary -> ended = true
                complete -> writer.save(ready, null)
                else -> backToNotebook()
            }
        }
        BackHandler(onBack = ::leave)
        Scaffold(topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                navigationIcon = { SubPageBackArrow(onClick = ::leave) },
                title = { Text(s[if (showSummary) "practice.summary" else "practice.title"], fontSize = SUBPAGE_TITLE) },
                actions = {
                    if (!showSummary) Text(s.format("review.progress", "i" to "${index + 1}", "n" to "${round.items.size}"),
                        modifier = Modifier.padding(end = 12.dp))
                }
            )
        }) { padding ->
            Column(Modifier.padding(padding).readablePage().padding(horizontal = 12.dp),
                verticalArrangement = if (showSummary) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (showSummary) {
                    PracticeSummary(round, writer.busy, onResume = { ended = false }, onLeave = ::leave,
                        onRepeat = { ids -> writer.save(ready, NotebookPracticeRound.start(ids)) })
                } else {
                    val id = round.items[index].id
                    key(id) {
                        PracticeCard(id, vm, writer.busy) { rating ->
                            writer.save(ready, round.advance(id, rating))
                        }
                    }
                }
            }
        }
        if (writer.failed) PracticeError(s["practice.saveFailed"], { writer.failed = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeSummary(
    round: NotebookPracticeRound, busy: Boolean, onResume: () -> Unit,
    onLeave: () -> Unit, onRepeat: (List<QuestionId>) -> Unit
) {
    val s = LocalStrings.current
    val counts = remember(round) { round.items.groupingBy { it.rating }.eachCount() }
    var includeSkipped by rememberSaveable { mutableStateOf(false) }
    val retry = remember(round, includeSkipped) { round.retryIds(includeSkipped) }
    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.format("practice.results", "total" to "${round.items.size}", "easy" to "${counts[PracticeRating.FAMILIAR] ?: 0}",
            "vague" to "${counts[PracticeRating.VAGUE] ?: 0}", "forgot" to "${counts[PracticeRating.FORGOT] ?: 0}",
            "skipped" to "${counts[PracticeRating.SKIPPED] ?: 0}", "left" to "${counts[null] ?: 0}"))
        Text(s["practice.temporary"], modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
        if (PracticeRating.SKIPPED in counts) {
            Row(Modifier.toggleable(includeSkipped, enabled = !busy, role = Role.Checkbox, onValueChange = { includeSkipped = it })
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeSkipped, onCheckedChange = null)
                Text(s["practice.includeSkipped"], modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (PracticeRating.VAGUE in counts || PracticeRating.FORGOT in counts || PracticeRating.SKIPPED in counts) {
            Button(enabled = !busy && retry.isNotEmpty(), onClick = { onRepeat(retry) }) {
                Text(s.format("practice.repeat", "count" to "${retry.size}"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (null in counts) TextButton(enabled = !busy, onClick = onResume) { Text(s["practice.continue"]) }
            TextButton(enabled = !busy, onClick = onLeave) { Text(s["practice.return"]) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PracticeCard(
    id: QuestionId, vm: MainViewModel, busy: Boolean, advance: (PracticeRating?) -> Unit
) {
    val s = LocalStrings.current
    var messages by remember { mutableStateOf<List<ChatMsg>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        deleted = false
        try {
            messages = vm.readPracticeQuestion(id)
            if (messages == null) {
                // A confirmed deletion is removed, never counted as a voluntary skip.
                deleted = true
                failed = true
                advance(null)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Text(s["practice.temporary"], modifier = Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    val card = messages
    if (card == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (failed && !busy) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s["practice.unavailable"])
                TextButton(onClick = { attempt++ }) { Text(s["update.retry"]) }
                if (!deleted) TextButton(onClick = { advance(PracticeRating.SKIPPED) }) { Text(s["practice.skip"]) }
            } else CircularProgressIndicator()
        }
    } else {
        ConversationWebView(
            messages = if (revealed) card else card.take(1),
            modifier = Modifier.weight(1f).fillMaxSize(),
            dark = rememberDarkTheme(vm.theme), fontScale = vm.fontScale.factor
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (revealed) {
                Button(enabled = !busy, onClick = { advance(PracticeRating.FORGOT) }) { Text(s["solve.forgot"]) }
                Button(enabled = !busy, onClick = { advance(PracticeRating.VAGUE) }) { Text(s["solve.vague"]) }
                Button(enabled = !busy, onClick = { advance(PracticeRating.FAMILIAR) }) { Text(s["solve.familiar"]) }
            } else {
                Button(enabled = !busy, onClick = { revealed = true }) { Text(s["review.showAnswer"]) }
                TextButton(enabled = !busy, onClick = { advance(PracticeRating.SKIPPED) }) { Text(s["practice.skip"]) }
            }
        }
    }
}
