package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.data.ConversationDocument
import com.zsz.studyassistant.data.DocumentProblem
import com.zsz.studyassistant.data.DocumentStore
import com.zsz.studyassistant.data.LocalDocumentText
import com.zsz.studyassistant.data.PdfDocumentSelection
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Zero-based page selection. Reject malformed or out-of-range input without silently dropping pages. */
internal fun parseDocumentPages(input: String, pageCount: Int): List<Int>? {
    if (input.isBlank()) return emptyList()
    if (input.length > 20000 || pageCount !in 1..1000) return null
    val pages = sortedSetOf<Int>()
    for (part in input.replace('，', ',').split(',')) {
        val range = part.trim().split('-')
        if (range.size !in 1..2 || range.any { !it.trim().matches(Regex("[0-9]+")) }) return null
        val first = range[0].trim().toIntOrNull() ?: return null
        val last = if (range.size == 2) range[1].trim().toIntOrNull() ?: return null else first
        if (first !in 1..pageCount || last !in first..pageCount) return null
        for (page in first..last) pages += page - 1
    }
    return pages.toList()
}

private fun pageRanges(pages: List<Int>): String {
    val sorted = pages.distinct().sorted()
    val ranges = mutableListOf<String>()
    var index = 0
    while (index < sorted.size) {
        val start = sorted[index]
        var end = start
        while (index + 1 < sorted.size && sorted[index + 1] == end + 1) { index++; end++ }
        ranges += if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"
        index++
    }
    return ranges.joinToString(",")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentSelectionDialog(
    document: ConversationDocument,
    onDismiss: () -> Unit,
    onConfirm: (ConversationDocument) -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val type = document.name.substringAfterLast('.', "").lowercase()
    val pdf = type == "pdf"
    val workbook = type == "xlsx"
    val plainText = type in setOf("txt", "md", "markdown", "csv")
    var file by remember(document.sha256) { mutableStateOf<File?>(null) }
    var pageCount by remember(document.sha256) { mutableStateOf(0) }
    var sheetNames by remember(document.sha256) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(document.sha256) { mutableStateOf(true) }
    var errorKey by remember(document.sha256) { mutableStateOf<String?>(null) }
    var allPages by rememberSaveable(document.sha256) { mutableStateOf(document.pages == null) }
    var ranges by rememberSaveable(document.sha256) { mutableStateOf(pageRanges(document.pages.orEmpty())) }
    var currentPage by rememberSaveable(document.sha256) { mutableStateOf(document.pages?.firstOrNull() ?: 0) }
    var allSheets by rememberSaveable(document.sha256) { mutableStateOf(document.sheets == null) }
    var selectedSheets by rememberSaveable(document.sha256) { mutableStateOf(ArrayList(document.sheets.orEmpty())) }
    var previewSheet by rememberSaveable(document.sha256) { mutableStateOf(document.sheets?.firstOrNull().orEmpty()) }
    var previewText by remember(document.sha256) { mutableStateOf("") }
    var textLoading by remember(document.sha256) { mutableStateOf(false) }
    var textReady by remember(document.sha256) { mutableStateOf(false) }
    var textError by remember(document.sha256) { mutableStateOf<String?>(null) }
    var bitmap by remember(document.sha256) { mutableStateOf<Bitmap?>(null) }
    var thumbnailLoading by remember(document.sha256) { mutableStateOf(false) }
    var thumbnailError by remember(document.sha256) { mutableStateOf<String?>(null) }
    var confirming by remember(document.sha256) { mutableStateOf(false) }
    var confirmationError by remember(document.sha256) { mutableStateOf<String?>(null) }

    LaunchedEffect(document.sha256, document.name) {
        loading = true
        errorKey = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val original = DocumentStore(context).file(document)
                val count = if (pdf) PdfDocumentSelection.pageCount(original) else 0
                if (pdf && count !in 1..1000) throw DocumentProblem("documentSelection.pageLimit")
                Triple(original, count, if (workbook) LocalDocumentText.sheets(original) { job.ensureActive() } else emptyList())
            }
            file = loaded.first
            pageCount = loaded.second
            sheetNames = loaded.third
            if (pdf) currentPage = currentPage.coerceIn(0, pageCount - 1)
            if (workbook && previewSheet !in sheetNames) previewSheet = sheetNames.firstOrNull().orEmpty()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { errorKey = (error as? DocumentProblem)?.messageKey ?: "document.readFailed" }
        finally { loading = false }
    }

    LaunchedEffect(file, currentPage) {
        val original = file ?: return@LaunchedEffect
        if (!pdf) return@LaunchedEffect
        bitmap = null
        thumbnailError = null
        thumbnailLoading = true
        // Only an unpublished result is recycled manually. Compose may still draw a retired bitmap.
        val unpublished = AtomicReference<Bitmap?>()
        try {
            val rendered = withContext(Dispatchers.IO) {
                PdfDocumentSelection.thumbnail(original, currentPage).also(unpublished::set)
            }
            bitmap = rendered
            unpublished.set(null)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { thumbnailError = (error as? DocumentProblem)?.messageKey ?: "documentSelection.previewFailed" }
        finally { unpublished.getAndSet(null)?.recycle(); thumbnailLoading = false }
    }

    LaunchedEffect(file, previewSheet) {
        val original = file ?: return@LaunchedEffect
        if (!plainText && !workbook) return@LaunchedEffect
        if (workbook && previewSheet.isEmpty()) return@LaunchedEffect
        previewText = ""
        textReady = false
        textError = null
        textLoading = true
        try {
            previewText = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                LocalDocumentText.read(original, document.name,
                    selectedSheets = if (workbook) listOf(previewSheet) else null,
                    check = { job.ensureActive() }).take(6000)
            }
            textReady = true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { textError = (error as? DocumentProblem)?.messageKey ?: "documentSelection.previewFailed" }
        finally { textLoading = false }
    }

    val selectedPages = if (allPages && pageCount > 0) (0 until pageCount).toList() else parseDocumentPages(ranges, pageCount)
    val chosenSheets = if (allSheets) sheetNames else selectedSheets.filter { it in sheetNames }
    val valid = !loading && !confirming && errorKey == null && when {
        pdf -> !selectedPages.isNullOrEmpty()
        workbook -> chosenSheets.isNotEmpty() && (allSheets ||
            (selectedSheets.size <= 256 && selectedSheets.all { it in sheetNames && it.length <= 128 }))
        plainText -> textReady && !textLoading && textError == null
        else -> true
    }
    Dialog(onDismissRequest = { if (!confirming) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !confirming, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s["documentSelection.title"], style = MaterialTheme.typography.titleLarge)
                Text(document.name, style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text(s["documentSelection.originalKept"], style = MaterialTheme.typography.bodySmall) }
                    if (loading) item { CircularProgressIndicator() }
                    errorKey?.let { key -> item { Text(s[key], color = MaterialTheme.colorScheme.error) } }
                    if (!loading && errorKey == null && pdf) {
                        item {
                            Text(s["documentSelection.pdfHint"], style = MaterialTheme.typography.bodySmall)
                            Text(s.format("documentSelection.pageCount", "n" to (selectedPages?.size ?: 0).toString(), "total" to pageCount.toString()))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { allPages = true; ranges = "" }) { Text(s["documentSelection.all"]) }
                                TextButton(onClick = { allPages = false; ranges = "" }) { Text(s["documentSelection.clear"]) }
                            }
                            OutlinedTextField(ranges, onValueChange = { if (it.length <= 20000) { ranges = it; allPages = false } },
                                label = { Text(s["documentSelection.range"]) }, placeholder = { Text("1-3,5") },
                                isError = !allPages && selectedPages == null, modifier = Modifier.fillMaxWidth())
                            if (!allPages && selectedPages == null) Text(s["documentSelection.invalidRange"], color = MaterialTheme.colorScheme.error)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = currentPage in selectedPages.orEmpty(), enabled = selectedPages != null,
                                    onCheckedChange = { checked ->
                                        val next = selectedPages.orEmpty().toMutableSet()
                                        if (checked) next += currentPage else next -= currentPage
                                        ranges = pageRanges(next.toList()); allPages = false
                                    })
                                Text(s.format("documentSelection.selectPage", "n" to (currentPage + 1).toString()))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { currentPage-- }, enabled = currentPage > 0) { Text(s["documentSelection.previous"]) }
                                Text("${currentPage + 1} / $pageCount", Modifier.padding(top = 12.dp))
                                TextButton(onClick = { currentPage++ }, enabled = currentPage + 1 < pageCount) { Text(s["documentSelection.next"]) }
                            }
                        }
                        if (thumbnailLoading) item { CircularProgressIndicator() }
                        thumbnailError?.let { key -> item { Text(s[key], color = MaterialTheme.colorScheme.error) } }
                        bitmap?.let { preview -> item {
                            Image(preview.asImageBitmap(), contentDescription = s.format("documentSelection.pagePreview", "n" to (currentPage + 1).toString()),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp), contentScale = ContentScale.Fit)
                        } }
                    }
                    if (!loading && errorKey == null && workbook) {
                        item {
                            Text(s.format("documentSelection.sheetCount", "n" to chosenSheets.size.toString(), "total" to sheetNames.size.toString()))
                            Text(s["documentSelection.sheetHint"], style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { allSheets = true; selectedSheets = arrayListOf(); confirmationError = null }, enabled = !confirming) { Text(s["documentSelection.all"]) }
                                TextButton(onClick = { allSheets = false; selectedSheets = arrayListOf(); confirmationError = null }, enabled = !confirming) { Text(s["documentSelection.clear"]) }
                            }
                        }
                        items(sheetNames, key = { "sheet:$it" }) { name ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = name in chosenSheets, enabled = !confirming, onCheckedChange = { checked ->
                                    val nextSize = chosenSheets.size + if (checked) 1 else -1
                                    if (nextSize > 256 || name.length > 128) {
                                        confirmationError = "documentSelection.sheetLimit"
                                    } else {
                                        val next = chosenSheets.toMutableSet()
                                        if (checked) next += name else next -= name
                                        selectedSheets = ArrayList(sheetNames.filter { it in next }); allSheets = false; confirmationError = null
                                    }
                                })
                                Text(name, Modifier.weight(1f))
                                TextButton(onClick = { previewSheet = name }, enabled = !confirming) { Text(s["documentSelection.preview"]) }
                            }
                        }
                    }
                    if (!loading && errorKey == null && (workbook || plainText)) {
                        item {
                            if (workbook) Text(previewSheet, style = MaterialTheme.typography.titleSmall)
                            Text(s["documentSelection.textPreviewHint"], style = MaterialTheme.typography.bodySmall)
                            if (textLoading) CircularProgressIndicator()
                            textError?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                            if (!textLoading && textError == null) Text(previewText.ifEmpty { s["documentSelection.emptyPreview"] })
                        }
                    }
                    if (!loading && errorKey == null && !pdf && !workbook && !plainText) item {
                        Text(s["documentSelection.wholeFile"])
                    }
                }
                if (!loading && errorKey == null && ((pdf && selectedPages.isNullOrEmpty()) || (workbook && chosenSheets.isEmpty()))) {
                    Text(s["documentSelection.selectRequired"], color = MaterialTheme.colorScheme.error)
                }
                confirmationError?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                if (workbook && !allSheets && selectedSheets.any { it !in sheetNames } && !loading) {
                    Text(s["document.missingSheets"], color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !confirming) { Text(s["common.cancel"]) }
                    Button(onClick = {
                        val selected = document.copy(pages = if (pdf && !allPages) selectedPages else null,
                            sheets = if (workbook && !allSheets) chosenSheets else null)
                        if (workbook) {
                            val original = file ?: return@Button
                            confirming = true
                            confirmationError = null
                            scope.launch {
                                try {
                                    // Validate the complete chosen scope, not just the displayed single-sheet excerpt.
                                    withContext(Dispatchers.IO) {
                                        val job = currentCoroutineContext()
                                        LocalDocumentText.read(original, selected.name, selected.sheets) { job.ensureActive() }
                                    }
                                    onConfirm(selected)
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { confirmationError = (error as? DocumentProblem)?.messageKey ?: "documentSelection.previewFailed" }
                                finally { confirming = false }
                            }
                        } else onConfirm(selected)
                    }, enabled = valid) { Text(s[if (confirming) "documentSelection.checking" else "documentSelection.confirm"]) }
                }
            }
        }
    }
}
