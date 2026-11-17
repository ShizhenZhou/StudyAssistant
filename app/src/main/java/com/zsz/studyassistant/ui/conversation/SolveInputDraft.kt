package com.zsz.studyassistant.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zsz.studyassistant.data.ConversationDocument
import java.util.UUID

/** Lives with one solve session across Activity recreation; image bytes never enter saved instance state. */
internal class SolveInputDraft(val storageKey: String? = null) {
    val id = UUID.randomUUID().toString()
    var restoring by mutableStateOf(false)
    var restoreFailed by mutableStateOf(false)
    var saveFailed by mutableStateOf(false)
    val editable: Boolean get() = !restoring && !restoreFailed
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
    var documentSelection by mutableStateOf<ConversationDocument?>(null)
    /** Applies only to historical attachments in the next accepted follow-up. */
    var excludedDocumentHashes by mutableStateOf<Set<String>>(emptySet())
    var importingDocuments by mutableStateOf(false)
    var awaitingDocumentPicker = false
    var awaitingPicker = false
    var fieldFocused = false
    var restoreIme by mutableStateOf(false)

    fun snapshot(): com.zsz.studyassistant.data.InputDraftSnapshot? {
        val key = storageKey ?: return null
        // A picker/crop is not accepted input yet. Only persist complete image/metadata pairs.
        val available = attachments.filter(images::containsKey)
        return com.zsz.studyassistant.data.InputDraftSnapshot(
            com.zsz.studyassistant.data.InputDraftMetadata(key, text, quote?.text, quote?.anchor,
                available.map { com.zsz.studyassistant.data.InputImageMetadata(it.toString(), correctedTexts[it]) },
                documents.toList(), documentSelection, excludedDocumentHashes.toSet()), available.map { images.getValue(it) })
    }

    fun restore(value: com.zsz.studyassistant.data.InputDraftSnapshot) {
        require(value.metadata.key == storageKey)
        text = value.metadata.text
        quote = value.metadata.quote?.let { QuoteSelection(it, value.metadata.anchor) }
        val uris = value.metadata.images.map { Uri.parse(it.uri) }
        images.putAll(uris.zip(value.images))
        value.metadata.images.forEachIndexed { index, item -> item.correctedText?.let { correctedTexts[uris[index]] = it } }
        attachments = uris
        documents = value.metadata.documents
        documentSelection = value.metadata.selection
        excludedDocumentHashes = value.metadata.excludedHashes
    }

    fun clearInput() {
        text = ""; quote = null; attachments = emptyList(); documents = emptyList()
        documentSelection = null; excludedDocumentHashes = emptySet()
    }

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
