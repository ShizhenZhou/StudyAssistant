package com.zsz.studyassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebView
import android.util.Base64
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

internal suspend fun prepareConversationExport(context: Context, view: WebView, messages: List<ChatMsg>,
    format: ConversationExportFormat): Int {
    check(messages.sumOf { m -> m.content.length.toLong() + (m.quotedText?.length ?: 0) + m.images.sumOf { it.length.toLong() } } <= 16L * 1024 * 1024)
    val payload = withContext(Dispatchers.Default) { buildMessagesJson(messages) }
    val script = withContext(Dispatchers.IO) { context.assets.open("conversation_export.js").bufferedReader().use { it.readText() } }
    return withTimeout(60_000) {
        view.exportScript(script)
        check(view.exportScript("prepareConversationExport($payload, '${format.name}')") == "true")
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
}

/** Capture only the attached, visible viewport, one page at a time; never draw a tall WebView. */
internal suspend fun renderConversationExport(context: Context, view: WebView, messages: List<ChatMsg>,
    format: ConversationExportFormat, name: String, progress: (Int, Int) -> Unit): ConversationExportResult {
    val directory = File(context.cacheDir, "conversation-export/${UUID.randomUUID()}")
    check(directory.mkdirs())
    var delivered = false
    try {
        if (format == ConversationExportFormat.PDF) {
            val file = renderNativeConversationPdf(context, view, messages, directory, name)
            currentCoroutineContext().ensureActive()
            delivered = true
            return ConversationExportResult(directory, listOf(file), name, format)
        }
        val width = view.width; val height = view.height
        check(width > 0 && height > 0 && view.isAttachedToWindow)
        val count = prepareConversationExport(context, view, messages, format)
        val files = mutableListOf<File>()
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
                    val file = File(directory, "${name}_${(page + 1).toString().padStart(3, '0')}.png")
                    file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    files.add(file)
                }
            } finally { bitmap.recycle() }
            yield()
        }
        currentCoroutineContext().ensureActive()
        delivered = true
        return ConversationExportResult(directory, files, name, format)
    } finally {
        if (!delivered) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
    }
}

/** WebView only parses sanitized Markdown and rasterizes isolated formulas; native code owns pagination. */
private suspend fun renderNativeConversationPdf(context: Context, view: WebView, messages: List<ChatMsg>, directory: File, name: String): File {
    // Screen accessibility zoom must not affect the isolated formula metrics on paper.
    view.settings.textZoom = 100
    check(messages.sumOf { it.content.length.toLong() + (it.quotedText?.length ?: 0) + it.images.sumOf { image -> image.length.toLong() } } <= 16L*1024*1024)
    val payload = withContext(Dispatchers.Default) { buildMessagesJson(messages) }
    val (script, css) = withContext(Dispatchers.IO) {
        val css = context.assets.open("katex/katex.min.css").bufferedReader().use { it.readText() }
        val embedded = Regex("url\\(fonts/([^)]+)\\)").replace(css) { match ->
            val bytes = context.assets.open("katex/fonts/${match.groupValues[1]}").use { it.readBytes() }
            "url(data:font/woff2;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)})"
        } + ".katex{font-size:1.1em;}"
        context.assets.open("conversation_pdf.js").bufferedReader().use { it.readText() } to embedded
    }
    return withTimeout(120_000) {
        view.exportScript(script)
        check(view.exportScript("prepareConversationPdf($payload,${JSONObject.quote(css)})") == "true")
        while (true) {
            ensureActive()
            val state = JSONObject(view.exportScript("window.conversationPdfState"))
            if (state.getString("status") == "error") error("PDF content preparation failed")
            if (state.getString("status") == "ready") { check(state.getInt("count") == messages.size);break }
            delay(50)
        }
        val writer = ConversationPdfWriter()
        try {
            for (index in messages.indices) {
                ensureActive()
                val json = view.exportScript("conversationPdfMessage($index)")
                withContext(Dispatchers.Default) { writer.append(JSONObject(json)) }
            }
            val file = File(directory,"$name.pdf")
            withContext(Dispatchers.IO) { file.outputStream().buffered().use(writer::writeTo) }
            file
        } finally { writer.close() }
    }
}

/** PDF uses the user's CreateDocument destination; PNG gets a uniquely created folder. */
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
        check(result.files.size == 1)
        copy(result.files.single(), destination)
        return@withContext
    }
    run {
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
