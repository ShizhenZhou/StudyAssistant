package com.zsz.studyassistant.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zsz.studyassistant.data.ConversationDocument
import java.util.UUID

/** Lives with one solve session across Activity recreation; image bytes never enter saved instance state. */
internal class SolveInputDraft {
    val id = UUID.randomUUID().toString()
    var text by mutableStateOf("")
    var quote by mutableStateOf<QuoteSelection?>(null)
    val images = androidx.compose.runtime.mutableStateMapOf<Uri, ByteArray>()
    val correctedTexts = androidx.compose.runtime.mutableStateMapOf<Uri, String>()
    private var attachmentUris by mutableStateOf<List<Uri>>(emptyList())
    var attachments: List<Uri>
        get() = attachmentUris
        set(value) {
            attachmentUris = value
            images.keys.toList().filterNot { it in value }.forEach { images.remove(it) }
            correctedTexts.keys.toList().filterNot { it in value }.forEach { correctedTexts.remove(it) }
        }
    var importingImages by mutableStateOf(false)
    var documents by mutableStateOf<List<ConversationDocument>>(emptyList())
    var importingDocuments by mutableStateOf(false)
    var awaitingDocumentPicker = false
    var awaitingPicker = false
    var fieldFocused = false
    var restoreIme by mutableStateOf(false)

    /** Derive OCR labels from the final image order without changing the user's editable input. */
    fun requestText(lang: UiLang): String {
        val strings = stringsFor(lang)
        val corrections = attachments.filter(images::containsKey).mapIndexedNotNull { index, uri ->
            correctedTexts[uri]?.takeIf { it.isNotBlank() }?.let {
                strings.format("ocr.imageText", "n" to (index + 1).toString()) + "\n" + it
            }
        }
        return (listOf(text.trim()) + corrections).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
