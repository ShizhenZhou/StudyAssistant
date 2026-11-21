package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** The caller supplies only documents in scope for the selected message. */
@Composable
internal fun PdfSourceSearchDialog(query: String, documents: List<ConversationDocument>,
    session: PdfSourceSession, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current.applicationContext
    var matches by remember(query, documents) { mutableStateOf<List<PdfSourceMatch>?>(null) }
    var failure by remember(query, documents) { mutableStateOf<String?>(null) }
    var progress by remember(query, documents) { mutableStateOf<PdfSourceProgress?>(null) }
    var selected by remember(query, documents) { mutableStateOf<PdfSourceMatch?>(null) }
    var preview by remember(selected) { mutableStateOf<PdfSourcePreview?>(null) }
    var previewFailure by remember(selected) { mutableStateOf<String?>(null) }
    LaunchedEffect(query, documents, session) {
        // Conflate worker updates and publish Compose state on the main coroutine only.
        val updates = Channel<PdfSourceProgress>(Channel.CONFLATED)
        val collector = launch { for (update in updates) progress = update }
        try {
            matches = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                PdfSourceSearch.find(DocumentStore(context), documents, query, session,
                    onProgress = { updates.trySend(it) }, check = { job.ensureActive() })
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = (error as? DocumentProblem)?.messageKey ?: "document.pdfUnreadable" }
        finally { updates.close(); collector.cancel() }
    }
    LaunchedEffect(selected) {
        val match = selected ?: return@LaunchedEffect
        try {
            preview = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val file = DocumentStore(context).verifiedFile(match.document) { job.ensureActive() }
                PdfSourcePreview.load(file, match, query) { job.ensureActive() }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { previewFailure = (error as? DocumentProblem)?.messageKey ?: "document.pdfUnreadable" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s["pdfSource.title"], style = MaterialTheme.typography.titleLarge)
                if (selected == null) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text(s["pdfSource.hint"], style = MaterialTheme.typography.bodySmall) }
                        if (failure == null && matches == null) item {
                            val current = progress
                            if (current == null || current.pageCount == 0) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(progress = { current.completedPages.toFloat() / current.pageCount },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            current?.let {
                                Text(s.format("pdfSource.documentProgress", "name" to it.documentName,
                                    "n" to it.documentNumber.toString(), "total" to it.documentCount.toString()))
                                if (it.pageCount == 0) Text(s["pdfSource.verifying"])
                                else Text(s.format("pdfSource.pageProgress",
                                    "done" to it.completedPages.toString(), "total" to it.pageCount.toString(),
                                    "page" to (it.originalPage ?: 1).toString(),
                                    "percent" to (it.completedPages * 100 / it.pageCount).toString()))
                            }
                        }
                        failure?.let { key -> item { Text(s[key], color = MaterialTheme.colorScheme.error) } }
                        if (matches?.isEmpty() == true) item { Text(s["pdfSource.noMatch"]) }
                        itemsIndexed(matches.orEmpty()) { _, match ->
                            TextButton(onClick = { selected = match }) {
                                Text(s.format("pdfSource.page", "name" to match.document.name,
                                    "n" to (match.pageIndex + 1).toString()))
                            }
                        }
                    }
                } else {
                    val match = selected!!
                    Text(s.format("pdfSource.page", "name" to match.document.name,
                        "n" to (match.pageIndex + 1).toString()), maxLines = 2)
                    if (preview == null && previewFailure == null) CircularProgressIndicator()
                    previewFailure?.let { key -> Text(s[key], color = MaterialTheme.colorScheme.error) }
                    preview?.let {
                        PdfSourcePage(it,
                            s.format("documentSelection.pagePreview", "n" to (match.pageIndex + 1).toString()),
                            Modifier.fillMaxWidth().weight(1f))
                    } ?: Spacer(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (selected != null) TextButton(onClick = { selected = null }) { Text(s["pdfSource.results"]) }
                    val loading = if (selected == null) matches == null && failure == null
                        else preview == null && previewFailure == null
                    TextButton(onClick = onDismiss) { Text(s[if (loading) "pdfSource.cancel" else "pdfSource.close"]) }
                }
            }
        }
    }
}
