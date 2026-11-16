package com.zsz.studyassistant.ui

import android.graphics.Color
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable
internal fun conversationExportViewModel(): ConversationExportViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = checkNotNull(
        androidx.activity.compose.LocalActivity.current as? androidx.lifecycle.ViewModelStoreOwner))

/** Compatibility entry point for callers that open a dialog with a fixed selection. */
@Composable
internal fun ConversationExportDialog(messages: List<ChatMsg>, onDismiss: () -> Unit, onFeedback: (String) -> Unit) {
    val export = conversationExportViewModel()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { export.begin(messages).join(); started = true }
    ConversationExportDialog(export, onFeedback)
    LaunchedEffect(started, export.session) { if (started && export.session == null) onDismiss() }
}

/** Always mounted by SolveScreen, so SAF callbacks remain registered through every export phase. */
@Composable
internal fun ConversationExportDialog(export: ConversationExportViewModel, onFeedback: (String) -> Unit) {
    val s = LocalStrings.current
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), export::destination)
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), export::destination)
    LaunchedEffect(export.feedback) {
        export.feedback?.let { onFeedback(s[it]); export.consumeFeedback() }
    }
    val state = export.session ?: return
    LaunchedEffect(state.id, state.phase) {
        if (state.phase == ExportPhase.READY && export.picking()) {
            try {
                if (state.format == ConversationExportFormat.PDF) pdfPicker.launch("${state.name}.pdf")
                else pngPicker.launch(null)
            } catch (_: Exception) { export.fail(state.id) }
        }
    }
    val messages = export.messages
    if (state.phase == ExportPhase.RENDERING && messages != null) {
        ConversationExportRenderer(messages, state.format, state.name, export::cancel,
            onReady = { export.prepared(state.id, it) }, onError = { export.fail(state.id) })
    } else if (state.phase in listOf(ExportPhase.SNAPSHOT, ExportPhase.RENDERING, ExportPhase.STORING, ExportPhase.SAVING)) {
        val saving = state.phase == ExportPhase.SAVING
        AlertDialog(onDismissRequest = { if (!saving) export.cancel() },
            title = { Text(s[if (saving) "export.saving" else "export.preparing"]) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {},
            dismissButton = { if (!saving) TextButton(onClick = export::cancel) { Text(s["common.cancel"]) } })
    } else if (state.phase == ExportPhase.OPTIONS) {
        AlertDialog(onDismissRequest = export::cancel, title = { Text(s["export.title"]) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConversationExportFormat.entries.forEach { option ->
                        Row(Modifier.weight(1f).clickable { export.edit(format = option) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.format == option, onClick = { export.edit(format = option) }); Text(option.name)
                        }
                    }
                }
                Text(s[if (state.format == ConversationExportFormat.PDF) "export.pdfHint" else "export.pngHint"], style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.filename, onValueChange = { export.edit(filename = it.take(160)) }, singleLine = true,
                    label = { Text(s["export.filename"]) }, placeholder = { Text(state.fallback) }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            Button(onClick = export::render) { Text(s["export.action"]) }
        }, dismissButton = { TextButton(onClick = export::cancel) { Text(s["common.cancel"]) } })
    } else if (state.phase == ExportPhase.PICKER) {
        // Also offers a way forward after a cold launch where Android no longer owns a pending picker.
        AlertDialog(onDismissRequest = export::cancel, title = { Text(s["export.title"]) },
            text = { Text(state.name) },
            confirmButton = { TextButton(onClick = export::chooseDestinationAgain) { Text(s["export.action"]) } },
            dismissButton = { TextButton(onClick = export::cancel) { Text(s["common.cancel"]) } })
    }
}

@Composable
private fun ConversationExportRenderer(messages: List<ChatMsg>, format: ConversationExportFormat, name: String,
    onDismiss: () -> Unit, onReady: (ConversationExportResult) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(view, loaded, failed) {
        if (failed) { onError(); return@LaunchedEffect }
        val web = view ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        try {
            withTimeout(15_000) { while (web.width <= 0 || web.height <= 0 || !web.isAttachedToWindow) delay(32) }
            onReady(renderConversationExport(context, web, messages, format, name) { current, total -> page = current; count = total })
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException) onError() else throw error
        } catch (_: Exception) { onError() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(16.dp).widthIn(max = 340.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (count == 0) s["export.preparing"] else s.format("export.progress", "i" to "$page", "n" to "$count"))
                if (format == ConversationExportFormat.PDF) LinearProgressIndicator(Modifier.fillMaxWidth())
                BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = format.width.toFloat() / format.height
                    val previewWidth = minOf(maxWidth, maxHeight * ratio)
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            configureConversationWebView(this)
                            setBackgroundColor(Color.WHITE)
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                            setOnTouchListener { _, _ -> true }
                            webViewClient = object : ConversationAssetClient() {
                                override fun onPageFinished(v: WebView?, url: String?) { loaded = true }
                                override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean { failed = true; return true }
                            }
                            view = this
                            loadUrl(CONVERSATION_ASSET_URL)
                        }
                    }, onRelease = { web ->
                        if (view === web) view = null
                        if (!failed) web.stopLoading()
                        (web.parent as? android.view.ViewGroup)?.removeView(web)
                        web.destroy()
                    }, modifier = if (format == ConversationExportFormat.PDF) Modifier.fillMaxWidth().height(1.dp).alpha(0f)
                        else Modifier.width(previewWidth).aspectRatio(ratio))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(s["common.cancel"]) }
            }
        }
    }
}
