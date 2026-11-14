package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zsz.studyassistant.data.AiFiles
import com.zsz.studyassistant.data.AiFileSession
import com.zsz.studyassistant.data.DocumentProblem
import com.zsz.studyassistant.data.DocumentRecognition
import com.zsz.studyassistant.data.OcrResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one immutable crop. No request starts until the fee notice's explicit start action. */
@Composable
internal fun OcrCorrectionDialog(imageBytes: ByteArray, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Keep raw page markup separate from the editable text throughout the correction session.
    var rawResult by remember { mutableStateOf<OcrResult?>(null) }
    var text by remember { mutableStateOf("") }
    var errorKey by remember { mutableStateOf<String?>(null) }
    var reviewedIncomplete by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var cacheKey by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<AiFileSession?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }
    var cacheFailed by remember { mutableStateOf(false) }
    var retryAfter by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(imageBytes) {
        try {
            val current = withContext(Dispatchers.IO) { AiFiles.session() }
            session = current
            val key = withContext(Dispatchers.Default) {
                OcrDraftCache.key(imageBytes, current.accountScope, DocumentRecognition.TEXT)
            }
            val draft = OcrDraftCache.read(context, key)
            cacheKey = key
            if (draft != null) {
                rawResult = draft.result
                text = draft.text
                retryAfter = draft.retryAfter
                errorKey = draft.errorKey
                restored = draft.result != null
                // Truncated text requires a fresh, explicit review even after restoring a draft.
                reviewedIncomplete = false
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (session == null) errorKey = "err.apikey" else cacheFailed = true
        }
        finally { restoring = false }
    }
    LaunchedEffect(retryAfter) {
        do {
            now = System.currentTimeMillis()
            if (now >= retryAfter) break
            kotlinx.coroutines.delay(1000)
        } while (true)
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, imageBytes) {
        var decoded: android.graphics.Bitmap? = null
        try {
            withContext(Dispatchers.Default) {
                decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            }
            value = decoded
            awaitDispose { decoded?.recycle() }
        } finally {
            // Covers cancellation while the background decoder is returning.
            if (value == null) decoded?.recycle()
        }
    }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(300)
        previewText = text
    }
    val incomplete = rawResult?.complete == false
    val tooLarge = text.length > MAX_OCR_CORRECTION_CHARS
    val canConfirm = rawResult != null && !busy && !restoring && errorKey == null && !tooLarge &&
        text.isNotBlank() && (!incomplete || reviewedIncomplete)
    val waitSeconds = ((retryAfter - now + 999) / 1000).coerceAtLeast(0)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
                Text(s["ocr.title"], style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(2.dp))
                    Text(s["ocr.original"], style = MaterialTheme.typography.labelLarge)
                    bitmap?.let { image ->
                        Image(image.asImageBitmap(), s["ocr.original"],
                            Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
                    }
                    Text(s["ocr.feeNotice"], style = MaterialTheme.typography.bodySmall)
                    if (restoring) Text(s["ocr.restoring"], style = MaterialTheme.typography.bodySmall)
                    if (restored) Text(s["ocr.restored"], style = MaterialTheme.typography.bodySmall)
                    if (cacheFailed) Text(s["ocr.cacheFailed"], color = MaterialTheme.colorScheme.error)
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(s["ocr.busy"])
                    }
                    errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                    if (!busy && waitSeconds > 0) Text(
                        s.format("ocr.retryWait", "seconds" to waitSeconds.toString()),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (rawResult != null) {
                        if (incomplete) {
                            Text(s["ocr.incomplete"], color = MaterialTheme.colorScheme.error)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(reviewedIncomplete, onCheckedChange = { reviewedIncomplete = it })
                                Text(s["ocr.reviewedIncomplete"], style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(value = text, onValueChange = {
                            text = it
                            reviewedIncomplete = false
                            cacheKey?.let { key ->
                                // Do not retain arbitrarily large pasted input in the private cache.
                                if (it.length <= MAX_OCR_CORRECTION_CHARS) OcrDraftCache.saveEdit(
                                    context, key, OcrDraft(result = rawResult, text = it)
                                ) { cacheFailed = true }
                            }
                        }, enabled = !busy && errorKey != "ocr.tooLarge",
                            label = { Text(s["ocr.text"]) }, minLines = 5, maxLines = 12,
                            modifier = Modifier.fillMaxWidth())
                        if (tooLarge) Text(s["ocr.tooLarge"], color = MaterialTheme.colorScheme.error)
                        Text(s["ocr.preview"], style = MaterialTheme.typography.labelLarge)
                        if (!tooLarge && previewText.isNotBlank()) ConversationWebView(
                            messages = listOf(ChatMsg("assistant", previewText)),
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(s["common.cancel"]) }
                    if (rawResult == null) {
                        Button(enabled = !busy && !restoring && cacheKey != null && waitSeconds == 0L,
                            onClick = {
                            if (busy || System.currentTimeMillis() < retryAfter) return@Button
                            val key = cacheKey ?: return@Button
                            val current = session ?: return@Button
                            busy = true
                            errorKey = null
                            cacheFailed = false
                            reviewedIncomplete = false
                            scope.launch {
                                var original: File? = null
                                var requestStarted = false
                                try {
                                    withContext(Dispatchers.IO) {
                                        original = File.createTempFile("ocr_crop_", ".jpg", context.cacheDir)
                                        checkNotNull(original).writeBytes(imageBytes)
                                    }
                                    retryAfter = System.currentTimeMillis() + OcrDraftCache.REQUEST_WINDOW_MS
                                    // Persist before sending. A lost/cancelled response must not enable a paid retry.
                                    withContext(NonCancellable) {
                                        OcrDraftCache.write(context, key, OcrDraft(retryAfter = retryAfter))
                                    }
                                    requestStarted = true
                                    val result = current.recognize(checkNotNull(original), DocumentRecognition.TEXT)
                                    // Once a paid result arrives, closing the dialog must not discard it.
                                    withContext(NonCancellable) {
                                        val editable = withContext(Dispatchers.Default) { editableOcrText(result) }
                                        rawResult = result
                                        retryAfter = 0
                                        if (editable.length > MAX_OCR_CORRECTION_CHARS) errorKey = "ocr.tooLarge"
                                        else text = editable
                                        OcrDraftCache.write(context, key,
                                            OcrDraft(result = result, text = text, errorKey = errorKey))
                                    }
                                } catch (error: CancellationException) {
                                    if (requestStarted && rawResult == null) {
                                        retryAfter = System.currentTimeMillis() + OcrDraftCache.REQUEST_WINDOW_MS
                                        withContext(NonCancellable) {
                                            try { OcrDraftCache.write(context, key,
                                                OcrDraft(retryAfter = retryAfter)) }
                                            catch (_: Exception) { cacheFailed = true }
                                        }
                                    }
                                    throw error
                                } catch (error: Exception) {
                                    if (!requestStarted || rawResult != null) cacheFailed = true
                                    else {
                                        errorKey = (error as? DocumentProblem)?.messageKey ?: "ocr.failed"
                                        // A timeout can arrive after the original deadline; the remote outcome is still unknown.
                                        if ((error as? DocumentProblem)?.uploadRejected != true) {
                                            retryAfter = System.currentTimeMillis() + OcrDraftCache.REQUEST_WINDOW_MS
                                        }
                                        withContext(NonCancellable) {
                                            try { OcrDraftCache.write(context, key,
                                                OcrDraft(retryAfter = retryAfter, errorKey = errorKey)) }
                                            catch (_: Exception) { cacheFailed = true }
                                        }
                                    }
                                }
                                finally {
                                    withContext(NonCancellable + Dispatchers.IO) { original?.delete() }
                                    busy = false
                                }
                            }
                        }) { Text(s[if (retryAfter > 0 || errorKey != null) "ocr.retry" else "ocr.start"]) }
                    } else {
                        Button(enabled = canConfirm, onClick = { onConfirm(text.trim()) }) {
                            Text(s["ocr.confirm"])
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_OCR_CORRECTION_CHARS = 250_000

/** Strip only known layout metadata. Formula delimiters and unknown markup remain editable. */
internal fun editableOcrText(result: OcrResult): String = result.pages.joinToString("\n\n") { page ->
    page.text
        .replace(Regex("<[|｜](?:det|box)[|｜]>\\s*\\[[-+0-9.eE,\\s\\[\\]]*]\\s*<[|｜]/(?:det|box)[|｜]>"), "")
        .replace(Regex("<[|｜](?:/?ref|/?det|grounding|loc_\\d+)[|｜]>"), "")
        .trim()
}
