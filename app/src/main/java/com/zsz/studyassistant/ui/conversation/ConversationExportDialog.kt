package com.zsz.studyassistant.ui

import android.graphics.Color
import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

@Composable
internal fun ConversationExportDialog(messages: List<ChatMsg>, onDismiss: () -> Unit, onFeedback: (String) -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val fallback = remember { "StudyAssistant-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) }
    var filename by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ConversationExportFormat.PDF) }
    var rendering by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<ConversationExportResult?>(null) }
    var safeName by remember { mutableStateOf(fallback) }
    DisposableEffect(prepared) {
        val result = prepared
        onDispose { result?.directory?.deleteRecursively() }
    }
    fun save(uri: Uri?) {
        val result = prepared ?: return
        if (uri == null) { onDismiss(); return }
        saving = true
        scope.launch {
            try {
                saveConversationExport(context, result, uri)
                onFeedback(s["export.success"])
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { onFeedback(s["export.failed"]) }
            finally { saving = false; onDismiss() }
        }
    }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { save(it) }

    if (rendering) {
        ConversationExportRenderer(messages, format, safeName, onDismiss, onReady = { result ->
            prepared = result; rendering = false
            try {
                pngPicker.launch(null)
            } catch (_: Exception) { onFeedback(s["export.failed"]); onDismiss() }
        }, onError = { onFeedback(s["export.failed"]); onDismiss() })
    } else if (saving) {
        AlertDialog(onDismissRequest = {}, title = { Text(s["export.saving"]) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    } else if (prepared == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(s["export.title"]) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConversationExportFormat.entries.forEach { option ->
                        Row(Modifier.weight(1f).clickable { format = option }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = format == option, onClick = { format = option }); Text(option.name)
                        }
                    }
                }
                Text(s[if (format == ConversationExportFormat.PDF) "export.pdfHint" else "export.pngHint"], style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = filename, onValueChange = { filename = it.take(160) }, singleLine = true,
                    label = { Text(s["export.filename"]) }, placeholder = { Text(fallback) }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            Button(onClick = { safeName = conversationExportName(filename, fallback); rendering = true }) { Text(s["export.action"]) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(s["common.cancel"]) } })
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
    var printing by remember { mutableStateOf(false) }
    var printSession by remember { mutableStateOf<ConversationPdfPrint?>(null) }
    LaunchedEffect(view, loaded, failed) {
        if (failed) { onError(); return@LaunchedEffect }
        val web = view ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        try {
            withTimeout(15_000) { while (web.width <= 0 || web.height <= 0 || !web.isAttachedToWindow) delay(32) }
            if (format == ConversationExportFormat.PDF) {
                prepareConversationExport(context, web, messages, format)
                printing = true
                val session = ConversationPdfPrint(web, name, onDismiss)
                printSession = session
                session.start(context, name)
            } else {
                onReady(renderConversationExport(context, web, messages, format, name) { current, total -> page = current; count = total })
            }
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException) onError() else throw error
        } catch (_: Exception) { onError() }
    }
    Dialog(onDismissRequest = { if (!printing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(16.dp).widthIn(max = 340.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (printing) s["export.pdfSaving"] else if (count == 0) s["export.preparing"] else s.format("export.progress", "i" to "$page", "n" to "$count"))
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
                        printSession?.close()
                        printSession = null
                        if (view === web) view = null
                        if (!failed) web.stopLoading()
                        (web.parent as? android.view.ViewGroup)?.removeView(web)
                        web.destroy()
                    }, modifier = Modifier.width(previewWidth).aspectRatio(ratio))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(s["common.cancel"]) }
            }
        }
    }
}
