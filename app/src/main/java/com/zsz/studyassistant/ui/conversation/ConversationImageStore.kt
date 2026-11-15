package com.zsz.studyassistant.ui

import android.util.Base64
import android.util.Base64InputStream
import android.webkit.WebResourceResponse
import java.io.InputStream
import java.util.UUID

/** Per-renderer capabilities, never paths into app storage or network URLs. No decoded bitmap cache. */
internal class ConversationImageStore {
    private val prefix = "file:///android_asset/session-image/${UUID.randomUUID()}/"
    private val urls = HashMap<String, String>()
    private val originals = HashMap<String, String>()
    private var serial = 0L

    @Synchronized fun messages(messages: List<ChatMsg>): List<ChatMsg> {
        val retained = HashSet<String>()
        val result = messages.map { message ->
            if (message.images.isEmpty()) message else message.copy(images = message.images.map { encoded ->
                val url = urls[encoded] ?: (prefix + serial++).also {
                    urls[encoded] = it
                    originals[it] = encoded
                }
                retained.add(url)
                url
            })
        }
        urls.entries.removeAll { it.value !in retained }
        originals.keys.retainAll(retained)
        return result
    }

    @Synchronized fun original(url: String): String? = originals[url]
    @Synchronized fun clear() { urls.clear(); originals.clear() }

    fun response(url: String): WebResourceResponse? {
        val encoded = original(url) ?: return null
        // Decode as WebView reads: no full Base64 byte-array copy or extra decoded image byte array.
        val input = object : InputStream() {
            private var position = 0
            override fun read(): Int = if (position < encoded.length) encoded[position++].code else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= encoded.length) return -1
                val count = minOf(length, encoded.length - position)
                for (i in 0 until count) buffer[offset + i] = encoded[position++].code.toByte()
                return count
            }
        }
        return WebResourceResponse("image/jpeg", null, 200, "OK",
            mapOf("Cache-Control" to "no-store"), Base64InputStream(input, Base64.DEFAULT))
    }
}
