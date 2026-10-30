package com.zsz.studyassistant.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Untrusted shares are staged privately; accepting a share never sends an AI request. */
class SharedImageInbox(private val context: Context, private val scope: CoroutineScope) {
    var files by mutableStateOf<List<File>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var errorKey by mutableStateOf<String?>(null)
        private set
    private var generation = 0

    fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        try {
            if (loading || files.isNotEmpty()) { errorKey = "share.pending"; return }
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                else -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }.ifEmpty {
                val clip = intent.clipData
                if (clip == null || clip.itemCount > 2) emptyList()
                else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }
            if (intent.type?.startsWith("image/") != true || uris.size !in 1..2 ||
                uris.any { it.scheme != "content" || it.authority == "${context.packageName}.fileprovider" }) {
                errorKey = "share.invalid"; return
            }
            loading = true
            errorKey = null
            val ticket = ++generation
            scope.launch {
                val staged = mutableListOf<File>()
                var accepted = false
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        for (uri in uris) {
                            val type = context.contentResolver.getType(uri)
                            require(type == null || type.startsWith("image/") || type == "application/octet-stream")
                            val file = File.createTempFile("shared_", ".img", context.cacheDir)
                            staged += file
                            val input = requireNotNull(context.contentResolver.openInputStream(uri))
                            input.use { source -> file.outputStream().use { target ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 32L * 1024 * 1024)
                                    target.write(buffer, 0, count)
                                }
                                require(total > 0)
                            } }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0)
                        }
                    }
                    if (ticket == generation) { files = staged.toList(); accepted = true }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (ticket == generation) errorKey = "share.readFailed"
                } finally {
                    if (!accepted) withContext(Dispatchers.IO + NonCancellable) { staged.forEach { it.delete() } }
                    if (ticket == generation) loading = false
                }
            }
        } catch (_: Exception) {
            errorKey = "share.invalid"
        } finally {
            // Configuration recreation must not import the same incoming Intent again.
            intent.action = null
            intent.removeExtra(Intent.EXTRA_STREAM)
            intent.clipData = null
        }
    }

    fun dismissError() { errorKey = null }

    fun take(): List<File> = files.also { files = emptyList(); errorKey = null }

    fun discard() {
        generation++
        loading = false
        val old = take()
        // This scope is deliberately independent: ViewModel cancellation must not skip deletion.
        CoroutineScope(Dispatchers.IO).launch { old.forEach { it.delete() } }
    }
}
