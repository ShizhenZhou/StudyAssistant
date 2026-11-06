package com.zsz.studyassistant.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** Lives with one solve session across Activity recreation; image bytes never enter saved instance state. */
internal class SolveInputDraft {
    val id = UUID.randomUUID().toString()
    var text by mutableStateOf("")
    var quote by mutableStateOf<QuoteSelection?>(null)
    var attachments by mutableStateOf<List<Uri>>(emptyList())
    val images = androidx.compose.runtime.mutableStateMapOf<Uri, ByteArray>()
    var importingImages by mutableStateOf(false)
    var awaitingPicker = false
    var fieldFocused = false
    var restoreIme by mutableStateOf(false)
}
