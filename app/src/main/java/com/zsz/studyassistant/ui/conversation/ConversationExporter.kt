package com.zsz.studyassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class ConversationExportFormat(val width: Int, val height: Int) {
    PDF(1190, 1684), PNG(1080, 1920)
}

internal data class ConversationExportResult(val directory: File, val files: List<File>, val name: String,
    val format: ConversationExportFormat)

internal suspend fun WebView.exportScript(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "null") }
}

private suspend fun WebView.awaitExportPaint() = withTimeout(15_000) {
    suspendCancellableCoroutine<Unit> { continuation ->
        postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }
}

/** Capture only the attached, visible viewport, one page at a time; never draw a tall WebView. */
internal suspend fun renderConversationExport(context: Context, view: WebView, messages: List<ChatMsg>,
    format: ConversationExportFormat, name: String, progress: (Int, Int) -> Unit): ConversationExportResult {
    val directory = File(context.cacheDir, "conversation-export/${UUID.randomUUID()}")
    check(directory.mkdirs())
    var delivered = false
    try {
        check(messages.sumOf { m -> m.content.length.toLong() + (m.quotedText?.length ?: 0) + m.images.sumOf { it.length.toLong() } } <= 16L * 1024 * 1024)
        val payload = withContext(Dispatchers.Default) { buildMessagesJson(messages) }
        val script = withContext(Dispatchers.IO) { context.assets.open("conversation_export.js").bufferedReader().use { it.readText() } }
        val width = view.width; val height = view.height
        check(width > 0 && height > 0 && view.isAttachedToWindow)
        val count = withTimeout(60_000) {
            view.exportScript(script)
            check(view.exportScript("prepareConversationExport($payload)") == "true")
            var total = 0
            while (total == 0) {
                ensureActive()
                val state = JSONObject(view.exportScript("window.conversationExportState"))
                when (state.getString("status")) {
                    "error" -> error("Cannot paginate export")
                    "ready" -> total = state.getInt("pages").also { check(it in 1..200) }
                    else -> delay(100)
                }
            }
            total
        }
        val files = mutableListOf<File>()
        val pdfFile = File(directory, "$name.pdf")
        val pdf = if (format == ConversationExportFormat.PDF) withContext(Dispatchers.IO) { ConversationImagePdf(pdfFile) } else null
        try {
            for (page in 0 until count) {
                currentCoroutineContext().ensureActive()
                check(view.width == width && view.height == height && view.isAttachedToWindow)
                progress(page + 1, count)
                withTimeout(15_000) { check(view.exportScript("showConversationExportPage($page)") == "true") }
                view.awaitExportPaint()
                val bitmap = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.scale(format.width.toFloat() / width, format.height.toFloat() / height)
                    view.draw(canvas)
                    withContext(Dispatchers.IO) {
                        if (pdf != null) {
                            val jpeg = ByteArrayOutputStream().use { out ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)); out.toByteArray()
                            }
                            pdf.addPage(jpeg, format.width, format.height)
                        } else {
                            val file = File(directory, "${name}_${(page + 1).toString().padStart(3, '0')}.png")
                            file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            files.add(file)
                        }
                    }
                } finally { bitmap.recycle() }
                yield()
            }
            if (pdf != null) withContext(Dispatchers.IO) { pdf.finish(); files.add(pdfFile) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { pdf?.close() } }
        currentCoroutineContext().ensureActive()
        delivered = true
        return ConversationExportResult(directory, files, name, format)
    } finally {
        if (!delivered) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
    }
}

/** Only writes the user-picked PDF or brand-new files in our uniquely created PNG folder. */
internal suspend fun saveConversationExport(context: Context, result: ConversationExportResult, destination: Uri) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    suspend fun copy(file: File, uri: Uri) {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openOutputStream(uri, "wt").use { output ->
            checkNotNull(output)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); digest.update(buffer, 0, n)
                }
            }
        }
        val expected = digest.digest()
        val actual = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri).use { input ->
            checkNotNull(input); val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive(); val n = input.read(buffer); if (n < 0) break
                actual.update(buffer, 0, n)
            }
        }
        check(expected.contentEquals(actual.digest()))
    }
    if (result.format == ConversationExportFormat.PDF) {
        resolver.query(destination, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) check(cursor.getLong(0) == 0L) { "Refuse to overwrite existing PDF" }
        }
        copy(result.files.single(), destination)
    } else {
        val treeId = DocumentsContract.getTreeDocumentId(destination)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(destination, treeId)
        val existingIds = mutableSetOf<String>(); val names = mutableSetOf<String>()
        checkNotNull(resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { cursor ->
            while (cursor.moveToNext()) { existingIds.add(cursor.getString(0)); names.add(cursor.getString(1)) }
        }
        var name = result.name; var suffix = 2
        while (name in names) name = "${result.name}_${suffix++}"
        val folder = checkNotNull(DocumentsContract.createDocument(resolver,
            DocumentsContract.buildDocumentUriUsingTree(destination, treeId), DocumentsContract.Document.MIME_TYPE_DIR, name))
        check(DocumentsContract.getDocumentId(folder) !in existingIds)
        try {
            result.files.forEach { file ->
                ensureActive()
                val uri = checkNotNull(DocumentsContract.createDocument(resolver, folder, "image/png", file.name))
                copy(file, uri)
            }
        } catch (error: Exception) {
            // This folder was newly created by this operation. Existing exports are never removed.
            runCatching { DocumentsContract.deleteDocument(resolver, folder) }
            throw error
        }
    }
}
