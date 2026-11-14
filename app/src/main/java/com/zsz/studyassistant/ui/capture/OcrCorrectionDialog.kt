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
    val canConfirm = rawResult != null && !busy && errorKey == null && !tooLarge &&
        text.isNotBlank() && (!incomplete || reviewedIncomplete)

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
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(s["ocr.busy"])
                    }
                    errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
                    if (rawResult != null && errorKey == null) {
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
                        }, label = { Text(s["ocr.text"]) }, minLines = 5, maxLines = 12,
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
                    if (rawResult == null || errorKey != null) {
                        Button(enabled = !busy && errorKey != "ocr.tooLarge", onClick = {
                            if (busy) return@Button
                            busy = true
                            errorKey = null
                            rawResult = null
                            reviewedIncomplete = false
                            scope.launch {
                                var original: File? = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        original = File.createTempFile("ocr_crop_", ".jpg", context.cacheDir)
                                        checkNotNull(original).writeBytes(imageBytes)
                                    }
                                    val result = AiFiles.session().recognize(checkNotNull(original), DocumentRecognition.TEXT)
                                    rawResult = result
                                    val editable = withContext(Dispatchers.Default) { editableOcrText(result) }
                                    if (editable.length > MAX_OCR_CORRECTION_CHARS) errorKey = "ocr.tooLarge"
                                    else text = editable
                                } catch (error: CancellationException) { throw error }
                                catch (_: Exception) { errorKey = "ocr.failed" }
                                finally {
                                    withContext(NonCancellable + Dispatchers.IO) { original?.delete() }
                                    busy = false
                                }
                            }
                        }) { Text(s["ocr.start"]) }
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
