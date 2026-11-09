package com.zsz.studyassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.QuestionId
import kotlinx.coroutines.CancellationException

/** The notebook entry owns the snapshot. Large lists of IDs never enter a saved-state Bundle. */
internal class NotebookPracticeQueue : ViewModel() {
    var ids: List<QuestionId> = emptyList()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NotebookPracticeScreen(nav: NavHostController, vm: MainViewModel) {
    // An outgoing destination may still be composed after a notification replaced its stack.
    val ids = remember(nav) {
        nav.previousBackStackEntry?.takeIf { it.destination.route == AppNav.NOTEBOOK }?.let {
            ViewModelProvider(it)[NotebookPracticeQueue::class.java].ids
        }.orEmpty()
    }
    if (ids.isEmpty()) {
        // A process restart ends this in-memory practice and restores the filtered list.
        LaunchedEffect(Unit) { if (nav.currentDestination?.route == AppNav.NOTEBOOK_PRACTICE) nav.popBackStack() }
        return
    }
    val s = LocalStrings.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var familiar by rememberSaveable { mutableIntStateOf(0) }
    var vague by rememberSaveable { mutableIntStateOf(0) }
    var forgot by rememberSaveable { mutableIntStateOf(0) }
    var ended by rememberSaveable { mutableStateOf(false) }
    val id = ids.getOrNull(index)
    val showSummary = ended || id == null
    fun leave() { if (showSummary) nav.popBackStack() else ended = true }
    BackHandler(enabled = !showSummary) { ended = true }
    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 56.dp,
            navigationIcon = { SubPageBackArrow(onClick = ::leave) },
            title = { Text(s[if (showSummary) "practice.summary" else "practice.title"], fontSize = SUBPAGE_TITLE) },
            actions = {
                if (!showSummary) {
                    Text(s.format("review.progress", "i" to "${index + 1}", "n" to "${ids.size}"),
                        modifier = Modifier.padding(end = 12.dp))
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).readablePage().padding(horizontal = 12.dp),
            verticalArrangement = if (showSummary) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSummary || id == null) {
                Text(s.format("practice.results", "total" to "${ids.size}", "easy" to "$familiar",
                    "vague" to "$vague", "forgot" to "$forgot", "left" to "${ids.size - familiar - vague - forgot}"))
                Text(s["practice.temporary"], modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { nav.popBackStack() }) { Text(s["practice.return"]) }
            } else key(id) {
                fun advance(rate: () -> Unit = {}) {
                    if (ids.getOrNull(index) == id) {
                        rate()
                        index++
                    }
                }
                var messages by remember { mutableStateOf<List<ChatMsg>?>(null) }
                var failed by remember { mutableStateOf(false) }
                var attempt by remember { mutableIntStateOf(0) }
                var revealed by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(attempt) {
                    failed = false
                    try {
                        messages = vm.readPracticeQuestion(id)
                        failed = messages == null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                }
                Text(s["practice.temporary"], modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
                val card = messages
                if (card == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s["practice.unavailable"])
                            TextButton(onClick = { attempt++ }) { Text(s["update.retry"]) }
                            TextButton(onClick = { advance() }) { Text(s["practice.skip"]) }
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
                            Button(onClick = { advance { forgot++ } }) { AppIconLabel(s["solve.forgot"], iconSize = 16.dp, iconSpacing = 4.dp, onPrimary = true) }
                            Button(onClick = { advance { vague++ } }) { AppIconLabel(s["solve.vague"], iconSize = 16.dp, iconSpacing = 4.dp, onPrimary = true) }
                            Button(onClick = { advance { familiar++ } }) { AppIconLabel(s["solve.familiar"], iconSize = 16.dp, iconSpacing = 4.dp, onPrimary = true) }
                        } else {
                            Button(onClick = { revealed = true }) { AppIconLabel(s["review.showAnswer"], iconSize = 16.dp, iconSpacing = 4.dp, onPrimary = true) }
                            TextButton(onClick = { advance() }) { Text(s["practice.skip"]) }
                        }
                    }
                }
            }
        }
    }
}
