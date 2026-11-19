package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.data.*
import kotlinx.coroutines.*

/** The caller supplies only documents in scope for the selected message. */
@Composable
internal fun PdfSourceSearchDialog(query: String, documents: List<ConversationDocument>, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current.applicationContext
    var matches by remember(query, documents) { mutableStateOf<List<PdfSourceMatch>?>(null) }
    var failure by remember(query, documents) { mutableStateOf<String?>(null) }
    var selected by remember(query, documents) { mutableStateOf<PdfSourceMatch?>(null) }
    var bitmap by remember(selected) { mutableStateOf<Bitmap?>(null) }
    var previewFailure by remember(selected) { mutableStateOf<String?>(null) }
    LaunchedEffect(query, documents) {
        try {
            matches = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                PdfSourceSearch.find(DocumentStore(context), documents, query) { job.ensureActive() }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = (error as? DocumentProblem)?.messageKey ?: "document.pdfUnreadable" }
    }
    LaunchedEffect(selected) {
        val match = selected ?: return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val file = DocumentStore(context).verifiedFile(match.document) { job.ensureActive() }
                PdfDocumentSelection.Preview().use { preview ->
                    val count = preview.open(file)
                    if (match.pageIndex !in PdfSourceSearch.originalPages(match.document, count))
                        throw DocumentProblem("document.invalidSelection")
                    preview.thumbnail(match.pageIndex) { job.ensureActive() }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { previewFailure = (error as? DocumentProblem)?.messageKey ?: "document.pdfUnreadable" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s["pdfSource.title"], style = MaterialTheme.typography.titleLarge)
                Text(s["pdfSource.hint"], style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected == null) {
                        if (failure == null && matches == null) item { CircularProgressIndicator() }
                        failure?.let { key -> item { Text(s[key], color = MaterialTheme.colorScheme.error) } }
                        if (matches?.isEmpty() == true) item { Text(s["pdfSource.noMatch"]) }
                        itemsIndexed(matches.orEmpty()) { _, match ->
                            TextButton(onClick = { selected = match }) {
                                Text(s.format("pdfSource.page", "name" to match.document.name,
                                    "n" to (match.pageIndex + 1).toString()))
                            }
                        }
                    } else {
                        val match = selected!!
                        item { Text(s.format("pdfSource.page", "name" to match.document.name,
                            "n" to (match.pageIndex + 1).toString())) }
                        if (bitmap == null && previewFailure == null) item { CircularProgressIndicator() }
                        previewFailure?.let { key -> item { Text(s[key], color = MaterialTheme.colorScheme.error) } }
                        bitmap?.let { preview -> item {
                            Image(preview.asImageBitmap(),
                                contentDescription = s.format("documentSelection.pagePreview", "n" to (match.pageIndex + 1).toString()),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 900.dp), contentScale = ContentScale.Fit)
                        } }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (selected != null) TextButton(onClick = { selected = null }) { Text(s["pdfSource.results"]) }
                    TextButton(onClick = onDismiss) { Text(s["pdfSource.close"]) }
                }
            }
        }
    }
}
