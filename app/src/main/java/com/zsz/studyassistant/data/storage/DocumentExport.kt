package com.zsz.studyassistant.data

import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The entire SAF open/write/close happens off the UI thread, without a second UTF-8 byte array. */
suspend fun writeUtf8Document(text: String, output: () -> OutputStream?) = withContext(Dispatchers.IO) {
    checkNotNull(output()) { "Selected document is not writable" }
        .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}
