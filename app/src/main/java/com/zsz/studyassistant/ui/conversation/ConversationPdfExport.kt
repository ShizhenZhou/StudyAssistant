package com.zsz.studyassistant.ui

import android.content.Context
import android.webkit.WebView
import android.util.Base64
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** WebView only parses sanitized Markdown and rasterizes isolated formulas; native code owns pagination. */
internal suspend fun renderNativeConversationPdf(context: Context, view: WebView, messages: List<ChatMsg>, directory: File, name: String): File {
    // Screen accessibility zoom must not affect the isolated formula metrics on paper.
    view.settings.textZoom = 100
    check(messages.sumOf { it.content.length.toLong() + (it.quotedText?.length ?: 0) + it.images.sumOf { image -> image.length.toLong() } } <= 16L*1024*1024)
    val payload = withContext(Dispatchers.Default) { buildMessagesJson(messages) }
    val (script, css) = withContext(Dispatchers.IO) { PdfExportAssets.load(context) }
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
                val json = view.exportScript("conversationPdfTakeMessage($index)")
                withContext(Dispatchers.Default) { writer.append(JSONObject(json)) }
            }
            val file = File(directory,"$name.pdf")
            withContext(Dispatchers.IO) { file.outputStream().buffered().use(writer::writeTo) }
            file
        } finally { writer.close() }
    }
}

private object PdfExportAssets {
    // APK-bundled resources are immutable for this process. No user content is cached here.
    @Volatile private var cached: Pair<String, String>? = null
    fun load(context: Context): Pair<String, String> = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }
    private fun read(context: Context): Pair<String, String> {
        val css = context.assets.open("katex/katex.min.css").bufferedReader().use { it.readText() }
        val embedded = Regex("url\\(fonts/([^)]+)\\)").replace(css) { match ->
            val bytes = context.assets.open("katex/fonts/${match.groupValues[1]}").use { it.readBytes() }
            "url(data:font/woff2;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)})"
        } + ".katex{font-size:1.1em;}"
        return context.assets.open("conversation_pdf.js").bufferedReader().use { it.readText() } to embedded
    }
}
